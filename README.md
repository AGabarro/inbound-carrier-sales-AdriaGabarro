# Inbound Carrier Sales API

Backend for a HappyRobot voice agent that handles inbound carrier calls for a freight brokerage:
it verifies the carrier with FMCSA, enforces a one-time code, finds loads in a legacy TCP TMS,
negotiates the rate within a hidden ceiling, books the load and hands the carrier to a senior rep (mocked).

```
Carrier (web call)
   │
HappyRobot: voice agent + workflow, OTP Sender workflow, Twin, Apps
   │  HTTPS tool calls, headers X-API-Key and X-Call-ID
   ▼
This API (Spring Boot, Docker, Railway)
   ├─ FMCSA QCMobile REST API   carrier authority
   └─ Legacy TMS over TCP       load search, detail, booking
```

The backend exists because the platform can't speak the TMS's TCP protocol, the maximum rate must
never reach the language model, and verification must be enforced in code rather than in a prompt.

## Run it

Requirements: Docker only.

```bash
cp .env.example .env        # fill in the values (see below)
docker compose up --build   # builds, runs the tests, starts on http://localhost:8080
curl localhost:8080/health  # {"status":"UP"}
```

Tests run inside the image build. To run them on the host instead (Java 21+): `./mvnw test`.
One test talks to the live TMS and only runs when `TMS_TOKEN` is set:
`set -a; . ./.env; set +a; ./mvnw test -Dtest=TmsLiveSmokeTest`.

## Configuration

Environment variables only (locally, `docker compose` reads `.env`, which is git-ignored).

| Variable | Required | Purpose |
|---|---|---|
| `TMS_TOKEN` | yes | Legacy TMS auth token |
| `TMS_HOST`, `TMS_PORT` | no | TMS address (defaults to the challenge instance) |
| `FMCSA_API_KEY` | yes | FMCSA QCMobile `webKey` |
| `API_KEY` | yes | Key callers must send in `X-API-Key` |
| `CARRIER_CONTACTS` | no | Contact on file per MC, where codes are sent: `133655:ops@carrier.com,650000:...` |
| `OTP_SENDER_WEBHOOK_URL`, `OTP_SENDER_API_KEY` | no | HappyRobot "OTP Sender" workflow webhook. Empty: codes are not delivered |
| `APP_ENV` | no | `production` (default) or `development`. See below |
| `PORT` | no | HTTP port, default 8080 |

The app refuses to start if a required value is missing, naming the variable but never its value.

**Development never writes to the TMS.** With `APP_ENV=development`, bookings go through every check
(verified code, accepted rate, same load) and return the normal response, but nothing is sent to the TMS and
the booking reference starts with `DEV-`. Searches and load details still read the real TMS. The setting comes
from deployment config only, never from a request. As a guard, the app refuses to start if `APP_ENV=development`
while the platform says it's the production environment (`RAILWAY_ENVIRONMENT_NAME=production`).
Both `docker compose` and the cloud default to `production`; set `APP_ENV=development` for test deployments.
Every call summary includes `environment`, so test calls can be left out of the call log analytics.

## Deploy

The image is a standard container with env-only config, so it runs on any container platform.
The live deployment is Railway (`railway.json`: Dockerfile build, health check on `/health`, one replica):

```bash
railway up      # from a linked project; set the variables above with `railway variables`
```

Other clouds take the same image and variables, for example Google Cloud Run
(`gcloud run deploy --source . --set-env-vars ...`), AWS App Runner or Fly.io (`fly launch`, `fly secrets set ...`).
Keep a single instance: call state is held in memory (see Limits).

FMCSA blocks some non-US networks, so carrier verification only works from a US-hosted deployment.

## API

Every endpoint under `/api` needs `X-API-Key`; call-scoped ones also need `X-Call-ID` (the HappyRobot run ID).
Every response is HTTP 200 with the same fields every time: `error` (`""` on success, otherwise a code)
and `message` (a sentence the agent can say). Only a missing or wrong key gets a 401.

| Step | Endpoint | Body / query | Key fields |
|---|---|---|---|
| Verify carrier | `POST /api/carriers/verify` | `{"mc_number": "MC 133655"}` | `eligible`, `reason`, `carrier_name`, `home_base`, `power_units` |
| Send code | `POST /api/otp/send` | `{"mc_number": "133655"}` | `sent`, `destination_masked`, `resends_left` |
| Check code | `POST /api/otp/verify` | `{"code": "4 8 2 9 1 3"}` | `verified`, `attempts_left`, `locked` |
| Search loads | `GET /api/loads/search` | `origin_state`, `origin_city`, `destination_state`, `destination_city`, `equipment`, `pickup_date` | `found`, `loads` (max 3) |
| Load details | `GET /api/loads/{load_id}` | | `available`, rate, lane, dates, weight, commodity... |
| Negotiate | `POST /api/negotiations/offer` | `{"load_id": "LD00979", "counter_rate": 2400}` | `decision` (accept, counter, reject), `rate`, `round`, `final` |
| Book | `POST /api/bookings` | `{"load_id": "LD00979"}` | `booked`, `agreed_rate`, `booking_reference`, `transferred` |
| Call summary | `GET /api/calls/{call_id}/summary` | | `state`, `carrier_verifications`, `otp_challenges`, `negotiations`, `negotiation_offers`, `bookings` |
| Health | `GET /health` | | no key, liveness only |

The call summary is read by the end-of-call workflow, which writes its lists to the matching Twin tables
(the call log and audit). It includes failed attempts, never the maximum rate, the code or the full email,
and is available while the call's session lives (1 hour).

Example:

```bash
H=(-H "X-API-Key: $API_KEY" -H "X-Call-ID: demo-1" -H "Content-Type: application/json")
curl "${H[@]}" -X POST -d '{"mc_number":"MC 133655"}' localhost:8080/api/carriers/verify
curl "${H[@]}" -X POST -d '{}' localhost:8080/api/otp/send
curl "${H[@]}" -X POST -d '{"code":"482913"}' localhost:8080/api/otp/verify
curl "${H[@]}" "localhost:8080/api/loads/search?destination_state=TX&equipment=dry%20van"
curl "${H[@]}" -X POST -d '{"load_id":"LD00979","counter_rate":2400}' localhost:8080/api/negotiations/offer
curl "${H[@]}" -X POST -d '{"load_id":"LD00979"}' localhost:8080/api/bookings
```

## How the rules are enforced

- **Verification gate.** Loads, negotiation and booking refuse a call until FMCSA (active operating authority)
  and the one-time code have both passed. The code is generated and checked here, delivered by a separate
  HappyRobot workflow, and only ever sent to the contact on file. The agent never sees it.
- **Maximum rate.** Read from the TMS, kept in the call's session, never in a response or log. Each of the
  3 rounds has a ceiling that rises towards it and shifts per call; reasonable asks are met halfway, outrageous
  ones get a smaller step. The TMS itself does not check the maximum, so bookings always use the rate agreed here.
- **Unreliable TMS.** Strict parsing (exact fields and widths), short timeouts and retries for reads. A booking
  is sent once; after a failure the load's status is checked before anything else, so it is never booked twice.

## Code layout

```
tms/          TCP client and protocol (request encoding, strict response parsing)
fmcsa/        FMCSA client
carriers/     carrier verification
otp/          one-time code
loads/        load search and details
negotiation/  rate negotiation rules and service
booking/      booking and rep handoff
session/      short-lived state per call (verification, negotiation, booking)
summary/      call summary for the Twin call log
web/          API key filter, request log
config/       validated configuration
```

## Limits and next steps

- Call state is in memory: one instance only. With several, sessions and the per-load booking claim move to a
  shared store (Redis or the database).
- Codes go to a contact list in configuration; in production, the brokerage's carrier records.
- Carrier checks follow the brief (active operating authority). Insurance, safety rating, out-of-service history
  and equipment fit are natural additions.

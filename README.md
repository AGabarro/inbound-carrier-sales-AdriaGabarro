# Inbound Carrier Sales API

Backend for a HappyRobot voice agent that handles inbound carrier calls for a freight brokerage: verifies the
carrier with FMCSA, enforces a one-time code, finds loads in a legacy TCP TMS, negotiates within a hidden ceiling,
books the load and hands the carrier to a senior rep (mocked).

```
Carrier (web call) → HappyRobot (voice agent, OTP Sender workflow, Twin, Apps)
                        │  HTTPS, headers X-API-Key and X-Call-ID
                        ▼
                     This API (Spring Boot, Docker) ── FMCSA REST API
                                                    └─ Legacy TMS over TCP
```

It exists because the platform can't speak the TMS's TCP protocol, the maximum rate must never reach the
language model, and verification must be enforced in code rather than in a prompt.

## Quick start (about 5 minutes)

You need Docker, a TMS token, a free FMCSA web key (https://mobile.fmcsa.dot.gov/QCDevsite/) and a US network
(FMCSA blocks some other countries).

```bash
cp .env.example .env
```

Set these in `.env`:

```
TMS_TOKEN=...                         # your TMS token
FMCSA_API_KEY=...                     # your FMCSA web key
API_KEY=any-long-random-string        # the key callers must send
CARRIER_CONTACTS=133655:you@example.com
APP_ENV=development                   # bookings never reach the TMS
TEST_OTP_CODE=000000                  # every code is 000000, no email sent
```

```bash
docker compose up --build      # builds, runs the tests, starts on http://localhost:8080
curl localhost:8080/health     # {"status":"UP"}
```

## Run the whole workflow

Calls come from the HappyRobot voice agent, which calls this API as tools.

1. **Make the API reachable over HTTPS** (HappyRobot can't call `localhost`): deploy it (see Deploy), or for a
   local run open a tunnel: `cloudflared tunnel --url http://localhost:8080`.
2. **Point the workflow at it:** in the HappyRobot "Inbound Carrier Sales" workflow, set the variables
   `CARRIER_SALES_BASE_URL` (the HTTPS URL) and `CARRIER_SALES_API_KEY` (your `API_KEY`).
3. **Start a web call** from the workflow and talk to the agent as a carrier: give MC 133655, read the code
   (`000000` in development, otherwise check the email on file), ask for a load on any lane, negotiate, accept.
4. **See the result** in the "Carrier Sales Ops" dashboard: the call, its audit trail, and the load waiting for a
   senior rep. In development nothing is booked in the TMS (references start with `DEV-`).

Reviewers invited to the HappyRobot workspace can skip steps 1 and 2: start the web call in the **development**
environment, which already points at a live deployment where the code is always `000000` and nothing is booked in
the TMS. (In production the code is emailed to the carrier's contact on file.)

## Configuration

Environment variables only; the app refuses to start if a required one is missing (it names it, never its value).

| Variable | Required | Purpose |
|---|---|---|
| `TMS_TOKEN`, `FMCSA_API_KEY`, `API_KEY` | yes | TMS token, FMCSA web key, key callers send in `X-API-Key` |
| `CARRIER_CONTACTS` | to send codes | Contact on file per MC: `133655:ops@carrier.com,650000:...` |
| `OTP_SENDER_WEBHOOK_URL`, `OTP_SENDER_API_KEY` | no | Webhook that emails the code (HappyRobot "OTP Sender"); it receives `call_id, mc_number, carrier_name, channel, email, phone, code, expires_in_min` with the key in `x-api-key`. Empty: codes aren't emailed |
| `APP_ENV` | no | `production` (default) or `development` |
| `TEST_OTP_CODE` | no | Development only: fixed code, no email |
| `TMS_HOST`, `TMS_PORT`, `PORT` | no | Defaults: the challenge TMS, port 8080 |

**Production vs development.** Development runs every check but never sends a booking to the TMS (references
start with `DEV-`), and can use a fixed test code. Production refuses to start with either setting. Every call
summary says which environment it came from.

## Deploy

One container, configured only by environment variables, so any container platform works:

```bash
railway up                                   # Railway (railway.json: Dockerfile, /health check)
gcloud run deploy --source . --set-env-vars TMS_TOKEN=...,FMCSA_API_KEY=...,API_KEY=...   # Google Cloud Run
```

Run a single instance: call state is kept in memory.

## API

Every `/api` endpoint needs `X-API-Key`; call steps also need `X-Call-ID` (the HappyRobot run ID). Responses are
always HTTP 200 with the same fields: `error` (`""` on success, otherwise a code) and `message` (a sentence the
agent can say). Only a wrong key gets 401.

| Step | Endpoint | Input |
|---|---|---|
| Verify carrier | `POST /api/carriers/verify` | `{"mc_number": "MC 133655"}` |
| Send / check code | `POST /api/otp/send`, `POST /api/otp/verify` | `{}`, `{"code": "4 8 2 9 1 3"}` |
| Search / details | `GET /api/loads/search`, `GET /api/loads/{load_id}` | `origin_state`, `origin_city`, `destination_state`, `destination_city`, `equipment`, `pickup_date` |
| Negotiate | `POST /api/negotiations/offer` | `{"load_id": "...", "counter_rate": 2400}` |
| Book | `POST /api/bookings` | `{"load_id": "..."}` |
| Call summary | `GET /api/calls/{call_id}/summary` | |
| Health | `GET /health` | no key |

## How the rules are enforced

- **Verification gate:** loads, negotiation and booking refuse a call until FMCSA and the code have passed. The
  code is generated and checked here, emailed by a separate workflow; the agent never sees it.
- **Maximum rate:** read from the TMS and kept server-side, never in a response or log. Each of the 3 rounds has
  a ceiling rising towards it, shifted per call. The TMS doesn't check the maximum, so bookings always use the
  agreed rate.
- **Unreliable TMS:** strict parsing, short timeouts, retries for reads; a booking is sent once and its status is
  checked after any failure, so it's never booked twice.

Code: `tms/` (TCP client), `fmcsa/`, `carriers/`, `otp/`, `loads/`, `negotiation/`, `booking/`, `session/` (state
per call), `summary/` (call record), `web/` (API key, request log). Tests: `./mvnw test` (Java 21+), also run in
the Docker build.

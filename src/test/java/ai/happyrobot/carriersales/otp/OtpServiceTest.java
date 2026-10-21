package ai.happyrobot.carriersales.otp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.happyrobot.carriersales.carriers.CarrierDirectory;
import ai.happyrobot.carriersales.config.DeploymentProperties;
import ai.happyrobot.carriersales.otp.exception.OtpDeliveryException;
import ai.happyrobot.carriersales.otp.model.OtpDelivery;
import ai.happyrobot.carriersales.otp.model.OtpSendResult;
import ai.happyrobot.carriersales.otp.model.OtpVerifyResult;
import ai.happyrobot.carriersales.session.Session;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OtpServiceTest {

    private static final String CALL = "run-1";

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-19T12:00:00Z"));
    private final SessionStore sessions = new SessionStore();
    private final FakeSender sender = new FakeSender();
    private final OtpService otp = new OtpService(sessions,
            new CarrierDirectory("133655:jane@schneider.com"), sender, clock);

    @BeforeEach
    void carrierPassedFmcsa() {
        verifiedCarrier(CALL, "133655");
    }

    // --- send ---

    @Test
    void sendsACodeToTheContactOnFile() {
        OtpSendResult result = otp.send(CALL, "");

        assertThat(result.error()).isEmpty();
        assertThat(result.sent()).isTrue();
        assertThat(result.destinationMasked()).isEqualTo("j***@schneider.com");
        assertThat(result.message()).isEqualTo("I just sent a 6-digit code to j***@schneider.com.");
        assertThat(result.expiresInSec()).isEqualTo(300);
        assertThat(result.resendsLeft()).isEqualTo(1);
        assertThat(sender.last().email()).isEqualTo("jane@schneider.com");
        assertThat(sender.last().code()).matches("\\d{6}");
        assertThat(result.toString()).doesNotContain(sender.last().code());
        assertThat(sessions.find(CALL).orElseThrow().state()).isEqualTo(SessionState.OTP_SENT);
    }

    @Test
    void noCodeBeforeTheMcIsVerified() {
        assertThat(otp.send("run-unknown", "").error()).isEqualTo("MC_NOT_VERIFIED");
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void noContactOnFileMeansNoCodeAndNoAskingTheCaller() {
        verifiedCarrier("run-2", "650000");

        OtpSendResult result = otp.send("run-2", "");

        assertThat(result.error()).isEqualTo("NO_CONTACT_ON_FILE");
        assertThat(result.message()).contains("A rep will follow up");
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void cannotRequestACodeForAnotherMc() {
        assertThat(otp.send(CALL, "MC 650000").error()).isEqualTo("MC_MISMATCH");
        assertThat(otp.send(CALL, "MC 133655").sent()).isTrue();
    }

    @Test
    void twoSendsPerCallAtMost() {
        assertThat(otp.send(CALL, "").sent()).isTrue();
        assertThat(otp.send(CALL, "").resendsLeft()).isZero();

        OtpSendResult third = otp.send(CALL, "");

        assertThat(third.error()).isEqualTo("RESEND_LIMIT");
        assertThat(sender.sent).hasSize(2);
    }

    @Test
    void aResendInvalidatesThePreviousCode() {
        otp.send(CALL, "");
        String oldCode = sender.last().code();
        otp.send(CALL, "");
        String newCode = sender.last().code();

        if (!oldCode.equals(newCode)) {
            assertThat(otp.verify(CALL, "", oldCode).error()).isEqualTo("INVALID_CODE");
        }
        assertThat(otp.verify(CALL, "", newCode).verified()).isTrue();
    }

    @Test
    void failedDeliveryKeepsTheCallAsItWas() {
        sender.failNext = true;

        OtpSendResult result = otp.send(CALL, "");

        assertThat(result.error()).isEqualTo("DELIVERY_FAILED");
        assertThat(result.sent()).isFalse();
        Session session = sessions.find(CALL).orElseThrow();
        assertThat(session.state()).isEqualTo(SessionState.FMCSA_VERIFIED);
        assertThat(session.otpSends()).isZero();
    }

    // --- verify ---

    @Test
    void correctCodeVerifiesTheCall() {
        otp.send(CALL, "");

        OtpVerifyResult result = otp.verify(CALL, "133655", sender.last().code());

        assertThat(result.error()).isEmpty();
        assertThat(result.verified()).isTrue();
        assertThat(sessions.find(CALL).orElseThrow().state()).isEqualTo(SessionState.OTP_VERIFIED);
        assertThat(sessions.find(CALL).orElseThrow().otpHash()).isEmpty();
    }

    @Test
    void spokenCodesAreUnderstood() {
        otp.send(CALL, "");
        String code = sender.last().code();
        String spoken = code.chars().mapToObj(c -> List.of("oh", "one", "two", "three", "four", "five", "six",
                "seven", "eight", "nine").get(c - '0')).reduce((a, b) -> a + " " + b).orElseThrow();

        assertThat(otp.verify(CALL, "", spoken).verified()).isTrue();
    }

    @Test
    void separatorsAreIgnored() {
        otp.send(CALL, "");
        String c = sender.last().code();

        assertThat(otp.verify(CALL, "", c.substring(0, 1) + "-" + c.substring(1, 3) + " " + c.substring(3)).verified())
                .isTrue();
    }

    @Test
    void threeWrongCodesLockTheCall() {
        otp.send(CALL, "");
        String right = sender.last().code();
        String wrong = right.equals("000000") ? "111111" : "000000";

        assertThat(otp.verify(CALL, "", wrong).attemptsLeft()).isEqualTo(2);
        assertThat(otp.verify(CALL, "", wrong).message()).isEqualTo("That code didn't match. You have 1 attempt left.");
        OtpVerifyResult third = otp.verify(CALL, "", wrong);

        assertThat(third.error()).isEqualTo("LOCKED");
        assertThat(third.locked()).isTrue();
        // Locked for good: even the right code fails, and no new code can be sent.
        assertThat(otp.verify(CALL, "", right).error()).isEqualTo("LOCKED");
        assertThat(otp.send(CALL, "").error()).isEqualTo("LOCKED");
    }

    @Test
    void wrongLengthDoesNotUseAnAttempt() {
        otp.send(CALL, "");

        OtpVerifyResult result = otp.verify(CALL, "", "48291");

        assertThat(result.error()).isEqualTo("INVALID_FORMAT");
        assertThat(result.attemptsLeft()).isEqualTo(3);
    }

    @Test
    void expiredCodeCanBeReplacedOnce() {
        otp.send(CALL, "");
        String expired = sender.last().code();
        clock.advance(Duration.ofMinutes(6));

        OtpVerifyResult result = otp.verify(CALL, "", expired);

        assertThat(result.error()).isEqualTo("EXPIRED");
        assertThat(result.message()).isEqualTo("That code has expired. I can send you a new one.");
        otp.send(CALL, "");
        assertThat(otp.verify(CALL, "", sender.last().code()).verified()).isTrue();
    }

    @Test
    void verifyBeforeAnyCodeWasSent() {
        assertThat(otp.verify(CALL, "", "123456").error()).isEqualTo("NO_ACTIVE_OTP");
        assertThat(otp.verify("run-unknown", "", "123456").error()).isEqualTo("NO_ACTIVE_OTP");
    }

    @Test
    void cannotVerifyForAnotherMc() {
        otp.send(CALL, "");

        assertThat(otp.verify(CALL, "650000", sender.last().code()).error()).isEqualTo("MC_MISMATCH");
    }

    @Test
    void missingCallIdIsRefused() {
        assertThat(otp.send(null, "").error()).isEqualTo("MISSING_CALL_ID");
        assertThat(otp.verify(" ", "", "123456").error()).isEqualTo("MISSING_CALL_ID");
    }

    @Test
    void theCodeNeverAppearsInLogsOrResults(CapturedOutput output) {
        OtpSendResult sent = otp.send(CALL, "");
        String code = sender.last().code();
        OtpVerifyResult verified = otp.verify(CALL, "", code);

        assertThat(output).doesNotContain(code);
        assertThat(sent.toString()).doesNotContain(code);
        assertThat(verified.toString()).doesNotContain(code);
        assertThat(sender.last().toString()).doesNotContain(code);
    }

    // --- development test code ---

    private OtpService withTestCode() {
        return new OtpService(sessions, new CarrierDirectory("133655:jane@schneider.com"), sender, clock,
                DeploymentProperties.developmentWithTestCode("000000"));
    }

    @Test
    void developmentTestCodeVerifiesWithoutAnyEmail(CapturedOutput output) {
        OtpService development = withTestCode();

        OtpSendResult sent = development.send(CALL, "");

        assertThat(sent.sent()).isTrue();
        assertThat(sent.destinationMasked()).isEqualTo("j***@schneider.com");
        assertThat(sender.sent).isEmpty();
        assertThat(output).contains("test code issued for call " + CALL);
        assertThat(development.verify(CALL, "", "zero zero zero, zero zero zero").verified()).isTrue();
    }

    @Test
    void developmentTestCodeKeepsTheNormalRules() {
        OtpService development = withTestCode();
        development.send(CALL, "");

        assertThat(development.verify(CALL, "", "111111").error()).isEqualTo("INVALID_CODE");
        assertThat(development.verify(CALL, "", "111111").attemptsLeft()).isEqualTo(1);
        assertThat(development.verify(CALL, "", "111111").error()).isEqualTo("LOCKED");
        assertThat(development.verify(CALL, "", "000000").error()).isEqualTo("LOCKED");

        verifiedCarrier("run-2", "133655");
        development.send("run-2", "");
        clock.advance(Duration.ofMinutes(6));
        assertThat(development.verify("run-2", "", "000000").error()).isEqualTo("EXPIRED");
    }

    @Test
    void productionTreatsTheTestCodeAsAWrongCode() {
        otp.send(CALL, "");
        String test = sender.last().code().equals("000000") ? "999999" : "000000";

        assertThat(otp.verify(CALL, "", test).error()).isEqualTo("INVALID_CODE");
        assertThat(sender.sent).hasSize(1); // production still emails the real code
    }

    @Test
    void masksEmails() {
        assertThat(OtpService.mask("jane@schneider.com")).isEqualTo("j***@schneider.com");
        assertThat(OtpService.mask("@odd")).isEqualTo("***");
    }

    private void verifiedCarrier(String callId, String mc) {
        Session session = sessions.getOrCreate(callId);
        session.state(SessionState.FMCSA_VERIFIED);
        session.mcNumber(mc);
        session.carrierName("Test Carrier");
    }

    private static final class FakeSender implements OtpSender {
        final List<OtpDelivery> sent = new ArrayList<>();
        boolean failNext;

        @Override
        public void send(OtpDelivery delivery) {
            if (failNext) {
                failNext = false;
                throw new OtpDeliveryException("webhook timed out");
            }
            sent.add(delivery);
        }

        OtpDelivery last() {
            return sent.getLast();
        }
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

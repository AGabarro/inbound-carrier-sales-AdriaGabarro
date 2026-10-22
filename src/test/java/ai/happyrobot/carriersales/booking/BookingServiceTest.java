package ai.happyrobot.carriersales.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.happyrobot.carriersales.booking.model.BookingResult;
import ai.happyrobot.carriersales.config.DeploymentProperties;
import ai.happyrobot.carriersales.session.Negotiation;
import ai.happyrobot.carriersales.session.Negotiation.Round;
import ai.happyrobot.carriersales.session.Session;
import ai.happyrobot.carriersales.session.SessionState;
import ai.happyrobot.carriersales.session.SessionStore;
import ai.happyrobot.carriersales.tms.TmsClient;
import ai.happyrobot.carriersales.tms.exception.FaultKind;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.TmsBooking;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BookingServiceTest {

    private static final String CALL = "run-1";
    private static final TmsBooking BOOKING =
            new TmsBooking("LD01000", "GFZKYZRT3K4JOEAE", "PENDING", LocalDateTime.of(2026, 10, 20, 3, 37, 45));
    private static final TmsFaultException TIMEOUT = new TmsFaultException(FaultKind.TIMEOUT, "no reply");

    private final SessionStore sessions = new SessionStore();
    private final LoadClaims claims = new LoadClaims();
    private final TmsClient tms = mock(TmsClient.class);
    private final BookingService service = new BookingService(sessions, claims, tms, DeploymentProperties.production());

    @BeforeEach
    void agreedAt4202() {
        agreedCall(CALL, "LD01000", 4202);
    }

    @Test
    void booksAtTheAgreedRateAndHandsOff() {
        when(tms.book("LD01000", "133655", 4202)).thenReturn(BOOKING);

        BookingResult result = service.book(CALL, "", "ld-01000");

        assertThat(result.error()).isEmpty();
        assertThat(result.booked()).isTrue();
        assertThat(result.agreedRate()).isEqualTo(4202);
        assertThat(result.bookingReference()).isEqualTo("GFZKYZRT3K4JOEAE");
        assertThat(result.transferred()).isTrue();
        assertThat(result.repQueue()).isEqualTo("senior-carrier-sales");
        assertThat(sessions.find(CALL).orElseThrow().state()).isEqualTo(SessionState.BOOKED);
    }

    @Test
    void carrierIsToldTheLoadIsReservedAndARepWillConfirm() {
        when(tms.book("LD01000", "133655", 4202)).thenReturn(BOOKING);

        BookingResult result = service.book(CALL, "", "LD01000");

        assertThat(result.message())
                .isEqualTo("Great, I've reserved LD01000 for you at $4,202. "
                        + "A senior rep will confirm it with you and handle the paperwork.")
                .doesNotContain("booked", "all set");
    }

    @Test
    void productionBookingStillBooksInTheTms() {
        when(tms.book("LD01000", "133655", 4202)).thenReturn(BOOKING);

        BookingResult result = service.book(CALL, "", "LD01000");

        verify(tms, times(1)).book("LD01000", "133655", 4202);
        assertThat(result.bookingReference()).isEqualTo("GFZKYZRT3K4JOEAE");
    }

    @Test
    void developmentBookingNeverWritesToTheTms(CapturedOutput output) {
        BookingService development = new BookingService(sessions, claims, tms, DeploymentProperties.development());

        BookingResult result = development.book(CALL, "", "LD01000");
        BookingResult again = development.book(CALL, "", "LD01000");

        verifyNoInteractions(tms);
        // Same shape and checks as production; only the reference shows it never reached the TMS.
        assertThat(result.error()).isEmpty();
        assertThat(result.booked()).isTrue();
        assertThat(result.agreedRate()).isEqualTo(4202);
        assertThat(result.loadId()).isEqualTo("LD01000");
        assertThat(result.transferred()).isTrue();
        assertThat(result.bookingReference()).matches("DEV-[A-Z0-9]{12}");
        assertThat(again.bookingReference()).isEqualTo(result.bookingReference());
        assertThat(sessions.find(CALL).orElseThrow().booking().status()).isEqualTo("booked");
        assertThat(output).contains("booked in development, not sent to the TMS");
    }

    @Test
    void developmentLetsEveryTestCallBookTheSameLoad() {
        BookingService development = new BookingService(sessions, claims, tms, DeploymentProperties.development());
        agreedCall("run-2", "LD01000", 4100);

        BookingResult first = development.book(CALL, "", "LD01000");
        BookingResult second = development.book("run-2", "", "LD01000");

        assertThat(first.booked()).isTrue();
        assertThat(second.booked()).isTrue();
        assertThat(second.bookingReference()).startsWith("DEV-").isNotEqualTo(first.bookingReference());
        assertThat(claims.claim("LD01000", "a-production-call")).isTrue(); // development never claimed it
        verifyNoInteractions(tms);
    }

    @Test
    void developmentStillRunsEveryCheck() {
        BookingService development = new BookingService(sessions, claims, tms, DeploymentProperties.development());
        sessions.getOrCreate("run-6").state(SessionState.FMCSA_VERIFIED);
        sessions.getOrCreate("run-7").state(SessionState.OTP_VERIFIED);

        assertThat(development.book("run-6", "", "LD01000").error()).isEqualTo("OTP_NOT_VERIFIED");
        assertThat(development.book("run-7", "", "LD01000").error()).isEqualTo("NO_AGREEMENT");
        assertThat(development.book(CALL, "", "LD01024").error()).isEqualTo("LOAD_MISMATCH");
        verifyNoInteractions(tms);
    }

    @Test
    void askingAgainDoesNotBookTwice() {
        when(tms.book(anyString(), anyString(), anyInt())).thenReturn(BOOKING);
        service.book(CALL, "", "LD01000");

        BookingResult again = service.book(CALL, "", "LD01000");

        assertThat(again.booked()).isTrue();
        assertThat(again.bookingReference()).isEqualTo("GFZKYZRT3K4JOEAE");
        verify(tms, times(1)).book(anyString(), anyString(), anyInt());
    }

    @Test
    void anotherCallOfOursCannotBookTheSameLoad() {
        when(tms.book(anyString(), anyString(), anyInt())).thenReturn(BOOKING);
        service.book(CALL, "", "LD01000");
        agreedCall("run-2", "LD01000", 4100);

        BookingResult second = service.book("run-2", "", "LD01000");

        assertThat(second.error()).isEqualTo("LOAD_TAKEN");
        verify(tms, times(1)).book(anyString(), anyString(), anyInt());
        // Not the carrier's fault: still verified, free to negotiate another load, history kept.
        Session session = sessions.find("run-2").orElseThrow();
        assertThat(session.state()).isEqualTo(SessionState.OTP_VERIFIED);
        assertThat(session.negotiation()).isNull();
        assertThat(session.previousNegotiations()).extracting(Negotiation::loadId).containsExactly("LD01000");
    }

    @Test
    void askingAgainDoesNotHandOffTwice(CapturedOutput output) {
        when(tms.book(anyString(), anyString(), anyInt())).thenReturn(BOOKING);

        service.book(CALL, "", "LD01000");
        service.book(CALL, "", "LD01000");

        assertThat(output.getOut().lines().filter(line -> line.contains("Handoff to"))).hasSize(1);
    }

    @Test
    void noBookingWithoutAnAgreement() {
        sessions.getOrCreate("run-3").state(SessionState.OTP_VERIFIED);
        Session failed = sessions.getOrCreate("run-4");
        failed.state(SessionState.NEGOTIATION_FAILED);

        assertThat(service.book("run-3", "", "LD01000").error()).isEqualTo("NO_AGREEMENT");
        BookingResult afterReject = service.book("run-4", "", "LD01000");
        assertThat(afterReject.error()).isEqualTo("NO_AGREEMENT");
        assertThat(afterReject.transferred()).isFalse(); // brief: no deal, no transfer
        verifyNoInteractions(tms);
    }

    @Test
    void onlyTheAgreedLoad() {
        assertThat(service.book(CALL, "", "LD01024").error()).isEqualTo("LOAD_MISMATCH");
        verifyNoInteractions(tms);
    }

    @Test
    void refusedBeforeTheOtp() {
        sessions.getOrCreate("run-5").state(SessionState.FMCSA_VERIFIED);

        assertThat(service.book("run-5", "", "LD01000").error()).isEqualTo("OTP_NOT_VERIFIED");
        assertThat(service.book(null, "", "LD01000").error()).isEqualTo("MISSING_CALL_ID");
    }

    // --- verify after failure ---

    @Test
    void timeoutButTheLoadIsPendingMeansItWentThrough() {
        when(tms.book(anyString(), anyString(), anyInt())).thenThrow(TIMEOUT);
        when(tms.send(any())).thenReturn(List.of(load("PENDING")));

        BookingResult result = service.book(CALL, "", "LD01000");

        assertThat(result.booked()).isTrue();
        verify(tms, times(1)).book(anyString(), anyString(), anyInt()); // never booked twice
    }

    @Test
    void timeoutAndStillOpenIsTriedOnceMore() {
        when(tms.book(anyString(), anyString(), anyInt())).thenThrow(TIMEOUT).thenReturn(BOOKING);
        when(tms.send(any())).thenReturn(List.of(load("OPEN")));

        BookingResult result = service.book(CALL, "", "LD01000");

        assertThat(result.booked()).isTrue();
        verify(tms, times(2)).book(anyString(), anyString(), anyInt());
    }

    @Test
    void twoTimeoutsWhileOpenGoToARepUnconfirmed() {
        when(tms.book(anyString(), anyString(), anyInt())).thenThrow(TIMEOUT);
        when(tms.send(any())).thenReturn(List.of(load("OPEN")));

        BookingResult result = service.book(CALL, "", "LD01000");

        assertThat(result.error()).isEqualTo("BOOKING_UNCONFIRMED");
        assertThat(result.booked()).isFalse();
        assertThat(result.transferred()).isTrue();
        verify(tms, times(2)).book(anyString(), anyString(), anyInt());
    }

    @Test
    void timeoutAndStatusUnknownIsUnconfirmed() {
        when(tms.book(anyString(), anyString(), anyInt())).thenThrow(TIMEOUT);
        when(tms.send(any())).thenThrow(TIMEOUT);

        assertThat(service.book(CALL, "", "LD01000").error()).isEqualTo("BOOKING_UNCONFIRMED");
        verify(tms, times(1)).book(anyString(), anyString(), anyInt());
        // It may have gone through: no new negotiation while a rep confirms.
        assertThat(sessions.find(CALL).orElseThrow().state()).isEqualTo(SessionState.AGREED);
    }

    @Test
    void alreadyBookedIsUnconfirmedNotAFalseYes() {
        when(tms.book(anyString(), anyString(), anyInt())).thenThrow(new TmsErrorException("ALREADY_BOOKED", "x"));

        BookingResult result = service.book(CALL, "", "LD01000");

        assertThat(result.error()).isEqualTo("BOOKING_UNCONFIRMED");
        assertThat(result.booked()).isFalse();
    }

    @Test
    void refusedBookingReleasesTheClaim() {
        when(tms.book(anyString(), anyString(), anyInt())).thenThrow(new TmsErrorException("UNKNOWN_LOAD", "x"));

        assertThat(service.book(CALL, "", "LD01000").error()).isEqualTo("BOOKING_FAILED");
        assertThat(claims.claim("LD01000", "someone-else")).isTrue();
        assertThat(sessions.find(CALL).orElseThrow().state()).isEqualTo(SessionState.OTP_VERIFIED);
        verify(tms, never()).send(any());
    }

    private void agreedCall(String callId, String loadId, int rate) {
        Session session = sessions.getOrCreate(callId);
        session.mcNumber("133655");
        session.carrierName("Schneider National Carriers Inc");
        Negotiation negotiation = new Negotiation(loadId, 4002, 4600, new int[] {4200, 4400, 4600});
        negotiation.record(new Round(1, rate, "accept", rate));
        session.negotiation(negotiation);
        session.state(SessionState.AGREED);
    }

    private static TmsLoad load(String status) {
        LocalDateTime pickup = LocalDateTime.now().plusDays(1);
        return new TmsLoad("LD01000", "Philadelphia", "PA", "19104", "Amarillo", "TX", "79101", pickup,
                pickup.plusDays(2), "POWER_ONLY", 4002, 1490, status, 10094, "Lumber", 27,
                "42ft x 8ft x 9ft", "", 4600);
    }
}

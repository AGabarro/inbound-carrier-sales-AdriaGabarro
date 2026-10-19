package ai.happyrobot.carriersales.web.exception;

import ai.happyrobot.carriersales.fmcsa.exception.FmcsaUnavailableException;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions from any controller into an {@link ErrorResponse} the voice agent can relay. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ErrorResponse> badRequest(BadRequestException e) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    // Missing or broken JSON body (e.g. a misconfigured tool), in the same shape as every other error.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request body is missing or is not valid JSON.");
    }

    @ExceptionHandler(FmcsaUnavailableException.class)
    ResponseEntity<ErrorResponse> fmcsaUnavailable(FmcsaUnavailableException e) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "FMCSA_UNAVAILABLE",
                "I can't check your MC number right now. Please try again in a few minutes.");
    }

    // Still failing after the client's retries.
    @ExceptionHandler(TmsFaultException.class)
    ResponseEntity<ErrorResponse> tmsUnavailable(TmsFaultException e) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "TMS_UNAVAILABLE",
                "Our load system is slow right now, give me a moment and I'll try again.");
    }

    // "Unknown load" is not an error here: services turn it into a 200 business answer (available: false).
    @ExceptionHandler(TmsErrorException.class)
    ResponseEntity<ErrorResponse> tmsError(TmsErrorException e) {
        return switch (e.code()) {
            case "MISSING_FIELD" -> respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "I couldn't search with those details. Can you give me the lane again?");
            default -> {
                log.warn("Unexpected TMS error {}: {}", e.code(), e.tmsMessage());
                yield respond(HttpStatus.BAD_GATEWAY, "TMS_ERROR",
                        "Our load system couldn't handle that request right now.");
            }
        };
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(error, message));
    }
}

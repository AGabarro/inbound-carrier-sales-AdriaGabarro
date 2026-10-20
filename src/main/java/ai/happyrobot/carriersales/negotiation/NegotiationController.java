package ai.happyrobot.carriersales.negotiation;

import ai.happyrobot.carriersales.negotiation.model.OfferRequest;
import ai.happyrobot.carriersales.negotiation.model.OfferResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/negotiations")
public class NegotiationController {

    private static final String CALL_ID = "X-Call-ID";

    private final NegotiationService negotiationService;

    public NegotiationController(NegotiationService negotiationService) {
        this.negotiationService = negotiationService;
    }

    @PostMapping("/offer")
    public OfferResult offer(@RequestBody(required = false) OfferRequest request,
                             @RequestHeader(name = CALL_ID, required = false) String callId) {
        if (request == null) {
            return negotiationService.offer(callId, null, null, null);
        }
        return negotiationService.offer(callId, request.mcNumber(), request.loadId(), request.counterRate());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public OfferResult unreadableBody(HttpServletRequest request) {
        return negotiationService.offer(request.getHeader(CALL_ID), null, null, null);
    }
}

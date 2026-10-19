package ai.happyrobot.carriersales.otp;

import ai.happyrobot.carriersales.otp.model.OtpSendRequest;
import ai.happyrobot.carriersales.otp.model.OtpSendResult;
import ai.happyrobot.carriersales.otp.model.OtpVerifyRequest;
import ai.happyrobot.carriersales.otp.model.OtpVerifyResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/otp")
public class OtpController {

    private static final String CALL_ID = "X-Call-ID";

    private final OtpService otpService;

    public OtpController(OtpService otpService) {
        this.otpService = otpService;
    }

    @PostMapping("/send")
    public OtpSendResult send(@RequestBody(required = false) OtpSendRequest request,
                              @RequestHeader(name = CALL_ID, required = false) String callId) {
        return otpService.send(callId, request == null ? null : request.mcNumber());
    }

    @PostMapping("/verify")
    public OtpVerifyResult verify(@RequestBody(required = false) OtpVerifyRequest request,
                                  @RequestHeader(name = CALL_ID, required = false) String callId) {
        return otpService.verify(callId,
                request == null ? null : request.mcNumber(),
                request == null ? null : request.code());
    }

    // Broken JSON still gets the endpoint's normal 200 shape.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object unreadableBody(HttpServletRequest request) {
        String callId = request.getHeader(CALL_ID);
        return request.getRequestURI().endsWith("/verify")
                ? otpService.verify(callId, null, null)
                : otpService.send(callId, null);
    }
}

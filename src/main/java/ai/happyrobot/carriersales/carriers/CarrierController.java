package ai.happyrobot.carriersales.carriers;

import ai.happyrobot.carriersales.carriers.model.VerifyRequest;
import ai.happyrobot.carriersales.carriers.model.VerifyResult;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/carriers")
public class CarrierController {

    private final CarrierService carrierService;

    public CarrierController(CarrierService carrierService) {
        this.carrierService = carrierService;
    }

    @PostMapping("/verify")
    public VerifyResult verify(@RequestBody(required = false) VerifyRequest request) {
        return carrierService.verify(request == null ? null : request.mcNumber());
    }

    // Broken JSON still gets the normal 200 shape, so the agent can ask for the MC number again.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public VerifyResult unreadableBody() {
        return carrierService.verify(null);
    }
}

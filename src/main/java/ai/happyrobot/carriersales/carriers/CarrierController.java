package ai.happyrobot.carriersales.carriers;

import ai.happyrobot.carriersales.carriers.model.VerifyRequest;
import ai.happyrobot.carriersales.carriers.model.VerifyResult;
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
    public VerifyResult verify(@RequestBody VerifyRequest request) {
        return carrierService.verify(request.mcNumber());
    }
}

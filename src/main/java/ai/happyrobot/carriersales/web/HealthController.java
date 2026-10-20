package ai.happyrobot.carriersales.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Liveness only: doesn't call the TMS or FMCSA, so a flaky downstream can't get the container restarted.
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}

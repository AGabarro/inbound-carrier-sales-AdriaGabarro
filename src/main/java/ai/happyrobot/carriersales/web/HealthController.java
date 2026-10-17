package ai.happyrobot.carriersales.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness only, unauthenticated. Deliberately does not touch the TMS or FMCSA: a flaky
 * downstream shouldn't get the container restarted.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}

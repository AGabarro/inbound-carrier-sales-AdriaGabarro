package ai.happyrobot.carriersales.loads;

import ai.happyrobot.carriersales.loads.model.LoadDetails;
import ai.happyrobot.carriersales.loads.model.SearchResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Tools the voice agent calls. All filters are optional; the service asks for at least one.
@RestController
@RequestMapping("/api/loads")
public class LoadController {

    private final LoadService loadService;

    public LoadController(LoadService loadService) {
        this.loadService = loadService;
    }

    @GetMapping("/search")
    public SearchResult search(
            @RequestParam(name = "origin_state", required = false) String originState,
            @RequestParam(name = "origin_city", required = false) String originCity,
            @RequestParam(name = "destination_state", required = false) String destinationState,
            @RequestParam(name = "destination_city", required = false) String destinationCity,
            @RequestParam(name = "equipment", required = false) String equipment,
            @RequestParam(name = "pickup_date", required = false) String pickupDate) {
        return loadService.search(originState, originCity, destinationState, destinationCity, equipment, pickupDate);
    }

    @GetMapping("/{loadId}")
    public LoadDetails details(@PathVariable String loadId) {
        return loadService.details(loadId);
    }
}

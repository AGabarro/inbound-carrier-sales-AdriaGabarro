package ai.happyrobot.carriersales.loads;

import ai.happyrobot.carriersales.call.CallGate;
import ai.happyrobot.carriersales.loads.model.LoadDetails;
import ai.happyrobot.carriersales.loads.model.SearchResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Tools the voice agent calls, only after the call passed FMCSA and the OTP (CallGate).
// All filters are optional; the service asks for at least one.
@RestController
@RequestMapping("/api/loads")
public class LoadController {

    private static final String CALL_ID = "X-Call-ID";

    private final LoadService loadService;
    private final CallGate gate;

    public LoadController(LoadService loadService, CallGate gate) {
        this.loadService = loadService;
        this.gate = gate;
    }

    @GetMapping("/search")
    public SearchResult search(
            @RequestParam(name = "origin_state", required = false) String originState,
            @RequestParam(name = "origin_city", required = false) String originCity,
            @RequestParam(name = "destination_state", required = false) String destinationState,
            @RequestParam(name = "destination_city", required = false) String destinationCity,
            @RequestParam(name = "equipment", required = false) String equipment,
            @RequestParam(name = "pickup_date", required = false) String pickupDate,
            @RequestHeader(name = CALL_ID, required = false) String callId) {
        if (!gate.isVerified(callId)) {
            return SearchResult.problem(CallGate.NOT_VERIFIED, CallGate.NOT_VERIFIED_MESSAGE);
        }
        return loadService.search(originState, originCity, destinationState, destinationCity, equipment, pickupDate);
    }

    @GetMapping("/{loadId}")
    public LoadDetails details(@PathVariable String loadId,
                               @RequestHeader(name = CALL_ID, required = false) String callId) {
        if (!gate.isVerified(callId)) {
            return LoadDetails.unavailable(CallGate.NOT_VERIFIED, CallGate.NOT_VERIFIED_MESSAGE);
        }
        return loadService.details(loadId);
    }

    // HappyRobot's test call sends an empty load_id, which hits /api/loads/ itself.
    @GetMapping({"", "/"})
    public LoadDetails detailsWithoutId(@RequestHeader(name = CALL_ID, required = false) String callId) {
        return details("", callId);
    }
}

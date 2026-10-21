package ai.happyrobot.carriersales.summary;

import ai.happyrobot.carriersales.summary.model.CallSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CallSummaryController {

    private final CallSummaryService summaryService;

    public CallSummaryController(CallSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping("/api/calls/{callId}/summary")
    public CallSummary summary(@PathVariable String callId) {
        return summaryService.summary(callId);
    }
}

package ai.happyrobot.carriersales.summary;

import static org.assertj.core.api.Assertions.assertThat;

import ai.happyrobot.carriersales.config.DeploymentProperties;
import ai.happyrobot.carriersales.session.SessionStore;
import org.junit.jupiter.api.Test;

class CallSummaryServiceTest {

    private final SessionStore sessions = new SessionStore();

    @Test
    void developmentCallsAreMarked() {
        sessions.getOrCreate("run-1");
        CallSummaryService development = new CallSummaryService(sessions, DeploymentProperties.development());

        assertThat(development.summary("run-1").environment()).isEqualTo("development");
        assertThat(development.summary("unknown").environment()).isEqualTo("development");
    }

    @Test
    void productionCallsAreMarked() {
        sessions.getOrCreate("run-1");

        assertThat(new CallSummaryService(sessions, DeploymentProperties.production()).summary("run-1").environment())
                .isEqualTo("production");
    }
}

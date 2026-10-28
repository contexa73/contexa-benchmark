package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.service.UserEngineStatePurger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardRunsTest {

    @SuppressWarnings("unchecked")
    private static GuardRuns runs() {
        ObjectProvider<UserEngineStatePurger> purger = mock(ObjectProvider.class);
        when(purger.getIfAvailable()).thenReturn(null);
        // A negative delay never purges, so the test needs no engine.
        return new GuardRuns(new GuardProperties("bench-agent", "bench-user-", Duration.ofSeconds(-1), 100, null, null), purger);
    }

    @Test
    void everyRunIsANewSubjectWithTheFixedAgent() {
        GuardRuns runs = runs();
        GuardRuns.Run first = runs.begin("run-ab12", "Check my balance.", "banking");
        GuardRuns.Run second = runs.begin("run-cd34", "Check my balance.", "banking");
        assertThat(first.principal()).startsWith("bench-user-run-ab12-");
        assertThat(second.principal()).startsWith("bench-user-run-cd34-");
        assertThat(first.principal()).isNotEqualTo(second.principal());
        assertThat(first.agentId()).isEqualTo("bench-agent").isEqualTo(second.agentId());
        assertThat(first.goal()).isEqualTo("Check my balance.");
    }

    @Test
    void aRunIdUsedAgainIsANewSubject() {
        GuardRuns runs = runs();
        GuardRuns.Run first = runs.begin("run-1", "task", "banking");
        runs.end("run-1");
        GuardRuns.Run again = runs.begin("run-1", "task", "banking");
        assertThat(again.principal()).isNotEqualTo(first.principal());
        // and one begun again without an end replaces the earlier one
        GuardRuns.Run third = runs.begin("run-1", "task", "banking");
        assertThat(runs.find("run-1").principal()).isEqualTo(third.principal()).isNotEqualTo(again.principal());
    }

    @Test
    void anEndedRunIsUnknown() {
        GuardRuns runs = runs();
        runs.begin("run-2", "task", "banking");
        assertThat(runs.find("run-2")).isNotNull();
        assertThat(runs.end("run-2")).isNotNull();
        assertThat(runs.find("run-2")).isNull();
        assertThat(runs.end("run-2")).isNull();
        assertThat(runs.active()).isZero();
    }

    @Test
    void theRunIdBecomesASafeSubjectName() {
        assertThat(GuardRuns.safe(" run/../x y:1 ")).isEqualTo("run_.._x_y_1");
        assertThatThrownBy(() -> GuardRuns.safe(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}

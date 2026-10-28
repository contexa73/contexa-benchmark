package io.contexa.bench.guard;

import java.util.Map;

/** What a run needs from the engine's identity and delegation areas when it begins and ends; it differs by stage. */
public interface RunLifecycle {

    /** Prepares the run's subject, agent and delegation with the engine; what the answer of {@code /guard/begin} adds. */
    Map<String, Object> begin(GuardRuns.Run run);

    default void end(GuardRuns.Run run) {
    }
}

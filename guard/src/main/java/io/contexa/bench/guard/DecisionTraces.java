package io.contexa.bench.guard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the engine announced for each decision ID while it decided: the stages (layer 1, layer 2) with their action,
 * risk, confidence and reasoning, and every model call with its model, tokens and time. Kept in memory and bounded;
 * the engine's own decision records are in its database ({@code ai_security_decision_observation}).
 */
public class DecisionTraces {

    public record Stage(String type, Instant at, String layer, String action, Double riskScore, Double confidence,
                        String reasoning, Long elapsedMs, String detail) {
    }

    public record ModelCall(String model, long promptTokens, long completionTokens, long totalTokens, Long cachedTokens,
                            long elapsedMs, boolean success, Instant at) {
    }

    /** The trace of one decision; the lists are appended from the engine's threads. */
    public static final class Trace {
        private final List<Stage> stages = new ArrayList<>();
        private final List<ModelCall> calls = new ArrayList<>();

        public synchronized List<Stage> stages() {
            return List.copyOf(stages);
        }

        public synchronized List<ModelCall> calls() {
            return List.copyOf(calls);
        }

        synchronized void add(Stage stage) {
            stages.add(stage);
        }

        synchronized void add(ModelCall call) {
            calls.add(call);
        }
    }

    private final int limit;
    private final Map<String, Trace> byDecision;

    public DecisionTraces(int limit) {
        this.limit = limit;
        this.byDecision = new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Trace> eldest) {
                return size() > DecisionTraces.this.limit;
            }
        };
    }

    public void stage(String decisionId, Stage stage) {
        if (decisionId != null) {
            trace(decisionId).add(stage);
        }
    }

    public void call(String decisionId, ModelCall call) {
        if (decisionId != null) {
            trace(decisionId).add(call);
        }
    }

    /** The trace of the decision, empty if the engine announced nothing for it. */
    public Trace of(String decisionId) {
        synchronized (byDecision) {
            Trace trace = decisionId == null ? null : byDecision.get(decisionId);
            return trace != null ? trace : new Trace();
        }
    }

    private Trace trace(String decisionId) {
        synchronized (byDecision) {
            return byDecision.computeIfAbsent(decisionId, key -> new Trace());
        }
    }
}

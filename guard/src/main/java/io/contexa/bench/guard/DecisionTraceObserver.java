package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.event.LlmAnalysisEventObserver;

import java.time.Clock;
import java.util.Map;

/** Records the stages the engine announces for each decision (the engine's public observer extension point). */
public class DecisionTraceObserver implements LlmAnalysisEventObserver {

    private final DecisionTraces traces;
    private final Clock clock;

    public DecisionTraceObserver(DecisionTraces traces, Clock clock) {
        this.traces = traces;
        this.clock = clock;
    }

    @Override
    public void onContextCollected(String userId, String requestPath, Map<String, Object> metadata) {
        add(metadata, new DecisionTraces.Stage("CONTEXT_COLLECTED", clock.instant(), null, null, null, null, null, null,
                requestPath));
    }

    @Override
    public void onLayer1Start(String userId, String requestPath, Map<String, Object> metadata) {
        add(metadata, new DecisionTraces.Stage("LAYER1_START", clock.instant(), "LAYER1", null, null, null, null, null,
                null));
    }

    @Override
    public void onLayer1Complete(String userId, String action, Double riskScore, Double confidence, String reasoning,
                                 String mitre, Long elapsedMs, Map<String, Object> metadata) {
        add(metadata, new DecisionTraces.Stage("LAYER1_COMPLETE", clock.instant(), "LAYER1", action, riskScore,
                confidence, reasoning, elapsedMs, null));
    }

    @Override
    public void onLayer2Start(String userId, String requestPath, String reason, Map<String, Object> metadata) {
        add(metadata, new DecisionTraces.Stage("LAYER2_START", clock.instant(), "LAYER2", null, null, null, null, null,
                reason));
    }

    @Override
    public void onLayer2Complete(String userId, String action, Double riskScore, Double confidence, String reasoning,
                                 String mitre, Long elapsedMs, Map<String, Object> metadata) {
        add(metadata, new DecisionTraces.Stage("LAYER2_COMPLETE", clock.instant(), "LAYER2", action, riskScore,
                confidence, reasoning, elapsedMs, null));
    }

    @Override
    public void onDecisionApplied(String userId, String action, String layer, String requestPath,
                                  Map<String, Object> metadata) {
        add(metadata, new DecisionTraces.Stage("DECISION_APPLIED", clock.instant(), layer, action, null, null, null, null,
                null));
    }

    @Override
    public void onError(String userId, String message, Map<String, Object> metadata) {
        add(metadata, new DecisionTraces.Stage("ANALYSIS_ERROR", clock.instant(), null, null, null, null, null, null,
                message));
    }

    private void add(Map<String, Object> metadata, DecisionTraces.Stage stage) {
        Object requestId = metadata == null ? null : metadata.get("requestId");
        if (requestId != null) {
            traces.stage(requestId.toString(), stage);
        }
    }
}

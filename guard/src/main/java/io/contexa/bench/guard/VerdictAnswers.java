package io.contexa.bench.guard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The answer of {@code /guard/decide}: the verdict, and what the engine did for the call (the layer and the model that
 * decided, the tokens), from the trace the engine announced for the call's decision ID.
 */
public final class VerdictAnswers {

    /** The reason the engine announces when a layer had none of its own. */
    private static final String PLACEHOLDER_REASON = "Layer1 analysis completed";
    /** The stages the engine applies a decision at when the analysis failed or was held back (not a model's verdict). */
    private static final Set<String> FALLBACK_STAGES = Set.of("ESCALATE_PROTECTION", "COLD_PATH_FALLBACK",
            "LAYER1_FALLBACK");

    private VerdictAnswers() {
    }

    /**
     * @param facts what the call was judged with, as the guard knows it (the delegation, the records compared, the
     *              importance class); null for none
     */
    public static Map<String, Object> answer(VerdictMapper.Outcome outcome, DecisionTraces.Trace trace, String decisionId,
                                             long decisionMs, GuardTier tier, Map<String, Object> facts) {
        List<DecisionTraces.Stage> stages = trace.stages();
        List<DecisionTraces.ModelCall> calls = trace.calls();
        DecisionTraces.Stage deciding = last(stages, "LAYER2_COMPLETE");
        if (deciding == null) {
            deciding = last(stages, "LAYER1_COMPLETE");
        }
        boolean technicalFallback = stages.stream().anyMatch(stage -> "ANALYSIS_ERROR".equals(stage.type())
                || "DECISION_APPLIED".equals(stage.type()) && stage.layer() != null
                && FALLBACK_STAGES.contains(stage.layer()));
        String layer = deciding != null ? deciding.layer() : outcome.allowed() || outcome.unresolved() ? "NONE"
                : "STORED_RESTRICTION";
        String model = null;
        long promptTokens = 0;
        long completionTokens = 0;
        for (DecisionTraces.ModelCall call : calls) {
            promptTokens += call.promptTokens();
            completionTokens += call.completionTokens();
            if (call.success() && call.model() != null) {
                model = call.model();
            }
        }
        String reason = deciding != null && deciding.reasoning() != null && !deciding.reasoning().isBlank()
                && !PLACEHOLDER_REASON.equals(deciding.reasoning()) ? deciding.reasoning() : outcome.refusal();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("action", outcome.action());
        answer.put("reason", reason == null ? "" : reason);
        answer.put("layer", layer);
        answer.put("model", model);
        answer.put("decisionMs", decisionMs);
        answer.put("decisionId", decisionId);
        answer.put("engineAction", outcome.engineAction());
        answer.put("unresolved", outcome.unresolved() || technicalFallback);
        if (outcome.confirmationId() != null) {
            answer.put("confirmationId", outcome.confirmationId());
        }
        answer.put("technicalFallback", technicalFallback);
        answer.put("analysed", !calls.isEmpty());
        answer.put("modelCalls", calls.size());
        answer.put("promptTokens", promptTokens);
        answer.put("completionTokens", completionTokens);
        long cachedTokens = 0;
        List<Map<String, Object>> callList = new java.util.ArrayList<>();
        for (DecisionTraces.ModelCall call : calls) {
            cachedTokens += call.cachedTokens() == null ? 0 : call.cachedTokens();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("model", call.model());
            one.put("promptTokens", call.promptTokens());
            one.put("completionTokens", call.completionTokens());
            one.put("cachedTokens", call.cachedTokens());
            one.put("ms", call.elapsedMs());
            callList.add(one);
        }
        answer.put("cachedPromptTokens", cachedTokens);
        answer.put("modelCallList", callList);
        if (deciding != null) {
            answer.put("riskScore", deciding.riskScore());
            answer.put("confidence", deciding.confidence());
        }
        answer.put("tier", tier == null ? null : tier.name());
        if (facts != null && !facts.isEmpty()) {
            answer.put("facts", facts);
        }
        return answer;
    }

    private static DecisionTraces.Stage last(List<DecisionTraces.Stage> stages, String type) {
        DecisionTraces.Stage found = null;
        for (DecisionTraces.Stage stage : stages) {
            if (type.equals(stage.type())) {
                found = stage;
            }
        }
        return found;
    }
}

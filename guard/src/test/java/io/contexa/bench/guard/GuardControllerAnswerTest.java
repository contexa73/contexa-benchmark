package io.contexa.bench.guard;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardControllerAnswerTest {

    private static final Instant AT = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void anAnswerNamesTheLayerThatDecidedItsModelAndItsTokens() {
        DecisionTraces traces = new DecisionTraces(100);
        traces.stage("d1", new DecisionTraces.Stage("LAYER1_COMPLETE", AT, "LAYER1", "ESCALATE", 0.5, 0.6, "unclear", 9000L, null));
        traces.call("d1", new DecisionTraces.ModelCall("gpt-6-luna", 5000, 700, 5700, 0L, 9000, true, AT));
        traces.stage("d1", new DecisionTraces.Stage("LAYER2_COMPLETE", AT, "LAYER2", "ALLOW", 0.1, 0.9, "fits the task", 9000L, null));
        traces.call("d1", new DecisionTraces.ModelCall("claude-sonnet-5-5", 10000, 1000, 11000, null, 9000, true, AT));

        Map<String, Object> answer = GuardController.answer(VerdictMapper.allowed(), traces.of("d1"), "d1", 18_500);

        assertThat(answer).containsEntry("action", "ALLOW").containsEntry("layer", "LAYER2")
                .containsEntry("model", "claude-sonnet-5-5").containsEntry("decisionMs", 18_500L)
                .containsEntry("decisionId", "d1").containsEntry("reason", "fits the task")
                .containsEntry("analysed", true).containsEntry("modelCalls", 2)
                .containsEntry("promptTokens", 15_000L).containsEntry("completionTokens", 1_700L)
                .containsEntry("unresolved", false);
    }

    @Test
    void aRestrictionInForceAnswersWithoutAnyModelCall() {
        VerdictMapper.Outcome blocked = new VerdictMapper.Outcome("BLOCK", "BLOCK", false, "Zero Trust: BLOCK");
        Map<String, Object> answer = GuardController.answer(blocked, new DecisionTraces(100).of("none"), "d2", 3);
        assertThat(answer).containsEntry("action", "BLOCK").containsEntry("layer", "STORED_RESTRICTION")
                .containsEntry("model", null).containsEntry("analysed", false).containsEntry("reason", "Zero Trust: BLOCK");
    }

    @Test
    void aRefusalWithoutAVerdictIsMarkedUnresolved() {
        VerdictMapper.Outcome none = VerdictMapper.of(
                io.contexa.contexacore.autonomous.execution.ZeroTrustAccessDeniedException.analysisTimeout("r", 3000));
        Map<String, Object> answer = GuardController.answer(none, new DecisionTraces(100).of("none"), "d3", 3000);
        assertThat(answer).containsEntry("action", "BLOCK").containsEntry("unresolved", true)
                .containsEntry("engineAction", "PENDING_ANALYSIS").containsEntry("layer", "NONE");
    }

    @Test
    void thePlaceholderReasonOfAnEngineLayerIsNotAReason() {
        DecisionTraces traces = new DecisionTraces(100);
        traces.stage("d4", new DecisionTraces.Stage("LAYER1_COMPLETE", AT, "LAYER1", "BLOCK", 0.9, 0.9,
                "Layer1 analysis completed", 1000L, null));
        VerdictMapper.Outcome blocked = new VerdictMapper.Outcome("BLOCK", "BLOCK", false, "Zero Trust: BLOCK - blocked");
        assertThat(GuardController.answer(blocked, traces.of("d4"), "d4", 1000)).containsEntry("reason", "Zero Trust: BLOCK - blocked");
    }

    @Test
    void theTraceIsBounded() {
        DecisionTraces traces = new DecisionTraces(100);
        for (int i = 0; i < 150; i++) {
            traces.stage("id-" + i, new DecisionTraces.Stage("LAYER1_START", AT, "LAYER1", null, null, null, null, null, null));
        }
        assertThat(traces.of("id-0").stages()).isEmpty();
        assertThat(traces.of("id-149").stages()).hasSize(1);
    }

    @Test
    void anAnalysisThatFellBackIsMarkedAsNoVerdict() {
        DecisionTraces traces = new DecisionTraces(100);
        traces.stage("d5", new DecisionTraces.Stage("LAYER1_START", AT, "LAYER1", null, null, null, null, null, null));
        traces.stage("d5", new DecisionTraces.Stage("ANALYSIS_ERROR", AT, null, null, null, null, null, null,
                "Tiered AI analysis failed: HTTP 429"));
        VerdictMapper.Outcome fallback = new VerdictMapper.Outcome("CHALLENGE", "CHALLENGE", false, "Zero Trust: CHALLENGE");
        Map<String, Object> answer = GuardController.answer(fallback, traces.of("d5"), "d5", 26_000);
        assertThat(answer).containsEntry("action", "CHALLENGE").containsEntry("technicalFallback", true)
                .containsEntry("unresolved", true);
    }

    @Test
    void theEscalationOverloadProtectionIsNoModelsVerdictEither() {
        DecisionTraces traces = new DecisionTraces(100);
        traces.stage("d6", new DecisionTraces.Stage("DECISION_APPLIED", AT, "ESCALATE_PROTECTION", "CHALLENGE", null, null,
                null, null, null));
        VerdictMapper.Outcome fallback = new VerdictMapper.Outcome("CHALLENGE", "CHALLENGE", false, "Zero Trust: CHALLENGE");
        assertThat(GuardController.answer(fallback, traces.of("d6"), "d6", 10)).containsEntry("technicalFallback", true);
    }

    @Test
    void theAnswerNamesTheStageAndTheFactsTheCallWasJudgedWith() {
        Map<String, Object> facts = Map.of("importance", Map.of("class", "MONEY"));
        Map<String, Object> answer = VerdictAnswers.answer(VerdictMapper.allowed(), new DecisionTraces(100).of("x"), "x", 5,
                GuardTier.C2, facts);
        assertThat(answer).containsEntry("tier", "C2").containsEntry("facts", facts);
    }
}

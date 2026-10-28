package io.contexa.bench.guard;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Reads the engine's own records of a decision from its database: the observation row
 * ({@code ai_security_decision_observation}, one row per attempt, the newest is the final one) and the sealed evidence
 * package. This is the evidence that a verdict came from a real model call and not from this application.
 */
public class EngineDecisionRecords {

    private final JdbcTemplate engine;

    public EngineDecisionRecords(JdbcTemplate engine) {
        this.engine = engine;
    }

    public List<Map<String, Object>> observations(String decisionId) {
        return engine.queryForList("""
                select observation_id, request_id, user_id, final_action, proposed_action, llm_risk_score,
                       llm_confidence, model_id, success, technical_fallback, parser_failure, failure_type,
                       fallback_category, fallback_reason, llm_latency_ms, queue_wait_ms, prompt_build_ms,
                       rag_vector_ms, total_analysis_ms, decided_at
                  from ai_security_decision_observation
                 where request_id = ?
                 order by decided_at desc, observation_id desc""", decisionId);
    }

    public long observationCountOf(String principalPrefix) {
        Long count = engine.queryForObject(
                "select count(*) from ai_security_decision_observation where user_id like ?", Long.class,
                principalPrefix + "%");
        return count == null ? 0 : count;
    }
}

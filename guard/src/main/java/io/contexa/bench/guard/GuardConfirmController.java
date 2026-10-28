package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.agent.AgentConfirmation;
import io.contexa.contexacore.autonomous.agent.AgentConfirmationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The answer of the person accountable for an agent's request, as the confirmation screen of an application would send it
 * (stages C2 and C3). A CHALLENGE of the agent gate asks the person to confirm the request; the benchmark has no person, so
 * its runner asks a simulated owner who sees what a person sees on that screen (the owner's own request and the call the
 * agent wants to make) and posts the answer here. The answer is the engine's own: the confirmation is approved or rejected
 * through the confirmation service by the person it was addressed to, and an approved confirmation is used once, by the
 * agent sending the same call again naming it.
 */
@RestController
@ConditionalOnExpression("'${bench.guard.tier:C1}'.toUpperCase() != 'C1'")
public class GuardConfirmController {

    private final AgentConfirmationService confirmations;

    public GuardConfirmController(AgentConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    /**
     * @param body {@code confirmationId} (from the CHALLENGE answer), {@code approve} (true or false) and an optional
     *             {@code note}
     */
    @PostMapping("/guard/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody Map<String, Object> body) {
        String id = body.get("confirmationId") instanceof String text && !text.isBlank() ? text.trim() : null;
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "confirmationId is required"));
        }
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        String note = body.get("note") instanceof String text && !text.isBlank() ? text.trim() : null;
        Optional<AgentConfirmation> pending = confirmations.status(id);
        if (pending.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown confirmation"));
        }
        String person = pending.get().recipientId();
        AgentConfirmation decided;
        try {
            decided = approve ? confirmations.approve(id, person, note) : confirmations.reject(id, person, note);
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return ResponseEntity.status(409).body(Map.of("error", String.valueOf(refused.getMessage())));
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("confirmationId", id);
        answer.put("status", decided.status().name());
        answer.put("decidedBy", decided.decidedBy());
        return ResponseEntity.ok(answer);
    }
}

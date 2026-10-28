package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.execution.RapidProtectableReentryDeniedException;
import io.contexa.contexacore.autonomous.execution.ZeroTrustAccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;

/**
 * What a refusal of the protected gateway means. The engine refuses with a {@link ZeroTrustAccessDeniedException} whose
 * action is the verdict (BLOCK, CHALLENGE, ESCALATE) or, when it had none, PENDING_ANALYSIS (the analysis was required
 * but not completed, timed out, or the engine was busy). A request the method policies refuse (XACML) is a plain
 * {@link AuthorizationDeniedException}.
 * <p>
 * A refusal without a verdict is not a verdict: it is answered as BLOCK (the guard fails closed) and marked
 * {@code unresolved}, so a run that counts verdicts can tell a security decision from a technical failure.
 */
public final class VerdictMapper {

    /**
     * @param action       what the guard answers: ALLOW, CHALLENGE, BLOCK or ESCALATE
     * @param engineAction what the engine said: the same, or PENDING_ANALYSIS, RAPID_REENTRY or POLICY_DENIED
     * @param unresolved   the engine gave no verdict (the answer is the guard's fail-closed BLOCK)
     * @param refusal      the refusal's message, or null for ALLOW
     * @param confirmationId the confirmation the accountable person is asked to decide (a CHALLENGE of the agent gate),
     *                       or null
     */
    public record Outcome(String action, String engineAction, boolean unresolved, String refusal,
                          String confirmationId) {

        public Outcome(String action, String engineAction, boolean unresolved, String refusal) {
            this(action, engineAction, unresolved, refusal, null);
        }

        public boolean allowed() {
            return "ALLOW".equals(action);
        }
    }

    private VerdictMapper() {
    }

    public static Outcome allowed() {
        return new Outcome("ALLOW", "ALLOW", false, null);
    }

    public static Outcome of(AuthorizationDeniedException denied) {
        if (denied instanceof ZeroTrustAccessDeniedException zeroTrust) {
            String engineAction = zeroTrust.getAction();
            return switch (engineAction == null ? "" : engineAction) {
                case "BLOCK", "CHALLENGE", "ESCALATE" ->
                        new Outcome(engineAction, engineAction, false, zeroTrust.getMessage());
                default -> new Outcome("BLOCK", engineAction == null ? "UNKNOWN" : engineAction, true,
                        zeroTrust.getMessage());
            };
        }
        if (denied instanceof RapidProtectableReentryDeniedException) {
            return new Outcome("BLOCK", "RAPID_REENTRY", true, denied.getMessage());
        }
        return new Outcome("BLOCK", "POLICY_DENIED", false, denied.getMessage());
    }
}

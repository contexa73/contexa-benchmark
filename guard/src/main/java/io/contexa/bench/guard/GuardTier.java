package io.contexa.bench.guard;

/**
 * The stage of Contexa's feature areas the guard runs with (docs/agent 에이전트-공방시험체계-고도화-기획안.md 13.11). A run
 * can ask for a lower stage than the server's.
 */
public enum GuardTier {

    /**
     * Minimal: the task as the delegation's objective, the tool's name and its arguments, decided request by request
     * (host-owned authentication, the delegation stated on the request).
     */
    C1,

    /**
     * Configured: C1 plus the agent's identity and delegation token (the authorization server's token exchange, the
     * {@code act} chain), the delegation record and the agent registration, the company-record comparison, the objective
     * contract and scope, the resource importance of each tool, the call limit, the confirmation a CHALLENGE asks for and
     * the stop (the delegation revoked) a BLOCK brings.
     */
    C2,

    /** Full: C2 plus the agent's baseline (warm-up learning), the execution narrative and the signed records. */
    C3;

    public boolean atLeast(GuardTier other) {
        return compareTo(other) >= 0;
    }

    public static GuardTier lower(GuardTier a, GuardTier b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static GuardTier parse(String text, GuardTier fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return valueOf(text.trim().toUpperCase());
    }
}

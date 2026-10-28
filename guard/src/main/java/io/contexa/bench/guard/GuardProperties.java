package io.contexa.bench.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of the guard application ({@code bench.guard.*}).
 *
 * @param agentId         the agent ID of the benchmark's agent; from C2 on it is suffixed with the suite
 *                        ({@code bench-agent-banking}), one fixed agent per suite
 * @param principalPrefix the prefix of a run's acting subject: {@code <prefix><runId>-<generation>}. The subject is the
 *                        person the agent acts for, so its name must not say "agent": the decision model read a person
 *                        named {@code agent-...} as "the delegation was recorded by the agent itself"
 * @param purgeDelay      how long after {@code /guard/end} the engine state of the run's subject is purged (the decision's
 *                        asynchronous writes of the last call must have landed first); zero purges at once, a negative
 *                        value never purges
 * @param traceLimit      the most decisions whose analysis trace (layer events and model calls) is kept in memory
 * @param tier            the stage the server runs with ({@code C1}, {@code C2}, {@code C3}); it also selects the
 *                        ownership of authentication (host-owned for C1, Contexa-owned for C2 and C3)
 * @param delegation      how the run's delegation reaches the engine in C1
 */
@ConfigurationProperties(prefix = "bench.guard")
public record GuardProperties(String agentId, String principalPrefix, Duration purgeDelay, Integer traceLimit,
                              GuardTier tier, Delegation delegation) {

    /** How the delegation the run's agent acts under is stated to the engine. */
    public enum DelegationMode {
        /**
         * The run's delegation is only stated on the request ({@code ctxa.delegation.*} request attributes: delegated,
         * the agent, the task as the objective). The engine reads it as the word of the caller: no record shows the
         * person handed the work to the agent.
         */
        ATTRIBUTES,
        /**
         * The run's delegation is also recorded in Contexa's delegation registry (delegator = the run's subject, agent,
         * the task as the objective, valid for the run's lifetime) and the benchmark agent is registered in the agent
         * registry. The engine's default delegation lookup then finds the record for every call of the run. C2 always
         * records; this mode only selects it for C1 experiments.
         */
        RECORD
    }

    /**
     * @param mode     {@link DelegationMode} (C1)
     * @param validity how long a recorded delegation is valid (a run is shorter)
     * @param sponsor  the person accountable for the benchmark agent in the agent registry
     */
    public record Delegation(DelegationMode mode, Duration validity, String sponsor) {

        public Delegation {
            mode = mode == null ? DelegationMode.ATTRIBUTES : mode;
            validity = validity == null || validity.isNegative() || validity.isZero() ? Duration.ofMinutes(30) : validity;
            sponsor = sponsor == null || sponsor.isBlank() ? "bench-operator" : sponsor.trim();
        }
    }

    public GuardProperties {
        agentId = agentId == null || agentId.isBlank() ? "bench-agent" : agentId.trim();
        principalPrefix = principalPrefix == null || principalPrefix.isBlank() ? "bench-user-" : principalPrefix.trim();
        purgeDelay = purgeDelay == null ? Duration.ofSeconds(30) : purgeDelay;
        traceLimit = traceLimit == null || traceLimit < 100 ? 20_000 : traceLimit;
        tier = tier == null ? GuardTier.C1 : tier;
        delegation = delegation == null ? new Delegation(null, null, null) : delegation;
    }
}

package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.agent.AgentDelegation;
import io.contexa.contexacore.autonomous.agent.AgentDelegationRequest;
import io.contexa.contexacore.autonomous.agent.AgentRegistrationRequest;
import io.contexa.contexacore.autonomous.agent.AgentRegistry;
import io.contexa.contexacore.autonomous.agent.DelegationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * The record of a run's delegation in Contexa's own registries: the person (the run's subject) handed the task to the
 * benchmark agent, which is a registered agent. Every call of the run is then decided with that record: the engine's
 * default delegation lookup finds it by the delegator and the agent, so the delegation is shown by a record and not only
 * by the word of the caller.
 * <p>
 * Stage C1 records only in {@code RECORD} mode; in {@code ATTRIBUTES} mode nothing is recorded and the delegation is only
 * stated on each request. From C2 on every run's delegation is recorded ({@link #record}).
 */
public class RunDelegations implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(RunDelegations.class);

    /** The kind the benchmark agent is registered as. */
    static final String AGENT_TYPE = "BENCHMARK_AGENT";

    private final GuardProperties properties;
    private final ObjectProvider<DelegationRegistry> delegations;
    private final ObjectProvider<AgentRegistry> agents;
    private final Clock clock;

    public RunDelegations(GuardProperties properties, ObjectProvider<DelegationRegistry> delegations,
                          ObjectProvider<AgentRegistry> agents, Clock clock) {
        this.properties = properties;
        this.delegations = delegations;
        this.agents = agents;
        this.clock = clock;
    }

    private boolean recording() {
        return properties.delegation().mode() == GuardProperties.DelegationMode.RECORD;
    }

    /** C1 in RECORD mode: registers the benchmark agent once, approved, with the person accountable for it. */
    @Override
    public void afterSingletonsInstantiated() {
        if (!recording() || properties.tier().atLeast(GuardTier.C2)) {
            return;
        }
        AgentRegistry registry = agents.getIfAvailable();
        if (registry == null) {
            throw new IllegalStateException("RECORD mode needs the agent registry of Contexa");
        }
        registry.register(new AgentRegistrationRequest(null, properties.agentId(), "Benchmark agent " + properties.agentId(),
                AGENT_TYPE, null, properties.delegation().sponsor(), true));
        log.info("[guard] agent {} registered (sponsor {})", properties.agentId(), properties.delegation().sponsor());
    }

    /** Stage C1: records the delegation only in RECORD mode; what the answer of {@code /guard/begin} adds. */
    public Map<String, Object> beginC1(GuardRuns.Run run) {
        String delegationId = recording() ? record(run) : null;
        run.delegationId(delegationId);
        return delegationId == null ? Map.of() : Map.of("delegationId", delegationId);
    }

    /**
     * Records the run's delegation: the person is the run's subject, the agent the run's agent, the objective the task, the
     * scope the suite's policy (family, operations, resources).
     *
     * @return the delegation's ID
     */
    public String record(GuardRuns.Run run) {
        DelegationRegistry registry = delegations.getIfAvailable();
        if (registry == null) {
            throw new IllegalStateException("Recording a delegation needs the delegation registry of Contexa");
        }
        Instant now = clock.instant();
        GuardRuns.Scope scope = run.scope();
        AgentDelegation recorded = registry.record(new AgentDelegationRequest(null, run.principal(), run.agentId(), null,
                scope.family(), run.goal().isBlank() ? null : run.goal(),
                scope.allowedOperations().isEmpty() ? null : scope.allowedOperations(),
                // The resources are not recorded: the engine compares them with the resource family of the request, which
                // the guard does not declare for a tool (the path of the tool endpoint holds no resource words), so a list
                // would only read as drift. The operations are compared with the request's declared action family.
                null, null, null, null, now,
                now.plus(properties.delegation().validity()), run.principal(), now));
        return recorded.delegationId();
    }
}

package io.contexa.bench.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a run needs from the engine's identity and delegation areas from C2 on: the person's account, the suite's agent
 * (client and registration), the delegation record (the task as the objective, the suite's scope) and the agent's
 * delegated access token. A run that asked for a lower stage than the server's gets none of it.
 */
public class FullLifecycle implements RunLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FullLifecycle.class);

    private final RunPersons persons;
    private final SuiteAgents agents;
    private final RunDelegations delegations;
    private final DelegationTokens tokens;

    public FullLifecycle(RunPersons persons, SuiteAgents agents, RunDelegations delegations, DelegationTokens tokens) {
        this.persons = persons;
        this.agents = agents;
        this.delegations = delegations;
        this.tokens = tokens;
    }

    @Override
    public Map<String, Object> begin(GuardRuns.Run run) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("tier", run.tier().name());
        if (!run.tier().atLeast(GuardTier.C2)) {
            return answer;
        }
        persons.create(run);
        SuiteAgents.Agent agent = agents.ensure(run.agentId(), run.suite());
        // The delegation is recorded before the exchange: the exchanged token then names it (delegation_id).
        String delegationId = delegations.record(run);
        run.delegationId(delegationId);
        run.accessToken(tokens.issue(run, agent));
        run.clientId(agent.clientId());
        answer.put("delegationId", delegationId);
        answer.put("clientId", agent.clientId());
        log.info("[guard] run {} has its delegation {} and the agent {}'s delegated token", run.runId(), delegationId,
                run.agentId());
        return answer;
    }
}

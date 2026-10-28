package io.contexa.bench.guard;

import io.contexa.autoconfigure.capability.CapabilityDiagnosticsEndpoint;
import io.contexa.contexacore.autonomous.agent.AgentCallGuard;
import io.contexa.contexacore.autonomous.agent.AgentConfirmationService;
import io.contexa.contexacore.autonomous.agent.AgentContainmentService;
import io.contexa.contexacore.autonomous.agent.AgentRegistry;
import io.contexa.contexacore.autonomous.agent.AgentScopeService;
import io.contexa.contexacore.autonomous.agent.DelegationRegistry;
import io.contexa.contexacore.autonomous.service.UserEngineStatePurger;
import io.contexa.contexacore.properties.SecurityZeroTrustProperties;
import io.contexa.contexacore.std.llm.model.DynamicModelRegistry;
import io.contexa.contexacore.std.llm.runtime.LlmRuntimeCatalog;
import io.contexa.contexacore.verification.evidence.EvidenceSigner;
import org.springframework.ai.chat.model.ChatModel;
import io.contexa.contexaidentity.security.token.service.TokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/** Wiring of the guard's own parts and of the observation extension points of the engine (stage events and model calls). */
@Configuration(proxyBeanMethods = false)
public class GuardConfiguration {

    private final Clock clock = Clock.systemUTC();

    @Bean
    public GuardRuns guardRuns(GuardProperties properties, ObjectProvider<UserEngineStatePurger> purger) {
        return new GuardRuns(properties, purger);
    }

    @Bean
    public ToolTable toolTable(@org.springframework.beans.factory.annotation.Value(
            "${bench.guard.declarations-dir:E:/projects/contexa-benchmark/declarations}") String declarationsDir) {
        return new ToolTable(java.nio.file.Path.of(declarationsDir));
    }

    @Bean
    public CallContexts callContexts(GuardProperties properties) {
        return new CallContexts(properties.traceLimit());
    }

    @Bean
    public RunDelegations runDelegations(GuardProperties properties, ObjectProvider<DelegationRegistry> delegations,
                                         ObjectProvider<AgentRegistry> agents) {
        return new RunDelegations(properties, delegations, agents, clock);
    }

    @Bean
    public DecisionTraces decisionTraces(GuardProperties properties) {
        return new DecisionTraces(properties.traceLimit());
    }

    @Bean
    public DecisionTraceObserver decisionTraceObserver(DecisionTraces traces) {
        return new DecisionTraceObserver(traces, clock);
    }

    @Bean
    public ModelCallMeter modelCallMeter(DecisionTraces traces) {
        return new ModelCallMeter(traces, clock);
    }

    @Bean
    public EngineDecisionRecords engineDecisionRecords(@Qualifier("contexaJdbcTemplate") JdbcTemplate engine) {
        return new EngineDecisionRecords(engine);
    }

    @Bean
    public GuardCapabilities guardCapabilities(GuardProperties properties, Environment environment, ToolTable tools,
                                               SecurityZeroTrustProperties zeroTrust,
                                               ObjectProvider<TokenService> tokenService,
                                               ObjectProvider<DelegationRegistry> delegationRegistry,
                                               ObjectProvider<AgentRegistry> agentRegistry,
                                               ObjectProvider<AgentConfirmationService> confirmations,
                                               ObjectProvider<AgentContainmentService> containment,
                                               ObjectProvider<AgentCallGuard> callGuard,
                                               ObjectProvider<AgentScopeService> scopes,
                                               ObjectProvider<EvidenceSigner> signer,
                                               ObjectProvider<BenchRecordsContextProvider> recordsProvider,
                                               ObjectProvider<CapabilityDiagnosticsEndpoint> engineReport,
                                               ObjectProvider<LlmRuntimeCatalog> runtimeCatalog,
                                               ObjectProvider<DynamicModelRegistry> modelRegistry,
                                               @Qualifier("primaryChatModel") ObjectProvider<ChatModel> primaryChatModel) {
        return new GuardCapabilities(properties, environment, tools, zeroTrust, tokenService, delegationRegistry,
                agentRegistry, confirmations, containment, callGuard, scopes, signer, recordsProvider, engineReport,
                runtimeCatalog, modelRegistry, primaryChatModel);
    }
}

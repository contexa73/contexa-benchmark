package io.contexa.bench.guard;

import io.contexa.autoconfigure.capability.CapabilityDiagnosticsEndpoint;
import io.contexa.contexacore.autonomous.agent.AgentCallGuard;
import io.contexa.contexacore.autonomous.agent.AgentConfirmationService;
import io.contexa.contexacore.autonomous.agent.AgentContainmentService;
import io.contexa.contexacore.autonomous.agent.AgentRegistry;
import io.contexa.contexacore.autonomous.agent.AgentScopeService;
import io.contexa.contexacore.autonomous.agent.DelegationRegistry;
import io.contexa.contexacore.properties.SecurityZeroTrustProperties;
import io.contexa.contexacore.std.llm.model.DynamicModelRegistry;
import io.contexa.contexacore.std.llm.runtime.LlmRuntimeBinding;
import io.contexa.contexacore.std.llm.runtime.LlmRuntimeCatalog;
import io.contexa.contexacore.verification.evidence.EvidenceSigner;
import org.springframework.ai.chat.model.ChatModel;
import io.contexa.contexaidentity.security.token.service.TokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of Contexa's feature areas the guard runs with, and the evidence that each is on: the stage of the server, the
 * area, whether it is on (the bean of the engine that does the work is present and the stage includes it) and what
 * decides that. The engine's own capability report ({@code contexacapabilities}) is included as it reports itself.
 * <p>
 * An area listed here is on in the engine; whether it was used in a decision is shown by the decision's facts
 * ({@code facts} of the answer, and the sealed prompt of the decision).
 */
public class GuardCapabilities {

    private final GuardProperties properties;
    private final Environment environment;
    private final ToolTable tools;
    private final SecurityZeroTrustProperties zeroTrust;
    private final ObjectProvider<TokenService> tokenService;
    private final ObjectProvider<DelegationRegistry> delegationRegistry;
    private final ObjectProvider<AgentRegistry> agentRegistry;
    private final ObjectProvider<AgentConfirmationService> confirmations;
    private final ObjectProvider<AgentContainmentService> containment;
    private final ObjectProvider<AgentCallGuard> callGuard;
    private final ObjectProvider<AgentScopeService> scopes;
    private final ObjectProvider<EvidenceSigner> signer;
    private final ObjectProvider<BenchRecordsContextProvider> recordsProvider;
    private final ObjectProvider<CapabilityDiagnosticsEndpoint> engineReport;
    private final ObjectProvider<LlmRuntimeCatalog> runtimeCatalog;
    private final ObjectProvider<DynamicModelRegistry> modelRegistry;
    private final ObjectProvider<ChatModel> primaryChatModel;

    public GuardCapabilities(GuardProperties properties, Environment environment, ToolTable tools,
                             SecurityZeroTrustProperties zeroTrust, ObjectProvider<TokenService> tokenService,
                             ObjectProvider<DelegationRegistry> delegationRegistry,
                             ObjectProvider<AgentRegistry> agentRegistry,
                             ObjectProvider<AgentConfirmationService> confirmations,
                             ObjectProvider<AgentContainmentService> containment, ObjectProvider<AgentCallGuard> callGuard,
                             ObjectProvider<AgentScopeService> scopes, ObjectProvider<EvidenceSigner> signer,
                             ObjectProvider<BenchRecordsContextProvider> recordsProvider,
                             ObjectProvider<CapabilityDiagnosticsEndpoint> engineReport,
                             ObjectProvider<LlmRuntimeCatalog> runtimeCatalog,
                             ObjectProvider<DynamicModelRegistry> modelRegistry,
                             ObjectProvider<ChatModel> primaryChatModel) {
        this.properties = properties;
        this.environment = environment;
        this.tools = tools;
        this.zeroTrust = zeroTrust;
        this.tokenService = tokenService;
        this.delegationRegistry = delegationRegistry;
        this.agentRegistry = agentRegistry;
        this.confirmations = confirmations;
        this.containment = containment;
        this.callGuard = callGuard;
        this.scopes = scopes;
        this.signer = signer;
        this.recordsProvider = recordsProvider;
        this.engineReport = engineReport;
        this.runtimeCatalog = runtimeCatalog;
        this.modelRegistry = modelRegistry;
        this.primaryChatModel = primaryChatModel;
    }

    public Map<String, Object> report() {
        GuardTier tier = properties.tier();
        SecurityZeroTrustProperties.AgentSettings agent = zeroTrust.getAgent();
        List<Map<String, Object>> areas = new ArrayList<>();
        area(areas, "C1", "request-level synchronous decision (Layer 1, Layer 2 when escalated)", true,
                "@Protectable(sync = true) gateway; mode " + zeroTrust.getMode());
        area(areas, "C1", "delegation objective (the user's task) and tool call arguments as untrusted text", true,
                "delegation objectiveSummary; InvocationArguments summary of the gateway call");
        boolean c2 = tier.atLeast(GuardTier.C2);
        area(areas, "C2", "agent identity and delegation token (authorization server token exchange, act chain)",
                c2 && tokenService.getIfAvailable() != null,
                "contexa.bridge.ownership=" + environment.getProperty("contexa.bridge.ownership")
                        + "; token exchange " + environment.getProperty("contexa.auth.oauth2.token-exchange-enabled"));
        area(areas, "C2", "delegation record (delegator, objective, allowed operations and resources, expiry)",
                c2 && delegationRegistry.getIfAvailable() != null,
                "DelegationRegistry " + type(delegationRegistry.getIfAvailable()));
        area(areas, "C2", "agent registration (one agent per suite, approved, with its sponsor)",
                c2 && agentRegistry.getIfAvailable() != null, "AgentRegistry " + type(agentRegistry.getIfAvailable()));
        area(areas, "C2", "company-record comparison (known counterparties, contacts, files, channels)",
                c2 && recordsProvider.getIfAvailable() != null, "BenchRecordsContextProvider (FrictionContextProvider)");
        area(areas, "C2", "objective contract and delegation scope (family, allowed operations and resources)", c2,
                "objective contracts configured: " + agent.getObjectiveContracts().size()
                        + "; the scope of a run comes from /guard/begin");
        area(areas, "C2", "resource importance declared per tool class (@Protectable importance, privileged, exportSensitive)",
                c2, "tool table v" + tools.version() + ", sha256 " + tools.sha256() + ", " + tools.listedTools()
                        + " listed tools");
        area(areas, "C2", "call limit", c2 && agent.getCallLimit().isEnabled() && callGuard.getIfAvailable() != null,
                agent.getCallLimit().getPerDelegationPerMinute() + "/min per delegation, "
                        + agent.getCallLimit().getPerAgentPerMinute() + "/min per agent, on exceed "
                        + agent.getCallLimit().getOnExceed());
        area(areas, "C2", "confirmation a CHALLENGE asks for (the accountable person; by variant: refused, a simulated owner, or an owner who approves)",
                c2 && confirmations.getIfAvailable() != null, "AgentConfirmationService, timeout "
                        + agent.getConfirmationTimeout());
        area(areas, "C2", "stop on BLOCK (delegation revoked, tokens revoked)", c2 && containment.getIfAvailable() != null,
                "AgentContainmentService");
        area(areas, "C2", "scope narrowing after a CHALLENGE, ESCALATE, BLOCK or a risky ALLOW",
                c2 && agent.getScopeNarrowing().isEnabled() && scopes.getIfAvailable() != null,
                "for " + agent.getScopeNarrowing().getDuration() + ", risk threshold "
                        + agent.getScopeNarrowing().getRiskThreshold());
        boolean c3 = tier.atLeast(GuardTier.C3);
        area(areas, "C3", "agent baseline (warm-up learning)", false, "not implemented in this build");
        area(areas, "C3", "signed decision records and sealed evidence (the signing key persists across restarts)",
                signer.getIfAvailable() != null,
                "EvidenceSigner " + (signer.getIfAvailable() != null ? signer.getIfAvailable().activeKeyId() : "none")
                        + "; sealed evidence sample " + environment.getProperty(
                        "contexa.pqa.oss.sealed-evidence.sample-percent") + "%");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("serverTier", tier.name());
        report.put("ownership", environment.getProperty("contexa.bridge.ownership", "HOST_OWNED"));
        report.put("areas", areas);
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("layer1", environment.getProperty("contexa.security.tiered.llm.layer1.model"));
        models.put("layer2", environment.getProperty("contexa.security.tiered.llm.layer2.model"));
        models.put("promptBundle", environment.getProperty("contexa.security.tiered.prompt-bundle.active"));
        models.put("chatProvider", environment.getProperty("spring.ai.model.chat"));
        models.put("embeddingProvider", environment.getProperty("spring.ai.model.embedding"));
        // What the engine resolves each layer's model to (the model id of the chat model that will answer), not only what
        // is configured: a layer model with no runtime binding runs on a copy of the primary chat model, and the engine
        // can copy only some providers' models.
        models.put("layer1Resolved", resolved(environment.getProperty("contexa.security.tiered.llm.layer1.model")));
        models.put("layer2Resolved", resolved(environment.getProperty("contexa.security.tiered.llm.layer2.model")));
        ChatModel primary = primaryChatModel.getIfAvailable();
        models.put("primaryChatModel", primary == null ? null : modelIdOf(primary));
        LlmRuntimeCatalog catalog = runtimeCatalog.getIfAvailable();
        if (catalog != null) {
            List<Map<String, Object>> bindings = new ArrayList<>();
            for (LlmRuntimeBinding binding : catalog.getChatBindings()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bean", binding.getBeanName());
                row.put("provider", binding.getProvider());
                row.put("modelId", binding.getModelId());
                row.put("primary", binding.isPrimary());
                bindings.add(row);
            }
            models.put("chatBindings", bindings);
        }
        report.put("models", models);
        Map<String, Object> toolTable = new LinkedHashMap<>();
        toolTable.put("version", tools.version());
        toolTable.put("sha256", tools.sha256());
        toolTable.put("listedTools", tools.listedTools());
        report.put("toolTable", toolTable);
        CapabilityDiagnosticsEndpoint endpoint = engineReport.getIfAvailable();
        report.put("engine", endpoint != null ? endpoint.diagnostics() : "the engine's capability report is not available");
        return report;
    }

    private String resolved(String configured) {
        DynamicModelRegistry registry = modelRegistry.getIfAvailable();
        if (configured == null || registry == null) {
            return null;
        }
        try {
            return modelIdOf(registry.getModel(configured));
        } catch (RuntimeException unavailable) {
            return "unavailable: " + unavailable.getMessage();
        }
    }

    private static String modelIdOf(ChatModel model) {
        return model.getDefaultOptions() != null ? model.getDefaultOptions().getModel() : model.getClass().getSimpleName();
    }

    private static void area(List<Map<String, Object>> areas, String tier, String name, boolean on, String evidence) {
        Map<String, Object> area = new LinkedHashMap<>();
        area.put("tier", tier);
        area.put("area", name);
        area.put("on", on);
        area.put("evidence", evidence);
        areas.add(area);
    }

    private static String type(Object bean) {
        return bean == null ? "absent" : bean.getClass().getSimpleName();
    }
}

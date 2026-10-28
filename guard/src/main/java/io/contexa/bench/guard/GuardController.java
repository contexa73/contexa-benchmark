package io.contexa.bench.guard;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.contexa.contexacore.autonomous.utils.RequestInfoExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The guard's HTTP contract (see README.md): {@code /guard/begin}, {@code /guard/decide}, {@code /guard/end},
 * {@code /guard/health}, {@code /guard/capabilities} and {@code /guard/decision/{id}} for the evidence of one decision.
 * <p>
 * In stage C1 (and for a run of C1 on a higher server) {@code /guard/decide} is decided here; in C2 and C3 the call is
 * presented to the engine's chain by {@link GuardCallFilter} and decided at the tool's endpoint
 * ({@link GuardCallController}).
 */
@RestController
@RequestMapping("/guard")
public class GuardController {

    private static final Logger log = LoggerFactory.getLogger(GuardController.class);

    /**
     * What {@code /guard/begin} takes.
     *
     * @param tier    the stage the run asks for (never above the server's), or none for the server's
     * @param records the records of the run's environment before any attack (kinds sent are looked up; kinds left out are not)
     * @param scope   the suite-level policy the task is delegated under
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BeginRequest(String runId, String goal, String suite, String tier, BeginRecords records,
                               BeginScope scope) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BeginRecords(
            @JsonProperty("known_counterparties") @JsonAlias("knownCounterparties") List<String> knownCounterparties,
            @JsonProperty("known_contacts") @JsonAlias("knownContacts") List<String> knownContacts,
            @JsonProperty("known_files") @JsonAlias("knownFiles") List<String> knownFiles,
            @JsonProperty("known_channels") @JsonAlias("knownChannels") List<String> knownChannels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BeginScope(
            String family,
            @JsonAlias("allowed_operations") List<String> allowedOperations,
            @JsonAlias("allowed_resources") List<String> allowedResources) {
    }

    public record DecideRequest(String runId, String tool, Map<String, Object> arguments) {
    }

    public record EndRequest(String runId) {
    }

    private final GuardRuns runs;
    private final RunLifecycle lifecycle;
    private final ToolGateway gateway;
    private final DecisionTraces traces;
    private final EngineDecisionRecords records;
    private final GuardCapabilities capabilities;

    public GuardController(GuardRuns runs, RunLifecycle lifecycle, ToolGateway gateway, DecisionTraces traces,
                           EngineDecisionRecords records, GuardCapabilities capabilities) {
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.traces = traces;
        this.records = records;
        this.capabilities = capabilities;
    }

    @PostMapping("/begin")
    public ResponseEntity<Map<String, Object>> begin(@RequestBody BeginRequest request) {
        if (request == null || request.runId() == null || request.runId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId is required"));
        }
        GuardTier asked;
        try {
            asked = GuardTier.parse(request.tier(), null);
        } catch (IllegalArgumentException unknown) {
            return ResponseEntity.badRequest().body(Map.of("error", "tier must be C1, C2 or C3"));
        }
        GuardRuns.Records recordsOfRun = request.records() == null ? GuardRuns.Records.NONE
                : new GuardRuns.Records(request.records().knownCounterparties(), request.records().knownContacts(),
                request.records().knownFiles(), request.records().knownChannels());
        GuardRuns.Scope scope = request.scope() == null ? GuardRuns.Scope.NONE
                : new GuardRuns.Scope(request.scope().family(), request.scope().allowedOperations(),
                request.scope().allowedResources());
        GuardRuns.Run run = runs.begin(new GuardRuns.BeginSpec(request.runId(), request.goal(), request.suite(), asked,
                recordsOfRun, scope));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("principal", run.principal());
        answer.put("agentId", run.agentId());
        try {
            answer.putAll(lifecycle.begin(run));
        } catch (RuntimeException failure) {
            // A run that could not be set up must not be decided as if it had been: say so, and drop it.
            log.error("[guard] run {} could not begin", run.runId(), failure);
            runs.end(run.runId());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "the run could not begin: " + failure.getMessage()));
        }
        answer.put("tier", run.tier().name());
        answer.put("records", recordsOfRun.count());
        log.info("[guard] run {} began: subject={}, agent={}, suite={}, tier={}, delegation={}", run.runId(), run.principal(),
                run.agentId(), run.suite(), run.tier(), run.delegationId());
        return ResponseEntity.ok(answer);
    }

    /**
     * Stage C1: the run's agent subject and delegation were put into the SecurityContext and the request attributes by
     * {@link GuardRunAuthenticationFilter} (or {@link GuardCallFilter} for a run of C1 on a higher server); the gateway is
     * decided by the engine as any {@code @Protectable} call.
     */
    @PostMapping("/decide")
    public ResponseEntity<Map<String, Object>> decide(@RequestBody DecideRequest request, HttpServletRequest http) {
        if (request == null || request.tool() == null || request.tool().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tool is required"));
        }
        GuardRuns.Run run = (GuardRuns.Run) http.getAttribute(HostAssertion.RUN_ATTRIBUTE);
        if (run == null) {
            throw new IllegalStateException("This call was not presented to the engine as the run's call");
        }
        String decisionId = RequestInfoExtractor.extractRequestId(http);
        Map<String, Object> arguments = request.arguments() != null ? request.arguments() : Map.of();

        long started = System.nanoTime();
        VerdictMapper.Outcome outcome;
        try {
            gateway.call(request.tool().trim(), arguments);
            outcome = VerdictMapper.allowed();
        } catch (AuthorizationDeniedException denied) {
            outcome = VerdictMapper.of(denied);
        }
        long decisionMs = (System.nanoTime() - started) / 1_000_000L;
        long call = run.calls().incrementAndGet();

        Map<String, Object> answer = answer(outcome, traces.of(decisionId), decisionId, decisionMs, run.tier());
        log.info("[guard] run {} call {} tool={} -> {} (engine {}, layer {}, model {}, {} ms, decision {})", run.runId(), call,
                request.tool(), answer.get("action"), outcome.engineAction(), answer.get("layer"), answer.get("model"),
                decisionMs, decisionId);
        return ResponseEntity.ok(answer);
    }

    @PostMapping("/end")
    public ResponseEntity<Map<String, Object>> end(@RequestBody EndRequest request) {
        if (request == null || request.runId() == null || request.runId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "runId is required"));
        }
        GuardRuns.Run run = runs.end(request.runId());
        if (run != null) {
            try {
                lifecycle.end(run);
            } catch (RuntimeException failure) {
                log.error("[guard] run {} could not be closed with the engine", run.runId(), failure);
            }
            log.info("[guard] run {} ended after {} calls: subject={}", run.runId(), run.calls().get(), run.principal());
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        if (run != null) {
            answer.put("calls", run.calls().get());
        }
        return ResponseEntity.ok(answer);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("status", "UP");
        answer.put("activeRuns", runs.active());
        return answer;
    }

    /** Which of Contexa's feature areas are on, the stage of the server, and the engine's own capability report. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return capabilities.report();
    }

    /** The evidence of one decision: what the engine announced and the engine's own database record. */
    @GetMapping("/decision/{decisionId}")
    public Map<String, Object> decision(@PathVariable("decisionId") String decisionId) {
        DecisionTraces.Trace trace = traces.of(decisionId);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("decisionId", decisionId);
        answer.put("stages", trace.stages());
        answer.put("modelCalls", trace.calls());
        answer.put("observations", records.observations(decisionId));
        return answer;
    }

    /** The guard's answer, with what the engine did for this call (the layer and the model that decided, tokens). */
    static Map<String, Object> answer(VerdictMapper.Outcome outcome, DecisionTraces.Trace trace, String decisionId,
                                      long decisionMs) {
        return VerdictAnswers.answer(outcome, trace, decisionId, decisionMs, null, null);
    }

    static Map<String, Object> answer(VerdictMapper.Outcome outcome, DecisionTraces.Trace trace, String decisionId,
                                      long decisionMs, GuardTier tier) {
        return VerdictAnswers.answer(outcome, trace, decisionId, decisionMs, tier, null);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException invalid) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(invalid.getMessage())));
    }
}

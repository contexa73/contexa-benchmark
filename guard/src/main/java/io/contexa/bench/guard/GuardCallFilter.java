package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The agent's call as the engine's chain receives it (stages C2 and C3). The runner posts {@code /guard/decide} with the
 * run, the tool and its arguments; this filter, before Spring Security, presents it to the chain as the agent's own request
 * to the tool's endpoint: the tool's method and path ({@code GET /guard/tools/get_balance}), the agent's delegated
 * access token as the bearer token, and the original body (so a confirmation binds to the call). The chain then
 * authenticates the token, reads the delegation it states, applies the zero trust filters and the agent gate (call limit,
 * revoked delegation, scope narrowing, confirmation), and reaches the gateway.
 * <p>
 * It answers the runner itself. What the gateway decided is read from the outcome the controller set; when no gateway decided
 * (a filter of the chain refused the call from the decision in force) the chain's refusal is read as the verdict
 * ({@link VerdictTranslator}). A run that asked for a lower stage than the server's is presented as in C1 (host-asserted
 * subject, delegation stated on the request).
 */
public class GuardCallFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GuardCallFilter.class);

    static final String DECIDE_PATH = "/guard/decide";
    static final String OUTCOME = "bench.guard.outcome";
    static final String DECISION_MS = "bench.guard.decisionMs";
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private final GuardRuns runs;
    private final ToolTable tools;
    private final CallContexts calls;
    private final DecisionTraces traces;
    private final ObjectMapper json;

    public GuardCallFilter(GuardRuns runs, ToolTable tools, CallContexts calls, DecisionTraces traces, ObjectMapper json) {
        this.runs = runs;
        this.tools = tools;
        this.calls = calls;
        this.traces = traces;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !DECIDE_PATH.equals(request.getRequestURI());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "the request body is too large");
            return;
        }
        JsonNode root;
        try {
            root = json.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "the request body is not JSON");
            return;
        }
        String runId = text(root, "runId");
        String tool = text(root, "tool");
        GuardRuns.Run run = runId == null ? null : runs.find(runId);
        if (run == null) {
            reject(response, HttpServletResponse.SC_NOT_FOUND, "unknown runId (call /guard/begin first)");
            return;
        }
        if (tool == null) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "tool is required");
            return;
        }
        Map<String, Object> arguments = root.has("arguments") && root.get("arguments").isObject()
                ? json.convertValue(root.get("arguments"), Map.class) : Map.of();

        if (!run.tier().atLeast(GuardTier.C2)) {
            // A run of a lower stage than the server's: presented as in C1 and decided by the controller of C1.
            HostAssertion.apply(request, run);
            try {
                chain.doFilter(new ReplayedRequest(request, body), response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }

        String decisionId = HostAssertion.decisionId(request);
        ToolTable.ToolSpec spec = tools.of(tool);
        CallContexts.Call call = calls.put(decisionId, run, spec, arguments);
        run.calls().incrementAndGet();
        String path = "/guard/tools/" + segment(tool);
        String token = run.accessToken();
        long started = System.nanoTime();
        VerdictMapper.Outcome outcome;
        long decisionMs;
        if (token == null) {
            outcome = new VerdictMapper.Outcome("BLOCK", "NO_DELEGATED_TOKEN", true,
                    "the run holds no delegated access token (its begin did not complete)");
            decisionMs = 0;
        } else {
            // The body a confirmation binds to is the call itself (run, tool, arguments), in one fixed form, so that the
            // call sent again with the confirmation's identifier is the same request the person approved.
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("runId", run.runId());
            canonical.put("tool", tool);
            canonical.put("arguments", arguments);
            ReplayedRequest presented = new ReplayedRequest(request, json.writeValueAsBytes(canonical),
                    spec.toolClass().httpMethod(), path, "Bearer " + token);
            String confirmationId = text(root, "confirmationId");
            if (confirmationId != null) {
                // The agent sends the same call again naming the confirmation its accountable person approved.
                presented.withHeader("Contexa-Agent-Confirmation", confirmationId);
            }
            BufferedResponse buffered = new BufferedResponse(response);
            Exception failure = null;
            try {
                chain.doFilter(presented, buffered);
            } catch (ServletException | RuntimeException e) {
                failure = e;
            }
            long totalMs = (System.nanoTime() - started) / 1_000_000L;
            Object decided = request.getAttribute(OUTCOME);
            if (decided instanceof VerdictMapper.Outcome gatewayOutcome) {
                outcome = gatewayOutcome;
                if ("CHALLENGE".equals(outcome.action()) && outcome.confirmationId() == null) {
                    // A CHALLENGE decided in this very request: the engine's handlers answered it with the confirmation
                    // the accountable person is asked to decide; its identifier is in the answer they wrote.
                    String asked = VerdictTranslator.translate(buffered.status(), buffered.body(), json)
                            .confirmationId();
                    if (asked != null) {
                        outcome = new VerdictMapper.Outcome(outcome.action(), outcome.engineAction(),
                                outcome.unresolved(), outcome.refusal(), asked);
                    } else {
                        log.info("[guard] run {} tool {}: CHALLENGE without a confirmation in the chain's answer (status {}, body {})",
                                run.runId(), tool, buffered.status(),
                                new String(buffered.body() == null ? new byte[0] : buffered.body(), StandardCharsets.UTF_8));
                    }
                }
            } else if (failure != null) {
                log.error("[guard] the chain failed for run {} tool {}", run.runId(), tool, failure);
                outcome = new VerdictMapper.Outcome("BLOCK", "GUARD_ERROR", true,
                        failure.getClass().getSimpleName() + ": " + failure.getMessage());
            } else {
                outcome = VerdictTranslator.translate(buffered.status(), buffered.body(), json);
            }
            Object gatewayMs = request.getAttribute(DECISION_MS);
            decisionMs = gatewayMs instanceof Long ms ? ms : totalMs;
        }

        Map<String, Object> answer = VerdictAnswers.answer(outcome, traces.of(decisionId), decisionId, decisionMs,
                run.tier(), facts(run, call));
        log.info("[guard] run {} call {} tool={} class={} -> {} (engine {}, layer {}, model {}, {} ms, decision {})",
                run.runId(), run.calls().get(), tool, spec.toolClass(), answer.get("action"), outcome.engineAction(),
                answer.get("layer"), answer.get("model"), decisionMs, decisionId);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getOutputStream().write(json.writeValueAsBytes(answer));
    }

    /** What the call was judged with, as the guard knows it. */
    private Map<String, Object> facts(GuardRuns.Run run, CallContexts.Call call) {
        Map<String, Object> facts = new LinkedHashMap<>();
        Map<String, Object> delegation = new LinkedHashMap<>();
        delegation.put("agentId", run.agentId());
        delegation.put("delegationId", run.delegationId());
        delegation.put("token", run.accessToken() != null ? "act chain (RFC 8693 token exchange)" : "none");
        delegation.put("objective", run.goal().isBlank() ? "not stated" : "the task, recorded in the delegation");
        if (!run.scope().empty()) {
            delegation.put("scope", Map.of("family", String.valueOf(run.scope().family()),
                    "allowedOperations", run.scope().allowedOperations(),
                    "allowedResources", run.scope().allowedResources()));
        }
        facts.put("delegation", delegation);
        Map<String, Object> importance = new LinkedHashMap<>();
        importance.put("class", call.spec().toolClass().name());
        importance.put("basis", call.spec().basis());
        importance.put("declared", call.spec().toolClass().declaration());
        facts.put("importance", importance);
        if (!call.run().records().empty()) {
            facts.put("records", BenchRecordsContextProvider.describe(call.lookup(), call.run().records()));
        }
        return facts;
    }

    private static String segment(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-' || c == '.' ? c : '_');
        }
        return out.length() == 0 ? "_" : out.toString();
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText().trim();
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("error", message)));
    }
}

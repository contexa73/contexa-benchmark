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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The host's part of the integration: it authenticates a decision request as the run's acting subject and states the
 * run's delegation to the Contexa bridge. This is the documented host-owned path (the legacy-system example does the
 * same for its session user): the Spring SecurityContext names who acts, and the request attributes
 * {@code ctxa.delegation.*} (BridgeProperties.RequestAttributes) state that the subject is an agent acting under a
 * delegation, with the task as its objective. The bridge's request-attribute resolver turns them into the delegation
 * stamp the engine reads.
 * <p>
 * It also sets the request's decision ID ({@code contexa.requestId}), the ID under which the
 * engine's observation row, sealed evidence and model calls are recorded, so the answer can name it.
 * <p>
 * The body of {@code POST /guard/decide} holds the run ID, so the body is read here and handed on unchanged.
 */
public class GuardRunAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GuardRunAuthenticationFilter.class);

    static final String DECIDE_PATH = "/guard/decide";
    static final String RUN_ATTRIBUTE = HostAssertion.RUN_ATTRIBUTE;
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;


    private final GuardRuns runs;
    private final ObjectMapper json;

    public GuardRunAuthenticationFilter(GuardRuns runs, ObjectMapper json) {
        this.runs = runs;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !DECIDE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "the request body is too large");
            return;
        }
        String runId = runIdOf(body, request);
        GuardRuns.Run run = runId == null ? null : runs.find(runId);
        if (run == null) {
            reject(response, HttpServletResponse.SC_NOT_FOUND, "unknown runId (call /guard/begin first)");
            return;
        }

        HostAssertion.apply(request, run);

        chain.doFilter(new ReplayedRequest(request, body), response);
    }

    private String runIdOf(byte[] body, HttpServletRequest request) {
        try {
            JsonNode root = json.readTree(new String(body, charset(request)));
            JsonNode runId = root == null ? null : root.get("runId");
            return runId == null || runId.isNull() || runId.asText().isBlank() ? null : runId.asText();
        } catch (IOException | RuntimeException unreadable) {
            log.warn("[guard] the decision request body could not be read: {}", unreadable.toString());
            return null;
        }
    }

    private static Charset charset(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        try {
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("error", message)));
    }
}

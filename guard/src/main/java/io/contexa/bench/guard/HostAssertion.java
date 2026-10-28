package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.utils.RequestInfoExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a host that owns authentication states about a run's call (stage C1): the run's subject is authenticated by the
 * host (put into the Spring SecurityContext), and the delegation the subject acts under is stated as the request
 * attributes {@code ctxa.delegation.*} (BridgeProperties.RequestAttributes), which the bridge's request-attribute
 * resolver turns into the delegation stamp the engine reads.
 */
final class HostAssertion {

    static final String RUN_ATTRIBUTE = "bench.guard.run";

    private static final String DELEGATED = "ctxa.delegation.enabled";
    private static final String AGENT_ID = "ctxa.delegation.agentId";
    private static final String OBJECTIVE_SUMMARY = "ctxa.delegation.objectiveSummary";

    private HostAssertion() {
    }

    /** Authenticates the run's subject, states its delegation, and gives the call its decision ID. */
    static String apply(HttpServletRequest request, GuardRuns.Run run) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("displayName", "Benchmark agent " + run.agentId() + " (run " + run.runId() + ")");
        details.put("authenticationType", "HOST_ASSERTED");
        details.put("mfaVerified", false);
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                run.principal(), null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        authentication.setDetails(details);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        request.setAttribute(DELEGATED, Boolean.TRUE);
        request.setAttribute(AGENT_ID, run.agentId());
        if (!run.goal().isBlank()) {
            request.setAttribute(OBJECTIVE_SUMMARY, run.goal());
        }
        request.setAttribute(RUN_ATTRIBUTE, run);
        return decisionId(request);
    }

    /** The decision ID of this call, known before the engine records anything under it. */
    static String decisionId(HttpServletRequest request) {
        String decisionId = UUID.randomUUID().toString();
        request.setAttribute(RequestInfoExtractor.REQUEST_ID_ATTRIBUTE, decisionId);
        return decisionId;
    }
}

package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contexa.contexacore.autonomous.utils.RequestInfoExtractor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GuardRunAuthenticationFilterTest {

    @SuppressWarnings("unchecked")
    private final GuardRuns runs = new GuardRuns(
            new GuardProperties("bench-agent", "bench-user-", Duration.ofSeconds(-1), 100, null, null), mock(ObjectProvider.class));
    private final GuardRunAuthenticationFilter filter = new GuardRunAuthenticationFilter(runs, new ObjectMapper());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest decide(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/guard/decide");
        request.setCharacterEncoding("UTF-8");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void aKnownRunActsAsItsSubjectUnderItsDelegationAndTheBodyIsReplayed() throws Exception {
        GuardRuns.Run run = runs.begin("run-ab12", "Check my balance.", "banking");
        String body = "{\"runId\":\"run-ab12\",\"tool\":\"get_balance\",\"arguments\":{}}";
        MockHttpServletRequest request = decide(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<String> replayed = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            replayed.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        };

        filter.doFilter(request, response, chain);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getName()).isEqualTo(run.principal());
        assertThat(seen.get().isAuthenticated()).isTrue();
        assertThat(replayed.get()).isEqualTo(body);
        assertThat(request.getAttribute("ctxa.delegation.enabled")).isEqualTo(Boolean.TRUE);
        assertThat(request.getAttribute("ctxa.delegation.agentId")).isEqualTo("bench-agent");
        assertThat(request.getAttribute("ctxa.delegation.objectiveSummary")).isEqualTo("Check my balance.");
        assertThat(request.getAttribute(RequestInfoExtractor.REQUEST_ID_ATTRIBUTE)).isInstanceOf(String.class);
        assertThat(request.getAttribute(GuardRunAuthenticationFilter.RUN_ATTRIBUTE)).isSameAs(run);
    }

    @Test
    void anUnknownRunIsRefusedBeforeAnythingIsDecided() throws Exception {
        MockHttpServletRequest request = decide("{\"runId\":\"nobody\",\"tool\":\"get_balance\",\"arguments\":{}}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter.doFilter(request, response, (req, res) -> reached[0] = true);

        assertThat(reached[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void onlyTheDecisionRequestIsFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/guard/begin");
        boolean[] reached = {false};
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached[0] = true);
        assertThat(reached[0]).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void theDecisionIdOfEachCallIsItsOwn() throws Exception {
        runs.begin("run-x", "task", "banking");
        MockHttpServletRequest first = decide("{\"runId\":\"run-x\",\"tool\":\"a\",\"arguments\":{}}");
        MockHttpServletRequest second = decide("{\"runId\":\"run-x\",\"tool\":\"a\",\"arguments\":{}}");
        filter.doFilter(first, new MockHttpServletResponse(), (req, res) -> { });
        filter.doFilter(second, new MockHttpServletResponse(), (req, res) -> { });
        assertThat(first.getAttribute(RequestInfoExtractor.REQUEST_ID_ATTRIBUTE))
                .isNotEqualTo(second.getAttribute(RequestInfoExtractor.REQUEST_ID_ATTRIBUTE));
    }
}

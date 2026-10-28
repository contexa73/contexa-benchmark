package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictTranslatorTest {

    private final ObjectMapper json = new ObjectMapper();

    private VerdictMapper.Outcome translate(int status, String body) {
        return VerdictTranslator.translate(status, body == null ? null : body.getBytes(StandardCharsets.UTF_8), json);
    }

    @Test
    void theAgentGatesRefusalsAreVerdictsOfTheDecisionInForce() {
        VerdictMapper.Outcome confirmation = translate(403,
                "{\"error\":\"AGENT_CONFIRMATION_REQUIRED\",\"message\":\"must confirm\",\"confirmationId\":\"c-1\"}");
        assertThat(confirmation.action()).isEqualTo("CHALLENGE");
        assertThat(confirmation.engineAction()).isEqualTo("AGENT_CONFIRMATION_REQUIRED");
        assertThat(confirmation.unresolved()).isFalse();
        assertThat(confirmation.refusal()).contains("c-1");

        assertThat(translate(403, "{\"error\":\"DELEGATION_REVOKED\"}").action()).isEqualTo("BLOCK");
        assertThat(translate(403, "{\"error\":\"AGENT_SCOPE_NARROWED\"}").engineAction()).isEqualTo("AGENT_SCOPE_NARROWED");
        VerdictMapper.Outcome limit = translate(429, "{\"error\":\"AGENT_CALL_LIMIT\"}");
        assertThat(limit.action()).isEqualTo("BLOCK");
        assertThat(limit.unresolved()).isFalse();
    }

    @Test
    void theZeroTrustFiltersAnswersAreRead() {
        assertThat(translate(403, "{\"code\":\"ZERO_TRUST_BLOCK\",\"action\":\"BLOCK\"}").action()).isEqualTo("BLOCK");
        assertThat(translate(401, "{\"error\":\"MFA_CHALLENGE_REQUIRED\"}").action()).isEqualTo("CHALLENGE");
        assertThat(translate(423, "{\"action\":\"ESCALATE\"}").action()).isEqualTo("ESCALATE");
    }

    @Test
    void anythingElseIsNoVerdictAndFailsClosed() {
        VerdictMapper.Outcome expired = translate(401, "{\"error\":\"invalid_token\"}");
        assertThat(expired.action()).isEqualTo("BLOCK");
        assertThat(expired.unresolved()).isTrue();
        assertThat(expired.engineAction()).startsWith("HTTP_401");
        assertThat(translate(500, null).unresolved()).isTrue();
        assertThat(translate(500, "not json").unresolved()).isTrue();
    }
}

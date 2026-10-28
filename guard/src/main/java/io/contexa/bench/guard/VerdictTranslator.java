package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Reads the engine's refusal of a request that never reached the gateway (a filter of the chain answered it) as a verdict.
 * The filters answer with a status and a JSON body: the zero trust filters with {@code action} / {@code code}, the agent
 * gate with {@code error} (AGENT_CONFIRMATION_REQUIRED, DELEGATION_REVOKED, AGENT_SCOPE_NARROWED, AGENT_CALL_LIMIT). These
 * are answers from the decision in force, not new model calls.
 * <p>
 * A refusal that is none of these (an expired token, a failure) is not a verdict: the guard fails closed and marks it
 * {@code unresolved}.
 */
public final class VerdictTranslator {

    private VerdictTranslator() {
    }

    public static VerdictMapper.Outcome translate(int status, byte[] body, ObjectMapper json) {
        JsonNode root = null;
        if (body != null && body.length > 0) {
            try {
                root = json.readTree(new String(body, StandardCharsets.UTF_8));
            } catch (java.io.IOException | RuntimeException notJson) {
                root = null;
            }
        }
        String error = text(root, "error");
        String code = text(root, "code");
        String action = text(root, "action");
        String message = firstNonBlank(text(root, "message"), text(root, "reason"), error, code);
        String named = firstNonBlank(error, code);

        if (named != null) {
            switch (named) {
                case "AGENT_CONFIRMATION_REQUIRED":
                    return new VerdictMapper.Outcome("CHALLENGE", named, false, withDetail(message, root, "confirmationId"),
                            text(root, "confirmationId"));
                case "DELEGATION_REVOKED":
                    return new VerdictMapper.Outcome("BLOCK", named, false, message);
                case "AGENT_SCOPE_NARROWED":
                    return new VerdictMapper.Outcome("BLOCK", named, false, message);
                case "AGENT_CALL_LIMIT":
                    return new VerdictMapper.Outcome("BLOCK", named, false, message);
                case "ZERO_TRUST_BLOCK":
                    return new VerdictMapper.Outcome("BLOCK", "BLOCK", false, message);
                case "ZERO_TRUST_CHALLENGE", "MFA_CHALLENGE_REQUIRED":
                    return new VerdictMapper.Outcome("CHALLENGE", "CHALLENGE", false, message);
                case "ZERO_TRUST_ESCALATE":
                    return new VerdictMapper.Outcome("ESCALATE", "ESCALATE", false, message);
                default:
                    break;
            }
        }
        if (action != null) {
            switch (action) {
                case "BLOCK", "CHALLENGE", "ESCALATE":
                    return new VerdictMapper.Outcome(action, action, false, message);
                default:
                    break;
            }
        }
        return new VerdictMapper.Outcome("BLOCK", "HTTP_" + status + (named != null ? ":" + named : ""), true,
                message != null ? message : "the chain answered " + status);
    }

    private static String withDetail(String message, JsonNode root, String field) {
        String detail = text(root, field);
        return detail == null ? message : (message == null ? "" : message + " ") + "(" + field + " " + detail + ")";
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode node = root.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

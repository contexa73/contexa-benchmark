package io.contexa.bench.guard;

import io.contexa.contexacommon.annotation.Protectable;
import io.contexa.contexacommon.annotation.ResourceImportance;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The gateways of a tool call. They do no work: the benchmark runs the tool itself, after the guard answers. What the
 * Contexa engine decides is whether the call may run, from the arguments of the method (the tool's name and the arguments
 * the agent passed; the engine reads them as untrusted text of the request), the delegation the call runs under and the
 * history of the acting subject.
 * <p>
 * The methods are {@code sync}: the decision is made before the call returns. Returning normally means ALLOW; the engine
 * refuses with a {@code ZeroTrustAccessDeniedException} (BLOCK, CHALLENGE, ESCALATE, or no verdict) otherwise. The
 * parameter names are what the engine's summary of the call shows ({@code tool=...; arguments={...}}).
 * <p>
 * {@link #call} declares nothing about the tool (stage C1). From C2 on the tool's class (contexa-benchmark/declarations, frozen before
 * any result) selects one of the other methods, each of which declares what the application knows of its tools: the action
 * family, whether the resource is sensitive, whether it is privileged or exports data. The names of these methods are the
 * resources of the engine's memory.
 */
@Component
public class ToolGateway {

    /** C1: a tool call, with nothing declared about the tool. */
    @Protectable(sync = true)
    public void call(String tool, Map<String, Object> arguments) {
        // Nothing to do: the decision is the point.
    }

    /**
     * A read of reference data the host declares non-sensitive: it passes while the first request of its kind is decided
     * (not sync), as the engine allows for explicitly non-sensitive reads.
     */
    @Protectable(actions = "READ", importance = ResourceImportance.NON_SENSITIVE)
    public void readPublic(String tool, Map<String, Object> arguments) {
    }

    @Protectable(sync = true, actions = "READ")
    public void read(String tool, Map<String, Object> arguments) {
    }

    @Protectable(sync = true, actions = "WRITE")
    public void write(String tool, Map<String, Object> arguments) {
    }

    @Protectable(sync = true, category = "PAYMENTS", actions = "WRITE", importance = ResourceImportance.SENSITIVE)
    public void money(String tool, Map<String, Object> arguments) {
    }

    @Protectable(sync = true, actions = "DELETE", importance = ResourceImportance.SENSITIVE)
    public void delete(String tool, Map<String, Object> arguments) {
    }

    @Protectable(sync = true, actions = "EXPORT", exportSensitive = true, importance = ResourceImportance.SENSITIVE)
    public void external(String tool, Map<String, Object> arguments) {
    }

    @Protectable(sync = true, category = "ACCESS", actions = "ADMIN", privileged = true,
            importance = ResourceImportance.SENSITIVE)
    public void credential(String tool, Map<String, Object> arguments) {
    }
}

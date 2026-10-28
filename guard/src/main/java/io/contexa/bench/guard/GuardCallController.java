package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.utils.RequestInfoExtractor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tool endpoints the agent's calls reach in stages C2 and C3 ({@link GuardCallFilter} presents each call as a request
 * to the tool's own endpoint). The gateway of the tool's class is decided by the engine here; what it decided is kept on the
 * request for the filter to answer with. A refusal is thrown on, so the engine's own handlers answer it as they would for
 * any application (a CHALLENGE asks the accountable person to confirm the request, a BLOCK stops the agent).
 */
@RestController
@ConditionalOnExpression("'${bench.guard.tier:C1}'.toUpperCase() != 'C1'")
public class GuardCallController {

    private final CallContexts calls;
    private final ToolGateway gateway;

    public GuardCallController(CallContexts calls, ToolGateway gateway) {
        this.calls = calls;
        this.gateway = gateway;
    }

    @RequestMapping(path = "/guard/tools/{tool}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE})
    public ResponseEntity<Void> call(HttpServletRequest request) {
        CallContexts.Call call = calls.of(RequestInfoExtractor.extractRequestId(request));
        if (call == null) {
            throw new IllegalStateException("The call of this request is unknown");
        }
        long started = System.nanoTime();
        try {
            invoke(call);
        } catch (AuthorizationDeniedException denied) {
            request.setAttribute(GuardCallFilter.DECISION_MS, (System.nanoTime() - started) / 1_000_000L);
            request.setAttribute(GuardCallFilter.OUTCOME, VerdictMapper.of(denied));
            throw denied;
        }
        request.setAttribute(GuardCallFilter.DECISION_MS, (System.nanoTime() - started) / 1_000_000L);
        request.setAttribute(GuardCallFilter.OUTCOME, VerdictMapper.allowed());
        return ResponseEntity.noContent().build();
    }

    /** The gateway method of the tool's class (the proxy is called, so the engine's interceptor decides it). */
    private void invoke(CallContexts.Call call) {
        String tool = call.spec().tool();
        switch (call.spec().toolClass()) {
            case READ_PUBLIC -> gateway.readPublic(tool, call.arguments());
            case READ -> gateway.read(tool, call.arguments());
            case WRITE -> gateway.write(tool, call.arguments());
            case MONEY -> gateway.money(tool, call.arguments());
            case DELETE -> gateway.delete(tool, call.arguments());
            case EXTERNAL -> gateway.external(tool, call.arguments());
            case CREDENTIAL -> gateway.credential(tool, call.arguments());
        }
    }
}

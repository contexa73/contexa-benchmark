package io.contexa.bench.guard;

import io.contexa.contexacore.std.advisor.core.BaseAdvisor;
import io.contexa.contexacore.std.llm.client.ProviderUsage;
import io.contexa.contexacore.std.llm.observation.LlmObservationContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

import java.time.Clock;

/**
 * Measures every chat call of the engine (model, tokens, time) and attributes it to the decision ID the engine passes in
 * the advisor context. It only measures: it never refuses or changes a call. Ordered after the engine's structured
 * output validation, so every retry is counted.
 */
public class ModelCallMeter extends BaseAdvisor {

    private final DecisionTraces traces;
    private final Clock clock;

    public ModelCallMeter(DecisionTraces traces, Clock clock) {
        super("bench", "model-call-meter", Integer.MAX_VALUE - 100);
        this.traces = traces;
        this.clock = clock;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long started = System.nanoTime();
        ChatClientResponse response = null;
        try {
            response = chain.nextCall(request);
            return response;
        } finally {
            record(request, response, (System.nanoTime() - started) / 1_000_000L);
        }
    }

    @Override
    protected ChatClientRequest beforeCall(ChatClientRequest request) {
        return request;
    }

    @Override
    protected ChatClientResponse afterCall(ChatClientResponse response, ChatClientRequest request) {
        return response;
    }

    private void record(ChatClientRequest request, ChatClientResponse response, long elapsedMs) {
        Object observation = request.context().get(LlmObservationContext.CONTEXT_KEY);
        if (!(observation instanceof LlmObservationContext context)) {
            return;
        }
        String model = null;
        long prompt = 0;
        long completion = 0;
        long total = 0;
        Long cached = null;
        boolean success = response != null && response.chatResponse() != null;
        if (success) {
            ChatResponseMetadata metadata = response.chatResponse().getMetadata();
            model = metadata.getModel();
            Usage usage = metadata.getUsage();
            if (usage != null) {
                Integer counted = ProviderUsage.promptTokens(usage);
                prompt = counted == null ? 0 : counted;
                completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                Integer sum = ProviderUsage.totalTokens(usage);
                total = sum == null ? 0 : sum;
                Integer cachedTokens = ProviderUsage.cachedPromptTokens(usage);
                cached = cachedTokens == null ? null : cachedTokens.longValue();
            }
        }
        traces.call(context.requestId(), new DecisionTraces.ModelCall(model, prompt, completion, total, cached, elapsedMs,
                success, clock.instant()));
    }
}

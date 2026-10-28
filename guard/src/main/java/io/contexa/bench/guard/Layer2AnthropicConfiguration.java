package io.contexa.bench.guard;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * Layer 2 on Anthropic's Claude Sonnet 5.5 while Layer 1 stays on OpenAI (or, with the OpenAI balance used up, Layer 1 on
 * Claude Haiku 5.5 as well), built the way the showcase's control D builds it:
 * the engine already runs the layers on different providers (a model ID with a chat runtime binding,
 * {@code contexa.llm.bindings.chat}, is resolved to that binding's chat model), so this only provides the Anthropic chat
 * model the binding in application.yml names. It is built the way Spring AI's own Anthropic auto-configuration builds
 * it (which is off while the primary chat provider is OpenAI).
 * <p>
 * Layer 2 is only called when the engine escalates a request Layer 1 could not decide; nothing here calls it.
 */
@Configuration(proxyBeanMethods = false)
public class Layer2AnthropicConfiguration {

    /** The bean the chat binding of the Layer 2 model names in application.yml. */
    public static final String BEAN = "layer2AnthropicChatModel";

    /** Anthropic requires a limit on the answer; the engine sets the layer's own limit on each call. */
    private static final int DEFAULT_MAX_TOKENS = 2560;

    /** The key placeholder that lets the context load when no key is set; the start check then refuses with the reason. */
    private static final String NO_KEY = "no-anthropic-key";

    /** The bean the chat binding of the Layer 1 model names in application.yml (only when the chat provider is Anthropic). */
    public static final String LAYER1_BEAN = "layer1AnthropicChatModel";
    private static final int DEFAULT_LAYER1_MAX_TOKENS = 2048;

    @Bean(BEAN)
    public AnthropicChatModel layer2AnthropicChatModel(
            @Value("${spring.ai.anthropic.api-key:}") String apiKey,
            @Value("${spring.ai.anthropic.base-url:}") String baseUrl,
            @Value("${bench.layer2.anthropic.model:claude-sonnet-5-5}") String model,
            @Value("${contexa.security.tiered.layer2.max-output-tokens:" + DEFAULT_MAX_TOKENS + "}") int maxTokens,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ToolCallingManager> toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return chatModel(apiKey, baseUrl, model, maxTokens, restClientBuilder, responseErrorHandler, retryTemplate,
                toolCallingManager, observationRegistry);
    }

    /**
     * Layer 1 on Anthropic (claude-haiku-5-5) while the OpenAI balance is used up. Spring AI's own Anthropic chat model is
     * {@code @ConditionalOnMissingBean} by type, so the Layer 2 bean above keeps it from being made; and a layer model with
     * no runtime binding runs on a copy of the primary chat model, which the engine cannot make of an Anthropic model (it
     * has no {@code mutate()}). Without a bean and a binding of its own, Layer 1 would run on the Layer 2 model. The
     * binding is {@code contexa.llm.bindings.chat.<model>} in application.yml.
     */
    @Bean(LAYER1_BEAN)
    @ConditionalOnProperty(prefix = "spring.ai.model", name = "chat", havingValue = "anthropic")
    public AnthropicChatModel layer1AnthropicChatModel(
            @Value("${spring.ai.anthropic.api-key:}") String apiKey,
            @Value("${spring.ai.anthropic.base-url:}") String baseUrl,
            @Value("${bench.layer1.anthropic.model:claude-haiku-5-5}") String model,
            @Value("${contexa.security.tiered.layer1.max-output-tokens:" + DEFAULT_LAYER1_MAX_TOKENS + "}") int maxTokens,
            ObjectProvider<RestClient.Builder> restClientBuilder,
            ObjectProvider<ResponseErrorHandler> responseErrorHandler,
            ObjectProvider<RetryTemplate> retryTemplate,
            ObjectProvider<ToolCallingManager> toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return chatModel(apiKey, baseUrl, model, maxTokens, restClientBuilder, responseErrorHandler, retryTemplate,
                toolCallingManager, observationRegistry);
    }

    /**
     * The strategy of Anthropic's prompt cache (NONE, SYSTEM_ONLY, ...): the fixed front of the request (the layer's system
     * instruction) is read from the provider's cache at a tenth of the input price when it is long enough to be cached.
     * Set with {@code bench.anthropic.prompt-cache}; NONE switches it off.
     */
    private static AnthropicCacheOptions promptCache() {
        String strategy = System.getProperty("bench.anthropic.prompt-cache",
                System.getenv().getOrDefault("BENCH_ANTHROPIC_PROMPT_CACHE", "SYSTEM_ONLY"));
        return AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.valueOf(strategy.trim().toUpperCase())).build();
    }

    private static AnthropicChatModel chatModel(String apiKey, String baseUrl, String model, int maxTokens,
                                                ObjectProvider<RestClient.Builder> restClientBuilder,
                                                ObjectProvider<ResponseErrorHandler> responseErrorHandler,
                                                ObjectProvider<RetryTemplate> retryTemplate,
                                                ObjectProvider<ToolCallingManager> toolCallingManager,
                                                ObjectProvider<ObservationRegistry> observationRegistry) {
        AnthropicApi.Builder api = AnthropicApi.builder()
                .apiKey(apiKey == null || apiKey.isBlank() ? NO_KEY : apiKey.trim())
                .restClientBuilder(restClientBuilder.getIfAvailable(RestClient::builder))
                .responseErrorHandler(responseErrorHandler.getIfAvailable(() -> RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER));
        if (baseUrl != null && !baseUrl.isBlank()) {
            api.baseUrl(baseUrl.trim());
        }
        return AnthropicChatModel.builder()
                .anthropicApi(api.build())
                .defaultOptions(AnthropicChatOptions.builder().model(model).maxTokens(maxTokens).cacheOptions(promptCache()).build())
                .toolCallingManager(toolCallingManager.getIfAvailable(() -> ToolCallingManager.builder().build()))
                .retryTemplate(retryTemplate.getIfAvailable(() -> RetryUtils.DEFAULT_RETRY_TEMPLATE))
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
    }

    /**
     * Stops the start when Layer 2 runs on the Anthropic model and the key is missing or is not a key (the user's
     * environment variable ANTHROPIC_API_KEY holds a short placeholder; this application reads BENCH_ANTHROPIC_API_KEY).
     */
    @Bean
    public SmartInitializingSingleton layer2AnthropicKeyCheck(
            @Value("${contexa.security.tiered.llm.layer2.model:}") String layer2Model,
            @Value("${bench.layer2.anthropic.model:claude-sonnet-5-5}") String anthropicModel,
            @Value("${spring.ai.anthropic.api-key:}") String apiKey) {
        return () -> {
            if (layer2Model == null || !layer2Model.trim().equalsIgnoreCase(anthropicModel.trim())) {
                return;
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Layer 2 runs on " + anthropicModel + " but no Anthropic key is set. "
                        + "Set the environment variable BENCH_ANTHROPIC_API_KEY (scripts/start-guard.sh reads it from the "
                        + "key file of the contexa docs without printing it).");
            }
            if (apiKey.trim().length() < 40) {
                throw new IllegalStateException("The Anthropic key is too short to be a key (" + apiKey.trim().length()
                        + " characters); it looks like a placeholder. Layer 2 needs BENCH_ANTHROPIC_API_KEY.");
            }
        };
    }
}

package io.contexa.bench.guard;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts the response record ({@link CachingChatModel}) around the two Anthropic chat models of the engine's layers.
 * {@code bench.cache.mode} is LIVE (default: always ask the model, and record the answer), CACHE or REPLAY;
 * {@code bench.cache.dir} is where the records are kept (default {@code ./cache}). Only the beans of the two layers are
 * wrapped, so nothing else of the engine changes.
 */
@Configuration(proxyBeanMethods = false)
public class ResponseCacheConfiguration {

    /** The caches made in this process, by layer label (read by {@link ResponseCacheController}). */
    static final Map<String, CachingChatModel> CACHES = new ConcurrentHashMap<>();

    @Bean
    static BeanPostProcessor responseCachePostProcessor(Environment environment) {
        CachingChatModel.Mode mode = CachingChatModel.Mode.valueOf(
                environment.getProperty("bench.cache.mode", "LIVE").trim().toUpperCase(Locale.ROOT));
        Path directory = Path.of(environment.getProperty("bench.cache.dir", "./cache"));
        boolean normalise = Boolean.parseBoolean(environment.getProperty("bench.cache.normalise", "true"));
        boolean storePrompt = Boolean.parseBoolean(environment.getProperty("bench.cache.store-prompt", "false"));
        // Every call that reaches a model is written down here (cost ledger, spend.py); one file for both layers of this run.
        Path ledger = Path.of(environment.getProperty("bench.cache.ledger", directory.resolve("model-calls.jsonl").toString()));
        Map<String, String> layers = Map.of(
                Layer2AnthropicConfiguration.LAYER1_BEAN, "layer1",
                Layer2AnthropicConfiguration.BEAN, "layer2");
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                String label = layers.get(beanName);
                if (label == null || !(bean instanceof org.springframework.ai.chat.model.ChatModel model)) {
                    return bean;
                }
                CachingChatModel cached = new CachingChatModel(model, label, mode, directory, normalise, storePrompt, ledger);
                CACHES.put(label, cached);
                return cached;
            }
        };
    }

    /** What every cache of this process did since the start. */
    static List<CachingChatModel.Stats> stats() {
        return CACHES.values().stream().map(CachingChatModel::stats).toList();
    }
}

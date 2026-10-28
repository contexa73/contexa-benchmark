package io.contexa.bench.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A model is paid for once per distinct request; the same request is answered from the record at no cost. */
class CachingChatModelTest {

    private static final class CountingModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            int n = calls.incrementAndGet();
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .model("test-model").usage(new DefaultUsage(1000, 200, 1200)).build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer-" + n))), metadata);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("test-model").build();
        }
    }

    private static Prompt prompt(String system, String user, int maxTokens) {
        return new Prompt(List.of(new SystemMessage(system), new UserMessage(user)),
                ChatOptions.builder().model("test-model").maxTokens(maxTokens).build());
    }

    @Test
    void theSameRequestIsAnsweredFromTheRecordAndCostsNothing(@TempDir Path dir) {
        CountingModel real = new CountingModel();
        CachingChatModel model = new CachingChatModel(real, "layer1", CachingChatModel.Mode.CACHE, dir);

        ChatResponse first = model.call(prompt("system", "facts", 2048));
        ChatResponse second = model.call(prompt("system", "facts", 2048));

        assertThat(real.calls.get()).isEqualTo(1);
        assertThat(second.getResult().getOutput().getText()).isEqualTo(first.getResult().getOutput().getText());
        assertThat(second.getMetadata().getModel()).isEqualTo("test-model");
        assertThat(second.getMetadata().getUsage().getTotalTokens()).isZero();
        assertThat(model.stats().hits()).isEqualTo(1);
        assertThat(model.stats().misses()).isEqualTo(1);
        assertThat(model.stats().savedPromptTokens()).isEqualTo(1000);
        assertThat(model.stats().savedCompletionTokens()).isEqualTo(200);
    }

    @Test
    void anyChangeOfTheRequestIsAnotherRequest(@TempDir Path dir) {
        CountingModel real = new CountingModel();
        CachingChatModel model = new CachingChatModel(real, "layer1", CachingChatModel.Mode.CACHE, dir);

        model.call(prompt("system", "facts", 2048));
        model.call(prompt("system", "facts changed", 2048));
        model.call(prompt("system changed", "facts", 2048));
        model.call(prompt("system", "facts", 1024));

        assertThat(real.calls.get()).isEqualTo(4);
        assertThat(model.stats().hits()).isZero();
    }

    @Test
    void liveModeAlwaysCallsAndRecords(@TempDir Path dir) {
        CountingModel real = new CountingModel();
        CachingChatModel live = new CachingChatModel(real, "layer1", CachingChatModel.Mode.LIVE, dir);

        live.call(prompt("system", "facts", 2048));
        live.call(prompt("system", "facts", 2048));
        assertThat(real.calls.get()).isEqualTo(2);

        CountingModel other = new CountingModel();
        CachingChatModel cache = new CachingChatModel(other, "layer1", CachingChatModel.Mode.CACHE, dir);
        cache.call(prompt("system", "facts", 2048));
        assertThat(other.calls.get()).isZero();
        assertThat(cache.stats().hits()).isEqualTo(1);
    }

    @Test
    void replayModeNeverCallsTheModel(@TempDir Path dir) {
        CountingModel real = new CountingModel();
        new CachingChatModel(real, "layer1", CachingChatModel.Mode.CACHE, dir).call(prompt("system", "facts", 2048));

        CountingModel unused = new CountingModel();
        CachingChatModel replay = new CachingChatModel(unused, "layer1", CachingChatModel.Mode.REPLAY, dir);

        assertThat(replay.call(prompt("system", "facts", 2048)).getResult().getOutput().getText()).isEqualTo("answer-1");
        assertThatThrownBy(() -> replay.call(prompt("system", "never recorded", 2048)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REPLAY");
        assertThat(unused.calls.get()).isZero();
    }

    @Test
    void valuesThatDifferOnlyBetweenRunsDoNotMakeAnotherRequest(@TempDir Path dir) {
        CountingModel real = new CountingModel();
        CachingChatModel model = new CachingChatModel(real, "layer1", CachingChatModel.Mode.CACHE, dir, true);

        model.call(prompt("system", "Subject agent-run-1a2b3c4d5e-9f8e7d6c acted at 2026-10-28T02:53:54.825Z under "
                + "delegation 3f1c2a9e-1b2c-4d5e-8f90-a1b2c3d4e5f6, request 1793152000123.", 2048));
        model.call(prompt("system", "Subject agent-run-1a2b3c4d5e-0a1b2c3d acted at 2026-10-28T03:11:07.001Z under "
                + "delegation aaaaaaaa-1b2c-4d5e-8f90-a1b2c3d4e5f6, request 1793153999999.", 2048));
        assertThat(real.calls.get()).isEqualTo(1);
        assertThat(model.stats().hits()).isEqualTo(1);

        // What the decision depends on is never normalised away: another account number is another request.
        model.call(prompt("system", "send to US133000000121212121212", 2048));
        model.call(prompt("system", "send to US133000000121212121299", 2048));
        assertThat(real.calls.get()).isEqualTo(3);
    }

    /** A model that answers with a thinking part before the text part, as the Anthropic models do. */
    private static final class ThinkingModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .model("test-model").usage(new DefaultUsage(1000, 200, 1200)).build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("")),
                    new Generation(new AssistantMessage("{\"action\":\"ALLOW\"}"))), metadata);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().model("test-model").build();
        }
    }

    @Test
    void theAnswerIsThePartThatHoldsTheAnswerNotTheFirstPart(@TempDir Path dir) throws Exception {
        CachingChatModel live = new CachingChatModel(new ThinkingModel(), "layer2", CachingChatModel.Mode.LIVE, dir, true, true);
        live.call(prompt("system text", "user facts", 2560));

        String line = java.nio.file.Files.readAllLines(dir.resolve("layer2.jsonl")).get(0);
        com.fasterxml.jackson.databind.JsonNode stored = new com.fasterxml.jackson.databind.ObjectMapper().readTree(line);
        assertThat(stored.get("text").asText()).isEqualTo("{\"action\":\"ALLOW\"}");
        assertThat(stored.get("parts")).hasSize(2);
        // The request is kept as it was sent, so another model can be asked the same thing later.
        com.fasterxml.jackson.databind.JsonNode raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stored.get("raw").asText());
        assertThat(raw.get("maxTokens").asInt()).isEqualTo(2560);
        assertThat(raw.get("messages").get(0).get("type").asText()).isEqualTo("SYSTEM");
        assertThat(raw.get("messages").get(0).get("text").asText()).isEqualTo("system text");
        assertThat(raw.get("messages").get(1).get("text").asText()).isEqualTo("user facts");
    }

    @Test
    void everyCallThatReachesTheModelIsInTheLedgerAndAnAnswerFromTheRecordIsNot(@TempDir Path dir) throws Exception {
        Path ledger = dir.resolve("model-calls.jsonl");
        CachingChatModel model = new CachingChatModel(new CountingModel(), "layer1", CachingChatModel.Mode.CACHE, dir, true,
                false, ledger);

        model.call(prompt("system", "facts", 2048));
        model.call(prompt("system", "facts", 2048));
        model.call(prompt("system", "other facts", 2048));

        List<String> lines = java.nio.file.Files.readAllLines(ledger);
        assertThat(lines).hasSize(2);
        com.fasterxml.jackson.databind.JsonNode first = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lines.get(0));
        assertThat(first.get("layer").asText()).isEqualTo("layer1");
        assertThat(first.get("model").asText()).isEqualTo("test-model");
        assertThat(first.get("promptTokens").asInt()).isEqualTo(1000);
        assertThat(first.get("completionTokens").asInt()).isEqualTo(200);
        // the provider's own cache-read figure is not available here: it is written as -1 (unknown), never as 0
        assertThat(first.get("cacheReadTokens").asInt()).isEqualTo(-1);
    }

    @Test
    void theRequestIsNotKeptUnlessAskedFor(@TempDir Path dir) throws Exception {
        new CachingChatModel(new ThinkingModel(), "layer2", CachingChatModel.Mode.LIVE, dir, true, false)
                .call(prompt("system text", "user facts", 2560));
        String line = java.nio.file.Files.readAllLines(dir.resolve("layer2.jsonl")).get(0);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(line).get("raw").isNull()).isTrue();
    }

    @Test
    void withoutNormalisationOnlyTheIdenticalRequestIsReused(@TempDir Path dir) {
        CountingModel real = new CountingModel();
        CachingChatModel model = new CachingChatModel(real, "layer1", CachingChatModel.Mode.CACHE, dir, false);

        model.call(prompt("system", "at 2026-10-28T02:53:54.825Z", 2048));
        model.call(prompt("system", "at 2026-10-28T03:11:07.001Z", 2048));

        assertThat(real.calls.get()).isEqualTo(2);
    }
}

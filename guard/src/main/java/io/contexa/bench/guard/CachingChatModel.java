package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Keeps the answer of a model to every distinct request and answers the same request again without calling the model.
 * <p>
 * The key is the SHA-256 of the whole request: the model, every message with its type and text, and the options that
 * shape the answer (limit of the answer, temperature, top-p, top-k). Anything that changes what the model would see,
 * such as the prompt bundle, a fact line or an option, is another request and is paid for once; anything that does not
 * (a policy, a declaration, a setting outside the prompt) is not. The answers are kept in a file per layer
 * ({@code <label>.jsonl}) so that a later start finds them.
 * <ul>
 * <li>{@code LIVE}: always asks the model, and records the answer.</li>
 * <li>{@code CACHE}: answers from the record when there is one, else asks the model and records the answer.</li>
 * <li>{@code REPLAY}: answers from the record only; a request with no record is an error. The model is never called, so
 * a run can be repeated without a key and without cost.</li>
 * </ul>
 * An answer from the record carries zero usage (it cost nothing); the tokens it saved are counted in {@link #stats()}.
 */
public class CachingChatModel implements ChatModel {

    public enum Mode { LIVE, CACHE, REPLAY }

    /** What the cache did since the start. */
    public record Stats(String label, Mode mode, long hits, long misses, long savedPromptTokens,
                        long savedCompletionTokens, int recorded) {
    }

    /**
     * @param prompt the normalised key text (only with store-prompt), kept for the earlier records
     * @param parts  the text of every part of the answer (a model may answer with a thinking part and a text part); {@code text}
     *               is the part that looks like the answer
     * @param raw    the request as it was sent (messages with their types, and the options), only with store-prompt; it is
     *               what a replay against another model needs
     */
    private record Stored(String key, String model, String text, long promptTokens, long completionTokens,
                          long cachedTokens, String prompt, List<String> parts, String raw) {
    }

    /**
     * Values that are different in every run without being a fact the decision depends on: identifiers a run makes up for
     * itself (the run's subject {@code agent-...-<8 hex>}, a UUID, a long hex string with letters), ISO timestamps and
     * epoch milliseconds. They are replaced by a placeholder in the key only (the model still sees the real text), so a
     * repeated run finds its earlier answers. Account numbers, names, amounts and every other fact stay as they are.
     */
    private static final List<Pattern> VOLATILE = List.of(
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
            Pattern.compile("(?:agent|bench-user)-[A-Za-z0-9-]*-[0-9a-f]{8}\\b"),
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?"),
            Pattern.compile("(?<![\\w.-])1[67]\\d{11}(?![\\w-])"),
            Pattern.compile("(?<![\\w.-])(?=[0-9a-f]*[a-f])[0-9a-f]{16,}(?![\\w-])"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatModel delegate;
    private final String label;
    private final Mode mode;
    private final Path file;
    private final boolean normalise;
    private final boolean storePrompt;
    private final Path ledger;
    private final Object ledgerLock = new Object();
    private final Map<String, Stored> entries = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong savedPrompt = new AtomicLong();
    private final AtomicLong savedCompletion = new AtomicLong();

    public CachingChatModel(ChatModel delegate, String label, Mode mode, Path directory) {
        this(delegate, label, mode, directory, true, false);
    }

    public CachingChatModel(ChatModel delegate, String label, Mode mode, Path directory, boolean normalise) {
        this(delegate, label, mode, directory, normalise, false);
    }

    public CachingChatModel(ChatModel delegate, String label, Mode mode, Path directory, boolean normalise,
                            boolean storePrompt) {
        this(delegate, label, mode, directory, normalise, storePrompt, null);
    }

    /**
     * @param ledger where every call that reached the model is written down with its tokens (null for none): the money the
     *               benchmark spent on this layer, whoever asked, a decision or anything else the engine does with the model
     */
    public CachingChatModel(ChatModel delegate, String label, Mode mode, Path directory, boolean normalise,
                            boolean storePrompt, Path ledger) {
        this.delegate = delegate;
        this.label = label;
        this.mode = mode;
        this.normalise = normalise;
        this.storePrompt = storePrompt;
        this.ledger = ledger;
        try {
            Files.createDirectories(directory);
            this.file = directory.resolve(label + ".jsonl");
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        Stored stored = JSON.readValue(line, Stored.class);
                        entries.put(stored.key(), stored);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The response record " + label + " in " + directory + " could not be read", e);
        }
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String key = keyOf(prompt);
        String keyText = lastKeyText.get();
        if (mode != Mode.LIVE) {
            Stored stored = entries.get(key);
            if (stored != null) {
                hits.incrementAndGet();
                savedPrompt.addAndGet(stored.promptTokens());
                savedCompletion.addAndGet(stored.completionTokens());
                return fromRecord(stored);
            }
            if (mode == Mode.REPLAY) {
                throw new IllegalStateException("REPLAY: no recorded answer for this request (" + label + ", key "
                        + key.substring(0, 12) + ")");
            }
        }
        ChatResponse response = delegate.call(prompt);
        misses.incrementAndGet();
        writeLedger(response);
        record(key, response, storePrompt ? keyText : null, storePrompt ? rawOf(prompt) : null);
        return response;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    public Stats stats() {
        return new Stats(label, mode, hits.get(), misses.get(), savedPrompt.get(), savedCompletion.get(), entries.size());
    }

    private String keyOf(Prompt prompt) {
        ChatOptions options = prompt.getOptions() != null ? prompt.getOptions() : delegate.getDefaultOptions();
        StringBuilder canonical = new StringBuilder();
        canonical.append("model=").append(options == null ? "" : options.getModel()).append('\n');
        canonical.append("maxTokens=").append(options == null ? "" : options.getMaxTokens()).append('\n');
        canonical.append("temperature=").append(options == null ? "" : options.getTemperature()).append('\n');
        canonical.append("topP=").append(options == null ? "" : options.getTopP()).append('\n');
        canonical.append("topK=").append(options == null ? "" : options.getTopK()).append('\n');
        for (Message message : prompt.getInstructions()) {
            canonical.append(message.getMessageType().name()).append('\u0000').append(message.getText()).append('\u0001');
        }
        String text = normalise ? normalised(canonical.toString()) : canonical.toString();
        lastKeyText.set(text);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalised(String text) {
        String out = text;
        for (Pattern volatileValue : VOLATILE) {
            out = volatileValue.matcher(out).replaceAll("<volatile>");
        }
        return out;
    }

    /** Per thread: calls overlap, and the key text of one call must not be taken for another's. */
    private final ThreadLocal<String> lastKeyText = ThreadLocal.withInitial(() -> "");

    /**
     * One line per call that reached the model. The cache-read tokens come from the provider's own usage record when it has
     * one; when it has not, they are written as -1 (unknown), never as 0, so that nothing looks cheaper than it was.
     */
    private void writeLedger(ChatResponse response) {
        if (ledger == null || response == null) {
            return;
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        long cacheRead = -1;
        Object nativeUsage = usage == null ? null : usage.getNativeUsage();
        if (nativeUsage != null) {
            try {
                Object value = nativeUsage.getClass().getMethod("cacheReadInputTokens").invoke(nativeUsage);
                cacheRead = value instanceof Number number ? number.longValue() : 0L;
            } catch (ReflectiveOperationException e) {
                cacheRead = -1;
            }
        }
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("at", java.time.Instant.now().toString());
        row.put("layer", label);
        row.put("model", response.getMetadata() == null ? null : response.getMetadata().getModel());
        row.put("promptTokens", usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
        row.put("completionTokens", usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
        row.put("cacheReadTokens", cacheRead);
        try {
            synchronized (ledgerLock) {
                Files.createDirectories(ledger.toAbsolutePath().getParent());
                Files.writeString(ledger, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new IllegalStateException("The model-call ledger " + ledger + " could not be written", e);
        }
    }

    /** The request as sent: every message with its type and text, and the options that shape the answer. */
    private String rawOf(Prompt prompt) {
        ChatOptions options = prompt.getOptions() != null ? prompt.getOptions() : delegate.getDefaultOptions();
        java.util.Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("model", options == null ? null : options.getModel());
        raw.put("maxTokens", options == null ? null : options.getMaxTokens());
        raw.put("temperature", options == null ? null : options.getTemperature());
        raw.put("topP", options == null ? null : options.getTopP());
        raw.put("topK", options == null ? null : options.getTopK());
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            messages.add(Map.of("type", message.getMessageType().name(), "text", String.valueOf(message.getText())));
        }
        raw.put("messages", messages);
        try {
            return JSON.writeValueAsString(raw);
        } catch (IOException e) {
            throw new IllegalStateException("The request could not be written down", e);
        }
    }

    /**
     * The part of an answer that is the answer: the first part that opens a JSON object, else the last part with text.
     * A thinking part has text too, so the first part is not the answer by position.
     */
    static String answerOf(List<String> parts) {
        String last = "";
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (part.stripLeading().startsWith("{")) {
                return part;
            }
            last = part;
        }
        return last;
    }

    private void record(String key, ChatResponse response, String keyText, String raw) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return;
        }
        List<String> parts = new java.util.ArrayList<>();
        for (Generation generation : response.getResults()) {
            parts.add(generation.getOutput() == null || generation.getOutput().getText() == null ? ""
                    : generation.getOutput().getText());
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        long prompt = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long completion = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        Stored stored = new Stored(key,
                response.getMetadata() == null ? "" : String.valueOf(response.getMetadata().getModel()),
                answerOf(parts), prompt, completion, 0L, keyText, parts, raw);
        entries.put(key, stored);
        try {
            Files.writeString(file, JSON.writeValueAsString(stored) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("The response record " + file + " could not be written", e);
        }
    }

    private ChatResponse fromRecord(Stored stored) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(stored.model())
                .usage(new DefaultUsage(0, 0, 0))
                .keyValue("bench-cache-hit", true)
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(stored.text()))), metadata);
    }
}

package io.contexa.bench.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The importance class of each tool, read from the frozen classification of the benchmark (contexa-benchmark/declarations:
 * {@code agentdojo-tools.yml}, {@code injecagent-tools.yml}, written from the tools' names and meaning alone before any result,
 * see {@code freeze.json}). The guard has no table of its own: what an adopter declares about its tools is one frozen file, and
 * the guard refuses to start when a file differs from the hash it was frozen with.
 * <p>
 * The file says what a tool does ({@code operation}), whether it touches personal data ({@code data}) and where its effect
 * lands ({@code effect}); this class turns that into the class that decides how the tool is declared to the engine. A tool
 * that is in no file is a WRITE: an unknown tool is not read-only.
 */
public class ToolTable {

    /** The class of a tool and how it was decided. */
    public record ToolSpec(String tool, ToolClass toolClass, String basis) {
    }

    private final int version;
    private final String sha256;
    private final Map<String, ToolClass> listed = new LinkedHashMap<>();
    private final Map<String, String> fileHashes = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public ToolTable(Path directory) {
        try {
            JsonNode freeze = new ObjectMapper().readTree(Files.readAllBytes(directory.resolve("freeze.json")));
            MessageDigest total = MessageDigest.getInstance("SHA-256");
            int highest = 0;
            Iterator<Map.Entry<String, JsonNode>> files = freeze.get("files").fields();
            while (files.hasNext()) {
                Map.Entry<String, JsonNode> file = files.next();
                byte[] bytes = Files.readAllBytes(directory.resolve(file.getKey()));
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                String frozen = file.getValue().get("sha256").asText();
                if (!actual.equals(frozen)) {
                    throw new IllegalStateException("The tool classification " + file.getKey()
                            + " differs from its freeze (" + actual + " instead of " + frozen + "); a change is a new version");
                }
                fileHashes.put(file.getKey(), actual);
                total.update(actual.getBytes(StandardCharsets.UTF_8));
                Map<String, Object> root = new Yaml().load(new String(bytes, StandardCharsets.UTF_8));
                highest = Math.max(highest, ((Number) root.get("version")).intValue());
                Map<String, Map<String, String>> tools = (Map<String, Map<String, String>>) root.get("tools");
                tools.forEach((name, fields) -> listed.put(normalise(name), classify(name, fields)));
            }
            this.version = highest;
            this.sha256 = HexFormat.of().formatHex(total.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("The tool classification in " + directory + " could not be read", e);
        }
    }

    /** What the three fields of the frozen file make of a tool. */
    static ToolClass classify(String tool, Map<String, String> fields) {
        String operation = fields.getOrDefault("operation", "").toUpperCase(Locale.ROOT);
        String data = fields.getOrDefault("data", "").toUpperCase(Locale.ROOT);
        String effect = fields.getOrDefault("effect", "").toUpperCase(Locale.ROOT);
        return switch (operation) {
            case "CREDENTIAL", "ADMIN", "EXECUTE" -> ToolClass.CREDENTIAL;
            case "TRANSFER", "RESERVE" -> ToolClass.MONEY;
            case "DELETE" -> ToolClass.DELETE;
            case "SEND", "SHARE", "EXPORT" -> ToolClass.EXTERNAL;
            case "WRITE" -> "EXTERNAL".equals(effect) ? ToolClass.EXTERNAL : ToolClass.WRITE;
            case "READ" -> {
                if ("ACCOUNT_SECURITY".equals(effect)) {
                    yield ToolClass.CREDENTIAL;
                }
                yield "PUBLIC".equals(data) && "NONE".equals(effect) ? ToolClass.READ_PUBLIC : ToolClass.READ;
            }
            default -> throw new IllegalStateException("The tool " + tool + " has the unknown operation '" + operation + "'");
        };
    }

    public ToolSpec of(String tool) {
        ToolClass listedClass = listed.get(normalise(tool));
        if (listedClass != null) {
            return new ToolSpec(tool, listedClass, "listed");
        }
        return new ToolSpec(tool, ToolClass.WRITE, "unknown");
    }

    public int version() {
        return version;
    }

    /** The SHA-256 over the hashes of the frozen files, which the results of a run name. */
    public String sha256() {
        return sha256;
    }

    /** The hash of each frozen file by name. */
    public Map<String, String> fileHashes() {
        return fileHashes;
    }

    public int listedTools() {
        return listed.size();
    }

    private static String normalise(String tool) {
        return tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
    }
}

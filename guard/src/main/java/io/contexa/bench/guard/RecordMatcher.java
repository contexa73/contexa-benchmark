package io.contexa.bench.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compares the values a tool call names (an account, a person, a file, a channel) with the records of the run's
 * environment as its initial state states them (before any attack is injected): which of them the environment knows. It
 * states facts only; what a name the environment does not know means is the decision's to weigh.
 * <p>
 * A kind of record the run did not send is not looked up: its values are neither known nor unknown.
 */
public final class RecordMatcher {

    public enum Kind {
        COUNTERPARTY("known counterparty"),
        CONTACT("known contact"),
        FILE("known file"),
        CHANNEL("known channel");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One value a call names.
     *
     * @param argument the argument it was passed in
     * @param value    the value as passed
     * @param known    whether the environment's records hold it
     */
    public record Target(Kind kind, String argument, String value, boolean known) {

        /**
         * The name the engine's record states for the target: a known value is the record's own; an unknown value is
         * echoed only when its form is closed (an account number, an address); any other text of the call is the
         * requester's and is not repeated.
         */
        public String recordName() {
            if (known || closedForm(kind, value)) {
                return value;
            }
            return "the " + kind.name().toLowerCase(Locale.ROOT) + " named in the call";
        }
    }

    /**
     * @param targets the values the call names that the run's records can be compared with, in the order of the call
     * @param primary the one the engine's single assignment record states: the first unknown value by kind order
     *                (counterparty, contact, file, channel), else the first known; null without a target
     */
    public record Lookup(List<Target> targets, Target primary) {

        public boolean empty() {
            return targets.isEmpty();
        }
    }

    private static final Pattern IBAN = Pattern.compile("^[A-Za-z]{2}\\d{2}[A-Za-z0-9]{10,30}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>\"']{1,64}@[^\\s@<>\"']{1,255}$");
    private static final Pattern FILE_ARGUMENT = Pattern.compile("(?i).*(file|filename|document|path).*");
    private static final Pattern CHANNEL_ARGUMENT = Pattern.compile("(?i).*channel.*");
    private static final Pattern CONTACT_ARGUMENT =
            Pattern.compile("(?i)^(user|users|recipient|recipients|participant|participants|contact|contacts|to|cc|bcc)$");
    private static final int MAX_DEPTH = 3;
    private static final int MAX_TARGETS = 20;
    private static final int MAX_VALUE = 200;

    private RecordMatcher() {
    }

    public static Lookup lookup(Map<String, Object> arguments, GuardRuns.Records records) {
        List<Target> targets = new ArrayList<>();
        if (arguments != null && records != null && !records.empty()) {
            collect("", arguments, 0, records, targets);
        }
        Target primary = null;
        for (Kind kind : Kind.values()) {
            for (Target target : targets) {
                if (target.kind() == kind && !target.known()) {
                    primary = target;
                    break;
                }
            }
            if (primary != null) {
                break;
            }
        }
        if (primary == null) {
            for (Kind kind : Kind.values()) {
                for (Target target : targets) {
                    if (target.kind() == kind) {
                        primary = target;
                        break;
                    }
                }
                if (primary != null) {
                    break;
                }
            }
        }
        return new Lookup(List.copyOf(targets), primary);
    }

    private static void collect(String name, Object value, int depth, GuardRuns.Records records, List<Target> out) {
        if (value == null || depth > MAX_DEPTH || out.size() >= MAX_TARGETS) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, inner) -> collect(String.valueOf(key), inner, depth + 1, records, out));
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                collect(name, item, depth + 1, records, out);
            }
        } else if (value instanceof CharSequence || value instanceof Number) {
            String text = value.toString().trim();
            if (text.isEmpty() || text.length() > MAX_VALUE) {
                return;
            }
            Kind kind = kindOf(name, text);
            List<String> known = kind == null ? null : recordsOf(kind, records);
            if (kind != null && known != null) {
                out.add(new Target(kind, name, text, contains(known, kind, text)));
            }
        }
    }

    private static Kind kindOf(String argument, String value) {
        String compact = value.replace(" ", "");
        if (IBAN.matcher(compact).matches()) {
            return Kind.COUNTERPARTY;
        }
        if (EMAIL.matcher(value).matches()) {
            return Kind.CONTACT;
        }
        if (CHANNEL_ARGUMENT.matcher(argument).matches()) {
            return Kind.CHANNEL;
        }
        if (FILE_ARGUMENT.matcher(argument).matches()) {
            return Kind.FILE;
        }
        if (CONTACT_ARGUMENT.matcher(argument).matches()) {
            return Kind.CONTACT;
        }
        return null;
    }

    private static List<String> recordsOf(Kind kind, GuardRuns.Records records) {
        return switch (kind) {
            case COUNTERPARTY -> records.knownCounterparties();
            case CONTACT -> records.knownContacts();
            case FILE -> records.knownFiles();
            case CHANNEL -> records.knownChannels();
        };
    }

    private static boolean contains(List<String> known, Kind kind, String value) {
        String wanted = normalise(kind, value);
        for (String entry : known) {
            if (entry != null && normalise(kind, entry).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(Kind kind, String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        return kind == Kind.COUNTERPARTY ? text.replace(" ", "") : text;
    }

    /** Whether the form of the value is closed: an account number or an address, which cannot carry instructions. */
    static boolean closedForm(Kind kind, String value) {
        return switch (kind) {
            case COUNTERPARTY -> IBAN.matcher(value.replace(" ", "")).matches();
            case CONTACT -> EMAIL.matcher(value).matches();
            default -> false;
        };
    }
}

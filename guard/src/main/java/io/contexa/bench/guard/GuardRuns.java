package io.contexa.bench.guard;

import io.contexa.contexacore.autonomous.service.UserEngineStatePurger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The runs the guard knows: one run of the benchmark's agent is one new acting subject ({@code agent-<runId>-<generation>})
 * and one delegation. A decision of the engine is kept under that subject, so a restriction of one run (a BLOCK is
 * never lifted by the engine) never reaches the next run.
 */
public class GuardRuns implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GuardRuns.class);

    /**
     * The company records of the run's environment as the benchmark's initial state states them (before any attack is
     * injected): the values a tool call may name, by kind of record.
     *
     * @param knownCounterparties accounts the user has paid or is paid by (IBANs)
     * @param knownContacts       people and addresses the user knows (e-mail addresses, user names)
     * @param knownFiles          files of the environment (file IDs, names)
     * @param knownChannels       channels of the environment
     */
    public record Records(List<String> knownCounterparties, List<String> knownContacts, List<String> knownFiles,
                          List<String> knownChannels) {

        public static final Records NONE = new Records(null, null, null, null);

        /** A null list means the kind of record is not integrated (not looked up); an empty list means none known. */
        public Records {
            knownCounterparties = knownCounterparties == null ? null : List.copyOf(knownCounterparties);
            knownContacts = knownContacts == null ? null : List.copyOf(knownContacts);
            knownFiles = knownFiles == null ? null : List.copyOf(knownFiles);
            knownChannels = knownChannels == null ? null : List.copyOf(knownChannels);
        }

        public boolean empty() {
            return knownCounterparties == null && knownContacts == null && knownFiles == null && knownChannels == null;
        }

        public int count() {
            return size(knownCounterparties) + size(knownContacts) + size(knownFiles) + size(knownChannels);
        }

        private static int size(List<String> list) {
            return list == null ? 0 : list.size();
        }
    }

    /**
     * The suite-level policy the task is delegated under: the objective's family and what it may do.
     *
     * @param family            the objective family the delegation names
     * @param allowedOperations the operations the delegation allows (READ, WRITE, ...), empty for any
     * @param allowedResources  the resources it allows, empty for any
     */
    public record Scope(String family, List<String> allowedOperations, List<String> allowedResources) {

        public static final Scope NONE = new Scope(null, List.of(), List.of());

        public Scope {
            family = family == null || family.isBlank() ? null : family.trim();
            allowedOperations = allowedOperations == null ? List.of() : List.copyOf(allowedOperations);
            allowedResources = allowedResources == null ? List.of() : List.copyOf(allowedResources);
        }

        public boolean empty() {
            return family == null && allowedOperations.isEmpty() && allowedResources.isEmpty();
        }
    }

    /** What {@code /guard/begin} asks for. */
    public record BeginSpec(String runId, String goal, String suite, GuardTier tier, Records records, Scope scope) {
    }

    /** One run of the benchmark's agent. */
    public static final class Run {
        private final String runId;
        private final String suite;
        private final String goal;
        private final String principal;
        private final String agentId;
        private final GuardTier tier;
        private final Records records;
        private final Scope scope;
        private final Instant startedAt = Instant.now();
        private final AtomicLong calls = new AtomicLong();
        private volatile String delegationId;
        private volatile String accessToken;
        private volatile String clientId;
        private volatile String stoppedBy;

        Run(String runId, String suite, String goal, String principal, String agentId, GuardTier tier, Records records,
            Scope scope) {
            this.runId = runId;
            this.suite = suite;
            this.goal = goal;
            this.principal = principal;
            this.agentId = agentId;
            this.tier = tier;
            this.records = records;
            this.scope = scope;
        }

        public String runId() {
            return runId;
        }

        public String suite() {
            return suite;
        }

        public String goal() {
            return goal;
        }

        public String principal() {
            return principal;
        }

        public String agentId() {
            return agentId;
        }

        /** The stage this run runs with: the one it asked for, never above the server's. */
        public GuardTier tier() {
            return tier;
        }

        public Records records() {
            return records;
        }

        public Scope scope() {
            return scope;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public AtomicLong calls() {
            return calls;
        }

        public String delegationId() {
            return delegationId;
        }

        public void delegationId(String delegationId) {
            this.delegationId = delegationId;
        }

        /** The delegated access token of the run (C2): the person's token exchanged by the agent, with the act chain. */
        public String accessToken() {
            return accessToken;
        }

        public void accessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String clientId() {
            return clientId;
        }

        public void clientId(String clientId) {
            this.clientId = clientId;
        }

        /** Why the run was stopped by the engine (the delegation revoked), or null. */
        public String stoppedBy() {
            return stoppedBy;
        }

        public void stoppedBy(String stoppedBy) {
            this.stoppedBy = stoppedBy;
        }
    }

    private final GuardProperties properties;
    private final ObjectProvider<UserEngineStatePurger> purger;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<String, Run> byPrincipal = new ConcurrentHashMap<>();
    private final ScheduledExecutorService purges = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "guard-run-purge");
        thread.setDaemon(true);
        return thread;
    });

    public GuardRuns(GuardProperties properties, ObjectProvider<UserEngineStatePurger> purger) {
        this.properties = properties;
        this.purger = purger;
    }

    public Run begin(String runId, String goal, String suite) {
        return begin(new BeginSpec(runId, goal, suite, null, null, null));
    }

    /**
     * Starts a run with a new acting subject. A run started again under the same ID is a new run: the earlier one is
     * ended first, and the new subject differs from every earlier subject of that ID by its generation suffix.
     */
    public Run begin(BeginSpec spec) {
        String id = safe(spec.runId());
        GuardTier tier = spec.tier() == null ? properties.tier() : GuardTier.lower(spec.tier(), properties.tier());
        String suite = spec.suite() == null ? "" : spec.suite();
        Run run = new Run(id, suite, spec.goal() == null ? "" : spec.goal(),
                properties.principalPrefix() + (id.length() > 48 ? id.substring(0, 48) : id) + "-"
                        + UUID.randomUUID().toString().substring(0, 8),
                agentIdOf(suite), tier, spec.records() == null ? Records.NONE : spec.records(),
                spec.scope() == null ? Scope.NONE : spec.scope());
        Run replaced = runs.put(id, run);
        byPrincipal.put(run.principal(), run);
        if (replaced != null) {
            log.warn("[guard] run {} began again; the earlier subject {} is ended", id, replaced.principal());
            schedulePurge(replaced);
        }
        return run;
    }

    /** One fixed agent per suite from C2 on; one agent in C1. */
    String agentIdOf(String suite) {
        if (!properties.tier().atLeast(GuardTier.C2)) {
            return properties.agentId();
        }
        String part = suite == null || suite.isBlank() ? "default" : safe(suite).toLowerCase();
        return properties.agentId() + "-" + part;
    }

    public Run find(String runId) {
        return runId == null ? null : runs.get(safe(runId));
    }

    /** The run of an acting subject, also after the run ended and before its engine state is purged. */
    public Run findByPrincipal(String principal) {
        return principal == null ? null : byPrincipal.get(principal);
    }

    /** Ends the run: its registration goes now, its engine state after the purge delay. Null if the run is unknown. */
    public Run end(String runId) {
        Run run = runId == null ? null : runs.remove(safe(runId));
        if (run != null) {
            schedulePurge(run);
        }
        return run;
    }

    public int active() {
        return runs.size();
    }

    private void schedulePurge(Run run) {
        if (properties.purgeDelay().isNegative()) {
            return;
        }
        purges.schedule(() -> purge(run), properties.purgeDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void purge(Run run) {
        byPrincipal.remove(run.principal(), run);
        UserEngineStatePurger engineStatePurger = purger.getIfAvailable();
        if (engineStatePurger == null) {
            return;
        }
        try {
            var result = engineStatePurger.purge(run.principal());
            log.info("[guard] engine state of {} purged: {}", run.principal(), result);
        } catch (RuntimeException failure) {
            log.error("[guard] engine state of {} could not be purged", run.principal(), failure);
        }
    }

    /** The run ID as a subject name part: letters, digits, dot, underscore and hyphen. */
    static String safe(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        String trimmed = runId.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            out.append(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return out.toString();
    }

    @Override
    public void destroy() {
        purges.shutdownNow();
    }
}

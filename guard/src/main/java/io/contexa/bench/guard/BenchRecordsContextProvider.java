package io.contexa.bench.guard;

import io.contexa.contexacommon.domain.SecurityEvent;
import io.contexa.contexacore.autonomous.context.CanonicalSecurityContext;
import io.contexa.contexacore.autonomous.context.CanonicalSecurityContext.AssignmentRecord;
import io.contexa.contexacore.autonomous.context.CanonicalSecurityContext.FrictionProfile;
import io.contexa.contexacore.autonomous.context.enricher.FrictionContextProvider;
import io.contexa.contexacore.autonomous.context.enricher.RecordsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The host's company-record comparison (the engine's {@link FrictionContextProvider} extension point): the values a tool
 * call names are compared with the records of the run's environment as its initial state states them, and the
 * comparison is put into the request's context as a record of the person the agent acts for. It states facts only and
 * never decides: the engine's decision reads "assigned to X (known counterparty)" or "not assigned to X" among the
 * records and weighs it.
 * <p>
 * The engine's context has one assignment record per request, so the record states the call's primary target (an unknown
 * value first). The environment's records are kinds the engine's vocabulary has no word for (counterparties, contacts,
 * files, channels); an assignment of the person to the target ("this is among the person's known counterparties") is
 * the nearest record the engine reads, and the record's responsibility says which kind.
 */
public class BenchRecordsContextProvider implements FrictionContextProvider {

    private static final Logger log = LoggerFactory.getLogger(BenchRecordsContextProvider.class);

    private final GuardRuns runs;
    private final CallContexts calls;

    public BenchRecordsContextProvider(GuardRuns runs, CallContexts calls) {
        this.runs = runs;
        this.calls = calls;
    }

    @Override
    public void enrich(SecurityEvent event, CanonicalSecurityContext context) {
        if (event == null || context == null || event.getMetadata() == null) {
            return;
        }
        try {
            Object requestId = event.getMetadata().get("requestId");
            CallContexts.Call call = calls.of(requestId == null ? null : requestId.toString());
            if (call == null || !call.run().tier().atLeast(GuardTier.C2) || call.run().records().empty()) {
                return;
            }
            RecordMatcher.Lookup lookup = RecordMatcher.lookup(call.arguments(), call.run().records());
            call.lookup(lookup);
            RecordsHolder holder = RecordsHolder.of(event);
            if (holder != null) {
                holder.stateOn(context);
            }
            FrictionProfile profile = context.getFrictionProfile();
            if (profile == null) {
                profile = new FrictionProfile();
                context.setFrictionProfile(profile);
            }
            // The kinds the run sent are looked up: a value the records do not hold is a fact, not an unknown.
            List<String> checked = new ArrayList<>(profile.getCheckedRecordKinds() == null ? List.of()
                    : profile.getCheckedRecordKinds());
            if (!checked.contains(FrictionProfile.RECORD_ASSIGNMENT)) {
                checked.add(FrictionProfile.RECORD_ASSIGNMENT);
            }
            profile.setCheckedRecordKinds(checked);
            RecordMatcher.Target primary = lookup.primary();
            if (primary != null && profile.getAssignment() == null) {
                profile.setAssignment(AssignmentRecord.builder()
                        .target(primary.recordName())
                        .assigned(primary.known())
                        .responsibility(primary.known() ? primary.kind().label() : null)
                        .build());
            }
        } catch (RuntimeException failure) {
            // The engine swallows a provider's failure with a warning; say it here as an error.
            log.error("[guard] the company-record comparison failed", failure);
        }
    }

    /** What the answer states of the comparison: the kinds checked and each value's result. */
    static Map<String, Object> describe(RecordMatcher.Lookup lookup, GuardRuns.Records records) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        List<String> kinds = new ArrayList<>();
        if (records.knownCounterparties() != null) {
            kinds.add("counterparties");
        }
        if (records.knownContacts() != null) {
            kinds.add("contacts");
        }
        if (records.knownFiles() != null) {
            kinds.add("files");
        }
        if (records.knownChannels() != null) {
            kinds.add("channels");
        }
        out.put("kindsLooked", kinds);
        if (lookup != null) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (RecordMatcher.Target target : lookup.targets()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("kind", target.kind().name());
                row.put("argument", target.argument());
                row.put("known", target.known());
                values.add(row);
            }
            out.put("values", values);
            if (lookup.primary() != null) {
                out.put("statedToEngine", lookup.primary().recordName() + (lookup.primary().known() ? " (known)" : " (unknown)"));
            }
        }
        return out;
    }
}

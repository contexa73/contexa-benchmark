package io.contexa.bench.guard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The calls being decided, by decision ID: what the host knows of each call while the engine decides it (the run, the
 * tool and its class, the arguments as passed), and what the host's providers compared. The engine's providers read it
 * by the request ID of the event; the answer of the call reads what they recorded. Bounded.
 */
public class CallContexts {

    /**
     * A call being decided.
     *
     * @param lookup what the records provider compared with the run's records, set while the engine builds its context
     */
    public static final class Call {
        private final GuardRuns.Run run;
        private final ToolTable.ToolSpec spec;
        private final Map<String, Object> arguments;
        private volatile RecordMatcher.Lookup lookup;

        Call(GuardRuns.Run run, ToolTable.ToolSpec spec, Map<String, Object> arguments) {
            this.run = run;
            this.spec = spec;
            this.arguments = arguments;
        }

        public GuardRuns.Run run() {
            return run;
        }

        public ToolTable.ToolSpec spec() {
            return spec;
        }

        public Map<String, Object> arguments() {
            return arguments;
        }

        public RecordMatcher.Lookup lookup() {
            return lookup;
        }

        public void lookup(RecordMatcher.Lookup lookup) {
            this.lookup = lookup;
        }
    }

    private final Map<String, Call> calls;

    public CallContexts(int limit) {
        this.calls = new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Call> eldest) {
                return size() > limit;
            }
        };
    }

    public Call put(String decisionId, GuardRuns.Run run, ToolTable.ToolSpec spec, Map<String, Object> arguments) {
        Call call = new Call(run, spec, arguments);
        synchronized (calls) {
            calls.put(decisionId, call);
        }
        return call;
    }

    public Call of(String decisionId) {
        synchronized (calls) {
            return decisionId == null ? null : calls.get(decisionId);
        }
    }
}

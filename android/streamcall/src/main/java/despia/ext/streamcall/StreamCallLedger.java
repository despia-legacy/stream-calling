package despia.ext.streamcall;

/**
 * The call state machine of the Stream calling template, the Java twin of the Swift
 * StreamCallLedger. Pure Java with no Android import, so it can be tested on a plain JVM against
 * the same state table as the Swift ledger. One call is live at a time. Every attempt gets an epoch from
 * a counter the ledger owns, and every asynchronous result carries the epoch it was started under,
 * so a result that arrives after leave, or for an earlier attempt of the same call id, never changes
 * the current call. Main thread only.
 */
public final class StreamCallLedger {
    public static final String RINGING = "ringing";
    public static final String CONNECTING = "connecting";
    public static final String JOINED = "joined";

    /** ok:&lt;detail&gt;, error:&lt;code&gt;, drop (stale join result: leave that SDK call) or ignored. */
    public static final class Outcome {
        public final String kind;
        public final String value;

        private Outcome(String kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        static Outcome ok(String detail) {
            return new Outcome("ok", detail);
        }

        static Outcome error(String code) {
            return new Outcome("error", code);
        }

        public boolean isOk() {
            return kind.equals("ok");
        }

        public boolean isDrop() {
            return kind.equals("drop");
        }

        public String line() {
            return value == null ? kind : kind + ":" + value;
        }
    }

    private static final Outcome DROP = new Outcome("drop", null);
    private static final Outcome IGNORED = new Outcome("ignored", null);

    /** The live call; fields are read by the engine, written only here. */
    public static final class Live {
        public final String cid;
        public final int epoch;
        public final boolean incoming;
        String state;
        Boolean wantedMuted;
        boolean muted;

        Live(String cid, int epoch, boolean incoming, String state) {
            this.cid = cid;
            this.epoch = epoch;
            this.incoming = incoming;
            this.state = state;
        }

        public String state() {
            return state;
        }

        public Boolean wantedMuted() {
            return wantedMuted;
        }

        public boolean muted() {
            return muted;
        }
    }

    private boolean configured;
    private Live live;
    private int epochs;

    public boolean configured() {
        return configured;
    }

    public Live live() {
        return live;
    }

    public String status() {
        return live == null ? "idle" : live.state;
    }

    public Outcome configure() {
        configured = true;
        return Outcome.ok("configured");
    }

    public Outcome logout() {
        configured = false;
        int n = live == null ? 0 : 1;
        live = null;
        return Outcome.ok("logout:" + n);
    }

    public Outcome join(String cid) {
        if (!configured) return Outcome.error("not_configured");
        if (live != null) return Outcome.error("busy");
        epochs++;
        live = new Live(cid, epochs, false, CONNECTING);
        return Outcome.ok("connecting:" + epochs);
    }

    public Outcome incoming(String cid) {
        if (!configured) return Outcome.error("not_configured");
        if (live != null) return Outcome.error(live.cid.equals(cid) ? "duplicate" : "busy");
        epochs++;
        live = new Live(cid, epochs, true, RINGING);
        return Outcome.ok("ringing:" + epochs);
    }

    public Outcome answered(String cid) {
        if (live == null || !live.cid.equals(cid) || !RINGING.equals(live.state)) return IGNORED;
        live.state = CONNECTING;
        return Outcome.ok("connecting:" + live.epoch);
    }

    public Outcome connected(String cid, int epoch) {
        if (live == null || !live.cid.equals(cid) || live.epoch != epoch || !CONNECTING.equals(live.state)) return DROP;
        live.state = JOINED;
        return Outcome.ok("joined");
    }

    public Outcome ended(String cid, int epoch, String reason) {
        if (live == null || !live.cid.equals(cid) || live.epoch != epoch) return IGNORED;
        live = null;
        return Outcome.ok("ended:" + reason);
    }

    public Outcome leave() {
        if (live == null) return Outcome.error("no_call");
        String state = live.state;
        live = null;
        return Outcome.ok(RINGING.equals(state) ? "ended:declined" : "ended:local");
    }

    public Outcome setMuted(boolean muted) {
        if (live == null) return Outcome.error("no_call");
        live.wantedMuted = muted;
        return Outcome.ok(JOINED.equals(live.state) ? "apply" : "pending");
    }

    public Outcome wanted() {
        if (live == null) return Outcome.error("no_call");
        return Outcome.ok("wanted:" + (live.wantedMuted == null ? "none" : String.valueOf(live.wantedMuted)));
    }

    public Outcome muteApplied(int epoch, boolean muted) {
        if (live == null || live.epoch != epoch || !JOINED.equals(live.state)) return IGNORED;
        live.muted = muted;
        return Outcome.ok("muted:" + muted);
    }

    public Outcome reset() {
        int n = live == null ? 0 : 1;
        live = null;
        return Outcome.ok("reset:" + n);
    }
}

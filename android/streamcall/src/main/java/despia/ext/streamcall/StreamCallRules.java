package despia.ext.streamcall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure rules shared by the extension and the engine, the Java twin of ios/app/StreamCallRules.swift.
 * No Android class is used, so the JVM suite runs them with javac and java alone.
 */
public final class StreamCallRules {
    private StreamCallRules() {}

    /** Stream's FCM sender value (stream-video-android-core, StreamNotificationManager / push data). */
    public static final String STREAM_SENDER = "stream.video";

    /** A call type, call id, API key or provider name: 1 to 64 of letters, digits, '_' and '-'. */
    public static boolean isName(String s) {
        return matches(s, 64, false);
    }

    /** A Stream user id: 1 to 128 of letters, digits, '@', '_' and '-'. */
    public static boolean isUserId(String s) {
        return matches(s, 128, true);
    }

    private static boolean matches(String s, int max, boolean at) {
        if (s == null || s.isEmpty() || s.length() > max) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || (at && c == '@');
            if (!ok) return false;
        }
        return true;
    }

    /** Comma-separated user ids, at most 32; null when one is malformed. */
    public static List<String> members(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        String[] parts = raw.split(",", -1);
        if (parts.length > 32) return null;
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String id = p.trim();
            if (!isUserId(id)) return null;
            out.add(id);
        }
        return out;
    }

    /** The type of "<type>:<id>", or null. */
    public static String callType(String cid) {
        int i = cid == null ? -1 : cid.indexOf(':');
        if (i <= 0 || i == cid.length() - 1) return null;
        return cid.substring(0, i);
    }

    /** The id of "<type>:<id>", or null. */
    public static String callId(String cid) {
        return callType(cid) == null ? null : cid.substring(cid.indexOf(':') + 1);
    }

    /**
     * Whether an FCM data message is Stream's: its sender is "stream.video". Every other message is
     * left to the base app's own FirebaseMessagingService, untouched.
     */
    public static boolean isStreamPush(Map<String, String> data) {
        return data != null && STREAM_SENDER.equals(data.get("sender"));
    }

    /**
     * The push state from what Stream really stored: "registered" only when its stored device is this
     * install's current FCM token, from Firebase, under the configured provider name; else "pending".
     */
    public static String pushState(String deviceId, String deviceProvider, String deviceProviderName,
                                   String fcmToken, String firebaseKey, String providerName) {
        boolean same = deviceId != null && deviceId.equals(fcmToken)
                && firebaseKey != null && firebaseKey.equals(deviceProvider)
                && providerName != null && providerName.equals(deviceProviderName);
        return same ? "registered" : "pending";
    }

    /**
     * The calls whose system ring (Telecom, full-screen notification, phone-call service) this
     * process has shown and that still ring. Stream can deliver one ring twice, over its connection
     * and by FCM, and it is shown once. A cid rings again as soon as its ring is over: the page or
     * Stream ended the call, it was refused as busy, or Stream stopped ringing it. A push handed to
     * Stream that Stream never made its ringing call (its ring service did not start, or the call
     * was already over) stops counting after HANDOFF_MS. Thread-safe: FCM and Stream's state
     * collectors call it from different threads.
     */
    public static final class SystemRings {
        /** How long a push hand-off counts before Stream's ringing call takes it over. */
        public static final long HANDOFF_MS = 30_000L;
        private final Map<String, Long> handedOff = new HashMap<>();
        private final Set<String> ringing = new HashSet<>();

        /** True: show cid's system ring now (it is recorded as handed to Stream); false: it already shows. */
        public synchronized boolean claim(String cid, long nowMs) {
            if (cid == null) return true;
            if (ringing.contains(cid)) return false;
            Long at = handedOff.get(cid);
            if (at != null && nowMs >= at && nowMs - at < HANDOFF_MS) return false;
            handedOff.put(cid, nowMs);
            return true;
        }

        /** Stream's ringing call is cid: its system ring shows until release(cid). */
        public synchronized void ringing(String cid) {
            if (cid == null) return;
            handedOff.remove(cid);
            ringing.add(cid);
        }

        /** cid's ring is over; the next ring for it is shown. */
        public synchronized void release(String cid) {
            handedOff.remove(cid);
            ringing.remove(cid);
        }
    }

    /** The message that goes with one of the template's error codes. */
    public static String message(String code) {
        switch (code) {
            case "not_configured": return "call configure first";
            case "busy": return "another call is live";
            case "no_call": return "there is no call";
            case "audio_busy": return "a recording holds the microphone";
            default: return "the call was not changed";
        }
    }
}

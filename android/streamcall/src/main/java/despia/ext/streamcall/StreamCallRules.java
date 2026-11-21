package despia.ext.streamcall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

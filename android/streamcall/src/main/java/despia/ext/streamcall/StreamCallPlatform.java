package despia.ext.streamcall;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** Read-only platform facts. Nothing here changes a setting, a route or an audio mode. */
final class StreamCallPlatform {
    private StreamCallPlatform() {}

    static boolean micGranted(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** BLUETOOTH_CONNECT exists from API 31; below it a headset route needs no runtime grant. */
    static boolean bluetoothGranted(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** POST_NOTIFICATIONS exists from API 33; the ring notification needs it there. */
    static boolean canNotify(Context c) {
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    /**
     * Whether the ring may show full screen over the lock screen. From API 34 the user (or Play, for
     * apps not declared as calling apps) can turn USE_FULL_SCREEN_INTENT off; below it the manifest
     * permission is enough.
     */
    static boolean canFullScreen(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
    }

    /**
     * Whether this app is recording now (the recorder template), read from the platform's own list of
     * active recordings (API 24). A join under it is refused with audio_busy; the template never
     * takes the microphone from a recording in flight.
     */
    static boolean recordingActive(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        return am != null && !am.getActiveRecordingConfigurations().isEmpty();
    }
}

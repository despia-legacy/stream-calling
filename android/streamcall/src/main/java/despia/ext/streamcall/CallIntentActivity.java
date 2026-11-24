package despia.ext.streamcall;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.lang.ref.WeakReference;

/**
 * The activity Stream's call notifications open (its intent resolver looks for an activity of this
 * package that handles each action). The call screen is Stream's notification and Telecom's own
 * UI; this activity only acts and hands over to the app:
 * - ACCEPT_CALL (notification Answer): answer, then bring the app forward.
 * - INCOMING_CALL (full-screen intent over the lock screen): a Material 3 Answer / Decline screen
 *   (MaterialButton under Theme.Material3.DayNight.NoActionBar, light and dark).
 * - OUTGOING_CALL, ONGOING_CALL (tapping the notification): bring the app forward.
 * It is not exported; only the app's own PendingIntents start it.
 * It shows only a ring that still rings, closes when that ring is over, shows a new ring instead of
 * one that is over, and Answer and Decline act on the cid on screen only while it rings (emulator
 * 24 Nov 14:38Z: a screen left from an ended ring covered the next ring and Answer did nothing).
 */
public final class CallIntentActivity extends AppCompatActivity {
    static final String ACCEPT = "io.getstream.video.android.action.ACCEPT_CALL";
    static final String INCOMING = "io.getstream.video.android.action.INCOMING_CALL";
    /** The Answer / Decline screen now open, if any. Main thread only. */
    private static WeakReference<CallIntentActivity> open = new WeakReference<>(null);
    /** The cid this screen shows. */
    private String shown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        route(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        route(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (shown != null && !ringing(shown)) finish();
    }

    @Override
    protected void onDestroy() {
        if (open.get() == this) open.clear();
        super.onDestroy();
    }

    /** The engine: cid's ring is over. A screen showing it closes. */
    static void ringOver(String cid) {
        CallIntentActivity a = open.get();
        if (a != null && cid.equals(a.shown)) a.finish();
    }

    /** The engine: cid rings now. A screen still showing a ring that is over shows this one instead. */
    static void ringShown(String cid, String name) {
        CallIntentActivity a = open.get();
        if (a == null || a.isFinishing() || cid.equals(a.shown)) return;
        if (a.shown == null || !ringing(a.shown)) a.show(cid, name);
    }

    private static boolean ringing(String cid) {
        StreamCallExtension ext = StreamCallExtension.instance;
        return ext != null && ext.isRinging(cid);
    }

    private void route(Intent intent) {
        StreamCallExtension ext = StreamCallExtension.instance;
        String cid = ext == null ? null : ext.callIdOf(intent);
        String action = intent.getAction();
        if (ext == null || cid == null) {
            finish();
            return;
        }
        if (ACCEPT.equals(action)) {
            if (ringing(cid)) ext.answer(cid);
            openApp();
        } else if (INCOMING.equals(action)) {
            if (!ringing(cid)) {
                finish();
                return;
            }
            show(cid, ext.callerNameOf(intent));
        } else {
            openApp();
        }
    }

    private void show(String cid, String name) {
        shown = cid;
        open = new WeakReference<>(this);
        setContentView(R.layout.nx_streamcall_incoming);
        ((TextView) findViewById(R.id.nx_streamcall_caller)).setText(name == null ? getString(R.string.nx_streamcall_unknown) : name);
        findViewById(R.id.nx_streamcall_answer).setOnClickListener((View v) -> {
            StreamCallExtension ext = StreamCallExtension.instance;
            if (ext != null && ringing(shown)) {
                ext.answer(shown);
                openApp();
            } else {
                finish();
            }
        });
        findViewById(R.id.nx_streamcall_decline).setOnClickListener((View v) -> {
            StreamCallExtension ext = StreamCallExtension.instance;
            if (ext != null && ringing(shown)) ext.decline(shown);
            finish();
        });
    }

    private void openApp() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(launch);
        }
        finish();
    }
}

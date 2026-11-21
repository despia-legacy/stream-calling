package despia.ext.streamcall;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * The activity Stream's call notifications open (its intent resolver looks for an activity of this
 * package that handles each action). The call screen is Stream's notification and Telecom's own
 * UI; this activity only acts and hands over to the app:
 * - ACCEPT_CALL (notification Answer): answer, then bring the app forward.
 * - INCOMING_CALL (full-screen intent over the lock screen): a Material 3 Answer / Decline screen
 *   (MaterialButton under Theme.Material3.DayNight.NoActionBar, light and dark).
 * - OUTGOING_CALL, ONGOING_CALL (tapping the notification): bring the app forward.
 * It is not exported; only the app's own PendingIntents start it.
 */
public final class CallIntentActivity extends AppCompatActivity {
    static final String ACCEPT = "io.getstream.video.android.action.ACCEPT_CALL";
    static final String INCOMING = "io.getstream.video.android.action.INCOMING_CALL";

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

    private void route(Intent intent) {
        StreamCallExtension ext = StreamCallExtension.instance;
        String cid = ext == null ? null : ext.callIdOf(intent);
        String action = intent.getAction();
        if (ext == null || cid == null) {
            finish();
            return;
        }
        if (ACCEPT.equals(action)) {
            ext.answer(cid);
            openApp();
        } else if (INCOMING.equals(action)) {
            setContentView(R.layout.nx_streamcall_incoming);
            String name = ext.callerNameOf(intent);
            ((TextView) findViewById(R.id.nx_streamcall_caller)).setText(name == null ? getString(R.string.nx_streamcall_unknown) : name);
            findViewById(R.id.nx_streamcall_answer).setOnClickListener((View v) -> {
                ext.answer(cid);
                openApp();
            });
            findViewById(R.id.nx_streamcall_decline).setOnClickListener((View v) -> {
                ext.decline(cid);
                finish();
            });
        } else {
            openApp();
        }
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

package despia.ext.streamcall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Hands Stream's own FCM data messages to Stream; every other message is left alone. The base app
 * keeps its one FirebaseMessagingService and still receives every message (this receiver sits beside
 * Firebase's receiver on the same broadcast and never aborts it). Only Google Play services holds
 * com.google.android.c2dm.permission.SEND, so nothing else can deliver here.
 */
public final class StreamPushReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        Map<String, String> data = new HashMap<>();
        for (String k : extras.keySet()) {
            Object v = extras.get(k);
            if (v instanceof String) data.put(k, (String) v);
        }
        if (!StreamCallRules.isStreamPush(data)) return;
        StreamCallExtension ext = StreamCallExtension.instance;
        if (ext == null) return;
        ext.onStreamPush(extras);
    }
}

package despia.ext.streamcall;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.despia.nativeext.ExtCall;
import com.despia.nativeext.ExtContext;
import com.despia.nativeext.ExtResult;
import com.despia.nativeext.NativeExtension;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stream audio calling for a V3 app, the Android twin of ios/app/StreamCallExtension.swift: a
 * template the app's developer owns. Audio only. The page starts or joins a call, leaves it, mutes
 * and switches the loudspeaker; incoming calls ring through Stream's FCM push as a real
 * self-managed Telecom call with Stream's CallStyle notification, also with the app closed.
 *
 * <p>No key or token is built into the app: {@code configure} takes them from the page, which got
 * the token from the developer's own server; they are stored in the extension's data directory so
 * a push can ring with no page loaded, and {@code logout} removes them. The token never appears in
 * a reply, an event or a log line.
 *
 * <p>Each command answers once. {@code join} answers once the ledger takes the call; the outcome is
 * the {@code joined} or {@code ended} event. Events carry {@code call_cid} and {@code seq};
 * {@code status} is the truth, since an event sent while no page is loaded is dropped. Every
 * method runs on the main thread.
 */
public final class StreamCallExtension implements NativeExtension, StreamCallEngine.Host {
    /** Set in onCreate; the push receiver and the call activity reach the engine through it. */
    static StreamCallExtension instance;

    private ExtContext ctx;
    private StreamCallEngine engine;
    private int seq;
    private ActivityResultLauncher<String[]> permission;
    private ComponentActivity registeredOn;
    private ExtCall pendingPermissions;
    private ExtResult.Callback pendingReply;

    @Override
    public void onCreate(ExtContext context) {
        ctx = context;
        instance = this;
        engine = new StreamCallEngine((Application) context.app(), this);
        engine.restore();
    }

    // ------------------------------------------------------------------ StreamCallEngine.Host

    @Override
    public void emit(String event, JSONObject data) {
        try {
            data.put("seq", ++seq);
        } catch (JSONException e) {
            return;
        }
        ctx.emit(event, data);
    }

    @Override
    public void log(String message) {
        ctx.log(message);
    }

    @Override
    public boolean isApprovedTokenUrl(String url) {
        return ctx.isApprovedUrl(url, "configure", "token_url");
    }

    @Override
    public File dataDir() {
        return ctx.dataDir();
    }

    // ------------------------------------------------------------------ push and the call activity

    void onStreamPush(Bundle extras) {
        engine.handlePush(extras);
    }

    String callIdOf(Intent intent) {
        return engine.callIdOf(intent);
    }

    String callerNameOf(Intent intent) {
        return engine.callerNameOf(intent);
    }

    void answer(String cid) {
        engine.answer(cid);
    }

    void decline(String cid) {
        engine.decline(cid);
    }

    // ------------------------------------------------------------------ activity lifecycle

    @Override
    public void onActivityEvent(Activity activity, ActivityEvent event) {
        if (!(activity instanceof ComponentActivity)) return;
        ComponentActivity a = (ComponentActivity) activity;
        if (event == ActivityEvent.CREATED) {
            if (permission != null) permission.unregister();
            registeredOn = a;
            permission = a.getActivityResultRegistry().register(ctx.resultKey("permissions"),
                    new ActivityResultContracts.RequestMultiplePermissions(), (Map<String, Boolean> result) -> {
                        ExtCall call = pendingPermissions;
                        ExtResult.Callback reply = pendingReply;
                        pendingPermissions = null;
                        pendingReply = null;
                        if (call == null) return;
                        if (!call.isActive()) {
                            reply.reply(ExtResult.error("timeout", "the permission answer came after the route timed out"));
                            return;
                        }
                        reply.reply(ExtResult.ok(permissionState()));
                    });
        } else if (event == ActivityEvent.DESTROYED && registeredOn == a) {
            if (permission != null) permission.unregister();
            permission = null;
            registeredOn = null;
            if (pendingPermissions != null) {
                ExtResult.Callback reply = pendingReply;
                pendingPermissions = null;
                pendingReply = null;
                reply.reply(ExtResult.error("unavailable", "the activity was destroyed"));
            }
        }
    }

    // ------------------------------------------------------------------ routes

    @Override
    public void handle(ExtCall call, ExtResult.Callback reply) {
        try {
            switch (call.route) {
                case "configure": configure(call, reply); return;
                case "set_token": {
                    String token = string(call, "token");
                    if (token == null || token.isEmpty()) {
                        reply.reply(ExtResult.error("bad_token", "token is required"));
                        return;
                    }
                    String code = engine.setToken(token, string(call, "request_id"));
                    reply.reply(code == null ? ExtResult.ok(new JSONObject()) : ExtResult.error(code, "the token was not stored"));
                    return;
                }
                case "logout":
                    engine.logout((code, data) -> reply.reply(ExtResult.ok(new JSONObject())));
                    return;
                case "permissions": permissions(call, reply); return;
                case "join": join(call, reply); return;
                case "leave": {
                    StreamCallLedger.Live l = engine.getLedger().live();
                    StreamCallLedger.Outcome o = engine.leave();
                    if (o.isOk()) {
                        reply.reply(ExtResult.ok(new JSONObject().put("call_cid", l.cid).put("state", "ended")));
                    } else {
                        reply.reply(ExtResult.error(o.value, StreamCallRules.message(o.value)));
                    }
                    return;
                }
                case "set_muted": {
                    Object m = call.params.get("muted");
                    if (!(m instanceof Boolean)) {
                        reply.reply(ExtResult.error("bad_request", "muted is required"));
                        return;
                    }
                    StreamCallLedger.Outcome o = engine.setMuted((Boolean) m);
                    if (!o.isOk()) {
                        reply.reply(ExtResult.error(o.value, StreamCallRules.message(o.value)));
                        return;
                    }
                    reply.reply(ExtResult.ok(new JSONObject().put("muted", m).put("call_cid", engine.getLedger().live().cid)));
                    return;
                }
                case "set_speaker": {
                    Object on = call.params.get("on");
                    if (!(on instanceof Boolean)) {
                        reply.reply(ExtResult.error("bad_request", "on is required"));
                        return;
                    }
                    JSONObject out = engine.setSpeaker((Boolean) on);
                    reply.reply(out == null ? ExtResult.error("no_call", "there is no joined call") : ExtResult.ok(out));
                    return;
                }
                case "status":
                    reply.reply(ExtResult.ok(engine.status()));
                    return;
                default:
                    reply.reply(ExtResult.error("not_registered", call.route));
            }
        } catch (JSONException e) {
            reply.reply(ExtResult.error("failed", "the reply could not be built"));
        }
    }

    private void configure(ExtCall call, ExtResult.Callback reply) {
        String apiKey = string(call, "api_key"), userId = string(call, "user_id"), token = string(call, "token");
        if (!StreamCallRules.isName(apiKey) || !StreamCallRules.isUserId(userId) || token == null || token.isEmpty()) {
            reply.reply(ExtResult.error("bad_request", "api_key, user_id and token are required"));
            return;
        }
        String provider = string(call, "fcm_provider");
        if (provider == null) provider = "firebase";
        if (!StreamCallRules.isName(provider)) {
            reply.reply(ExtResult.error("bad_request", "fcm_provider is malformed"));
            return;
        }
        StreamCallCredentials c = new StreamCallCredentials(apiKey, userId, string(call, "user_name"), token,
                string(call, "token_url"), provider);
        engine.configure(c, (code, data) -> {
            if (!call.isActive()) {
                reply.reply(ExtResult.error("timeout", "configure completed after the route timed out"));
            } else if (code != null) {
                reply.reply(ExtResult.error(code, "the Stream client was not configured"));
            } else {
                reply.reply(ExtResult.ok(data));
            }
        });
    }

    private void permissions(ExtCall call, ExtResult.Callback reply) throws JSONException {
        Application app = (Application) ctx.app();
        boolean request = Boolean.TRUE.equals(call.params.get("request"));
        List<String> missing = new ArrayList<>();
        if (!StreamCallPlatform.micGranted(app)) missing.add(Manifest.permission.RECORD_AUDIO);
        if (!StreamCallPlatform.bluetoothGranted(app)) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!StreamCallPlatform.canNotify(app)) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!request || missing.isEmpty()) {
            reply.reply(ExtResult.ok(permissionState()));
            return;
        }
        if (permission == null) {
            reply.reply(ExtResult.error("unavailable", "no activity is showing"));
            return;
        }
        if (pendingPermissions != null) {
            reply.reply(ExtResult.error("busy", "a permission request is already showing"));
            return;
        }
        pendingPermissions = call;
        pendingReply = reply;
        permission.launch(missing.toArray(new String[0]));
    }

    private JSONObject permissionState() {
        Application app = (Application) ctx.app();
        try {
            return new JSONObject()
                    .put("microphone", StreamCallPlatform.micGranted(app) ? "granted" : "denied")
                    .put("bluetooth", StreamCallPlatform.bluetoothGranted(app) ? "granted" : "denied")
                    .put("notifications", StreamCallPlatform.canNotify(app) ? "granted" : "denied");
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void join(ExtCall call, ExtResult.Callback reply) throws JSONException {
        String type = string(call, "call_type"), id = string(call, "call_id");
        if (!StreamCallRules.isName(type) || !StreamCallRules.isName(id)) {
            reply.reply(ExtResult.error("bad_request", "call_type and call_id are letters, digits, '_' or '-'"));
            return;
        }
        boolean ring = Boolean.TRUE.equals(call.params.get("ring"));
        String raw = string(call, "members");
        List<String> members = StreamCallRules.members(raw == null ? "" : raw);
        if (members == null || (ring && members.isEmpty())) {
            reply.reply(ExtResult.error("bad_request", "members is a comma-separated list of user ids; ring needs at least one"));
            return;
        }
        if (!StreamCallPlatform.micGranted((Application) ctx.app())) {
            reply.reply(ExtResult.error("permission_denied", "microphone access is not granted"));
            return;
        }
        StreamCallLedger.Outcome o = engine.join(type, id, Boolean.TRUE.equals(call.params.get("create")), ring, members);
        if (!o.isOk()) {
            reply.reply(ExtResult.error(o.value, StreamCallRules.message(o.value)));
            return;
        }
        reply.reply(ExtResult.ok(new JSONObject().put("call_cid", type + ":" + id).put("state", "connecting")));
    }

    private static String string(ExtCall call, String name) {
        Object v = call.params.get(name);
        return v instanceof String ? (String) v : null;
    }
}

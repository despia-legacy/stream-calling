package despia.ext.streamcall

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.IntentCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import io.getstream.android.push.PushDeviceGenerator
import io.getstream.android.push.PushDevice
import io.getstream.android.push.PushProvider
import io.getstream.android.push.firebase.FirebaseMessagingDelegate
import io.getstream.android.push.firebase.FirebasePushDeviceGenerator
import io.getstream.result.Result
import io.getstream.video.android.core.Call
import io.getstream.video.android.core.CreateCallOptions
import io.getstream.video.android.core.GEO
import io.getstream.video.android.core.RealtimeConnection
import io.getstream.video.android.core.RingingState
import io.getstream.video.android.core.StreamVideo
import io.getstream.video.android.core.StreamVideoBuilder
import io.getstream.video.android.core.audio.StreamAudioDevice
import io.getstream.video.android.core.call.CallType
import io.getstream.video.android.core.notifications.NotificationConfig
import io.getstream.video.android.core.notifications.NotificationHandler
import io.getstream.video.android.core.notifications.internal.service.CallServiceConfigRegistry
import io.getstream.video.android.core.notifications.internal.service.DefaultCallConfigurations
import io.getstream.video.android.core.notifications.internal.telecom.TelecomConfig
import io.getstream.video.android.core.socket.common.token.TokenProvider
import io.getstream.video.android.model.Device
import io.getstream.video.android.model.StreamCallId
import io.getstream.video.android.model.User
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only file of the template that talks to Stream (stream-video-android-core 1.35.0 and
 * stream-android-push-firebase 1.3.4, Maven Central's newest; no Stream UI artifact). Main thread only: every public method
 * is called on the main looper and every coroutine runs on Dispatchers.Main.
 *
 * Ownership, each one stated once:
 * - Telecom: Stream's Jetpack Telecom integration is enabled for every call type, so the call is a
 *   real self-managed Telecom call where the device supports it, with Stream's own CallStyle
 *   notification and phone-call foreground service; Stream falls back to the service and the
 *   notification by itself where Telecom is unusable.
 * - Audio: the template never calls AudioManager mode, speakerphone, communication-device or SCO
 *   methods (Android forbids driving routes outside Telecom); speaker and mute go through the SDK.
 * - Push: the base app keeps its one FirebaseMessagingService. StreamPushReceiver hands only
 *   Stream's own FCM messages to Stream.
 */
class StreamCallEngine(private val app: Application, private val host: Host) {
    /** What the engine needs from the extension (a Java interface, so Java implements it). */
    interface Host {
        fun emit(event: String, data: JSONObject)
        fun log(message: String)
        fun isApprovedTokenUrl(url: String): Boolean
        fun dataDir(): java.io.File
    }

    /** One answer per asynchronous command: an error code, or null and the data. */
    fun interface Reply {
        fun done(code: String?, data: JSONObject?)
    }

    val ledger = StreamCallLedger()
    var credentials: StreamCallCredentials? = null
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: StreamVideo? = null
    private var call: Call? = null
    private var watchers = mutableListOf<Job>()
    private var callWatchers = mutableListOf<Job>()
    private class TokenSession(var credentials: StreamCallCredentials) {
        var valid = true
        var revision = 0L
        val waiters = mutableMapOf<String, CompletableDeferred<String?>>()
    }
    private var activeSession: TokenSession? = null
    /** The last ring push Stream sent (cid, created_by_id, created_by_display_name): a push ring's call
     *  has no creator loaded when it rings, so the page's caller comes from the push, as on iOS. */
    private var pushCaller: Triple<String, String, String>? = null
    /** The cid of Stream's last missed-call push: Stream ends that ring as rejected by this user. */
    private var missedPush: String? = null
    private var candidateSession: TokenSession? = null
    private var candidateClient: StreamVideo? = null
    private var configuration = 0L
    private var deviceBarrier: Deferred<Unit>? = null
    private var deviceCleanupFailed = false
    private var failedCleanup: Triple<StreamVideo, StreamCallCredentials, Device?>? = null
    private var retiringClient: StreamVideo? = null
    private var retiringCredentials: StreamCallCredentials? = null
    private var retiringDevice: Device? = null
    /** Calls whose system ring (Telecom, full-screen notification, foreground service) is showing. */
    private val systemRings = StreamCallRules.SystemRings()
    /** The cid of Stream's ringing call as last seen, so a ring that stops is released. */
    private var streamRinging: String? = null
    /** Waits for Stream's ringing call to report its ringing state; replaced on every change. */
    private var pendingRing: Job? = null

    fun interface CommandState { fun isActive(): Boolean }

    /** Application.onCreate (also when FCM starts the process): rebuild the client from storage. */
    fun restore() {
        val stored = StreamCallCredentials.load(host.dataDir()) ?: return
        credentials = stored
        val session = TokenSession(stored)
        activeSession = session
        val c = build(session)
        attach(c, session)
        ledger.configure()
        scope.launch { registerPush(c, session) }
    }

    private fun hasFirebase(): Boolean = FirebaseApp.getApps(app).isNotEmpty()

    private fun registry(): CallServiceConfigRegistry = CallServiceConfigRegistry().apply {
        // Every call type, the built-in livestream type included, runs on Stream's audio call
        // service (microphone and phone-call foreground types) with Telecom enabled. The call type
        // stays the page's choice: nothing here limits which types exist.
        val audio = DefaultCallConfigurations.audioCall.copy(enableTelecom = true)
        register(CallType.AnyMarker.name, audio)
        register(CallType.Livestream.name, audio)
    }

    // Stream keeps its first notification configuration when later clients replace its API.
    // This generator therefore belongs to the engine, not to the first configured user.
    private val pushGenerator = object : PushDeviceGenerator {
        private fun selected(): TokenSession? =
            candidateSession?.takeIf { it.valid } ?: activeSession?.takeIf { it.valid }

        override fun isValidForThisDevice(): Boolean = hasFirebase() &&
            FirebasePushDeviceGenerator(providerName = "firebase", context = app).isValidForThisDevice()

        override fun onPushDeviceGeneratorSelected() {
            scope.launch(Dispatchers.Main.immediate) {
                val session = selected() ?: return@launch
                if (hasFirebase()) {
                    FirebasePushDeviceGenerator(providerName = session.credentials.fcmProvider, context = app)
                        .onPushDeviceGeneratorSelected()
                }
            }
        }

        override fun asyncGeneratePushDevice(onPushDeviceGenerated: (PushDevice) -> Unit) {
            scope.launch(Dispatchers.Main.immediate) {
                val session = selected() ?: return@launch
                if (!hasFirebase()) return@launch
                val delegate = FirebasePushDeviceGenerator(providerName = session.credentials.fcmProvider, context = app)
                delegate.onPushDeviceGeneratorSelected()
                delegate.asyncGeneratePushDevice { device ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (owns(session)) onPushDeviceGenerated(device)
                    }
                }
            }
        }
    }

    /** connect: false when configure() connects itself and waits for the answer. */
    private fun build(session: TokenSession, connect: Boolean = true): StreamVideo {
        val c = session.credentials
        StreamVideo.instanceOrNull()?.let { StreamVideo.removeClient() }
        val generators = listOf(pushGenerator)
        return StreamVideoBuilder(
            context = app,
            apiKey = c.apiKey,
            geo = GEO.GlobalEdgeNetwork,
            user = User(id = c.userId, name = c.userName),
            token = c.token,
            tokenProvider = object : TokenProvider {
                // Stream's contract (TokenProvider.loadToken, 1.35.0): "If the token cannot be loaded,
                // returns an empty string and never throws an exception." NO_TOKEN is that value.
                override suspend fun loadToken(): String {
                    return withContext(Dispatchers.Main.immediate) { refreshToken(session) ?: NO_TOKEN }
                }
            },
            notificationConfig = NotificationConfig(
                pushDeviceGenerators = generators,
                // The page asks for permissions through the `permissions` route; Stream never prompts.
                requestPermissionOnAppLaunch = { false },
                requestPermissionOnDeviceRegistration = { false },
            ),
            callServiceConfigRegistry = registry(),
            telecomConfig = TelecomConfig(app.packageName),
            connectOnInit = connect,
        ).build()
    }

    private fun attach(c: StreamVideo, session: TokenSession) {
        watchers.forEach { it.cancel() }
        pendingRing?.cancel()
        watchers = mutableListOf(
            scope.launch { c.state.ringingCall.collect { ringing -> if (activeSession === session) ringingChanged(ringing) } },
            scope.launch { c.state.activeCall.collect { active -> if (activeSession === session) activeChanged(active) } },
            scope.launch { c.getDevice().collect { d -> if (activeSession === session) registeredDevice = d } },
            scope.launch { currentFcmToken() },
        )
        client = c
    }

    fun configure(c: StreamCallCredentials, active: CommandState = CommandState { true }, reply: Reply) {
        if (ledger.live() != null) return reply.done("busy", null)
        val old = credentials ?: retiringCredentials ?: StreamCallCredentials.load(host.dataDir())
        val previous = client ?: retiringClient
        val oldDevice = registeredDevice ?: retiringDevice
        val supersededClient = candidateClient
        val supersededCredentials = candidateSession?.credentials
        configuration += 1
        val operation = configuration
        invalidate(candidateSession)
        invalidate(activeSession)
        candidateClient = null
        activeSession = null
        credentials = null
        watchers.forEach { it.cancel() }
        watchers.clear()
        client = null
        ledger.logout()
        retiringClient = previous
        retiringCredentials = old
        retiringDevice = oldDevice
        val session = TokenSession(c)
        candidateSession = session
        if (supersededClient != null && supersededCredentials != null) {
            retire(supersededClient, supersededCredentials, null)
        }
        failedCleanup?.let { retry ->
            if (retry.first !== previous && retry.first !== supersededClient) retire(retry.first, retry.second, retry.third)
        }
        val cleanup = if (previous != null && old != null) {
            retire(previous, old, oldDevice)
        } else deviceBarrier
        scope.launch {
            cleanup?.await()
            if (configuration != operation) return@launch reply.done("session_changed", null)
            if (deviceCleanupFailed) {
                invalidate(session)
                candidateSession = null
                return@launch reply.done("storage_failed", null)
            }
            if (!active.isActive()) {
                invalidate(session)
                candidateSession = null
                restorePrevious(old, previous, oldDevice, operation)
                return@launch reply.done("timeout", null)
            }
            // This SDK reuses its notification API manager. Retired clients must not be used
            // for device work after the replacement has installed another user's API.
            retiringClient = null
            retiringDevice = null
            val fresh = build(session, connect = false)
            candidateClient = fresh
            val connected = try {
                withTimeoutOrNull(15_000) { fresh.connectAsync().await() }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                null
            }
            if (configuration != operation || candidateSession !== session) {
                // The newer operation owns cleanup of this installed/retiring client.
                return@launch reply.done("session_changed", null)
            }
            val expired = !active.isActive()
            val next = session.credentials
            val stored = !expired && connected is Result.Success && StreamCallCredentials.save(host.dataDir(), next)
            if (!stored) {
                invalidate(session)
                candidateSession = null
                candidateClient = null
                retire(fresh, next, null).await()
                if (configuration != operation) return@launch reply.done("session_changed", null)
                if (deviceCleanupFailed) return@launch reply.done("storage_failed", null)
                restorePrevious(old, previous, oldDevice, operation)
                return@launch reply.done(if (expired) "timeout" else if (connected !is Result.Success) "connect_failed" else "storage_failed", null)
            }
            activeSession = session
            candidateSession = null
            candidateClient = null
            retiringClient = null
            retiringCredentials = null
            retiringDevice = null
            credentials = next
            registeredDevice = null
            attach(fresh, session)
            ledger.configure()
            registerPush(fresh, session)
            if (activeSession !== session) return@launch reply.done("session_changed", null)
            reply.done(null, JSONObject().put("user_id", next.userId).put("push", pushState()))
        }
    }

    private suspend fun restorePrevious(old: StreamCallCredentials?, previous: StreamVideo?, device: Device?, operation: Long) {
        if (configuration != operation) return
        retiringClient = null
        retiringCredentials = null
        retiringDevice = null
        if (old == null) return
        val restored = TokenSession(old)
        activeSession = restored
        credentials = old
        val restoredClient = if (previous != null && StreamVideo.instanceOrNull() === previous) previous else build(restored)
        registeredDevice = device
        attach(restoredClient, restored)
        ledger.configure()
        registerPush(restoredClient, restored)
    }

    private fun queueDeviceWork(work: suspend () -> Unit): Deferred<Unit> {
        val previous = deviceBarrier
        val queued = scope.async(start = CoroutineStart.LAZY) {
            previous?.await()
            work()
        }
        deviceBarrier = queued
        queued.start()
        return queued
    }

    private fun retire(owned: StreamVideo, c: StreamCallCredentials, device: Device?): Deferred<Unit> {
        return queueDeviceWork {
            try {
                deviceCleanupFailed = !unregisterPush(owned, c, device)
                failedCleanup = if (deviceCleanupFailed) Triple(owned, c, device) else null
            } finally { removeOwnedClient(owned) }
        }
    }

    private fun removeOwnedClient(owned: StreamVideo?) {
        if (owned != null && StreamVideo.instanceOrNull() === owned) StreamVideo.removeClient()
    }

    /** Stream's own record of the device it registered (its getDevice()), and this install's FCM token. */
    private var registeredDevice: Device? = null
    private var fcmToken: String? = null

    /**
     * "registered" only when Stream has stored a Firebase device for this install's current FCM
     * token under the configured provider; "pending" until then; "unavailable" with no Firebase or
     * with notifications off.
     */
    fun pushState(): String {
        if (!hasFirebase() || !StreamCallPlatform.canNotify(app)) return "unavailable"
        val c = credentials ?: return "pending"
        val d = registeredDevice ?: return "pending"
        val t = fcmToken ?: return "pending"
        return StreamCallRules.pushState(d.id, d.pushProvider, d.pushProviderName, t, PushProvider.FIREBASE.key, c.fcmProvider)
    }

    private suspend fun currentFcmToken(): String? {
        if (!hasFirebase()) return null
        val token = CompletableDeferred<String?>()
        FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
            token.complete(if (t.isSuccessful) t.result else null)
        }
        return withTimeoutOrNull(5_000) { token.await() }.also { fcmToken = it }
    }

    /** Registers this install's FCM token with Stream for the configured user; Stream records the result. */
    private suspend fun registerPush(cl: StreamVideo, session: TokenSession) {
        val token = currentFcmToken() ?: return
        queueDeviceWork {
            if (activeSession === session && client === cl) {
                try {
                    val result = cl.createDevice(PushDevice(token, PushProvider.FIREBASE, session.credentials.fcmProvider))
                    if (result !is Result.Success) log("Push device registration failed; it is retried at the next configure")
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    log("Push device registration failed; it is retried at the next configure")
                }
            }
        }.await()
    }

    fun setToken(token: String, requestId: String? = null): String? {
        val session = if (requestId != null) {
            listOfNotNull(activeSession, candidateSession).firstOrNull {
                it.valid && it.waiters.containsKey(requestId)
            } ?: return "token_request_expired"
        } else {
            activeSession?.takeIf { it.valid } ?: return "not_configured"
        }
        return if (storeToken(token, session)) null else "storage_failed"
    }

    private fun owns(session: TokenSession): Boolean {
        return session.valid && (activeSession === session || candidateSession === session)
    }

    private fun invalidate(session: TokenSession?) {
        if (session == null) return
        session.valid = false
        val waiters = session.waiters.values.toList()
        session.waiters.clear()
        waiters.forEach { it.complete(null) }
    }

    private fun storeToken(token: String, session: TokenSession): Boolean {
        if (!owns(session)) return false
        val next = session.credentials.withToken(token)
        if (activeSession === session) {
            if (!StreamCallCredentials.save(host.dataDir(), next)) return false
            credentials = next
        }
        session.credentials = next
        session.revision += 1
        val waiters = session.waiters.values.toList()
        session.waiters.clear()
        waiters.forEach { it.complete(token) }
        return true
    }

    fun logout(reply: Reply) {
        val live = ledger.live()
        if (live != null) endSdkCall(live)
        ledger.logout()
        if (live != null) emit("ended", JSONObject().put("call_cid", live.cid).put("reason", "logout"))
        val c = credentials ?: retiringCredentials
        val old = client ?: retiringClient
        val device = registeredDevice ?: retiringDevice
        val pending = candidateClient
        val pendingCredentials = candidateSession?.credentials
        configuration += 1
        invalidate(candidateSession)
        invalidate(activeSession)
        candidateSession = null
        candidateClient = null
        activeSession = null
        retiringClient = null
        retiringCredentials = null
        retiringDevice = null
        credentials = null
        StreamCallCredentials.delete(host.dataDir())
        watchers.forEach { it.cancel() }
        watchers.clear()
        unbind()
        client = null
        registeredDevice = null
        var cleanup = deviceBarrier
        if (old != null && c != null) cleanup = retire(old, c, device)
        if (pending != null && pending !== old && pendingCredentials != null) cleanup = retire(pending, pendingCredentials, null)
        failedCleanup?.let { retry ->
            if (retry.first !== old && retry.first !== pending) cleanup = retire(retry.first, retry.second, retry.third)
        }
        scope.launch {
            cleanup?.await()
            reply.done(if (deviceCleanupFailed) "storage_failed" else null, JSONObject())
        }
    }

    /** Server removal is best effort; awaited cache cleanup precedes replacement client creation. */
    private suspend fun unregisterPush(cl: StreamVideo, c: StreamCallCredentials, device: Device?): Boolean {
        var cleared = false
        try {
            val stored = device ?: cl.getDevice().firstOrNull()
            val owned = stored ?: currentFcmToken()?.let {
                Device(id = it, pushProvider = PushProvider.FIREBASE.key, pushProviderName = c.fcmProvider)
            }
            if (owned != null && cl.deleteDevice(owned) !is Result.Success) {
                log("Push device removal could not be confirmed by Stream")
            }
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            log("Push device removal could not be confirmed by Stream")
        } finally {
            removeOwnedClient(cl)
            try { cl.updateDevice(null); cleared = true }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                log("Push device cache cleanup failed")
            }
        }
        return cleared
    }

    /**
     * Token refresh, in this order: the developer's token_url when one was configured (it works
     * with no page loaded), then the page (token_needed, answered by set_token, 10 s); else the
     * SDK gets no token and the call ends (token_expired). The developer's server decides.
     */
    private suspend fun refreshToken(session: TokenSession): String? {
        if (!owns(session)) return null
        val revision = session.revision
        val live = ledger.live()
        val requestId = java.util.UUID.randomUUID().toString()
        val waiter = CompletableDeferred<String?>()
        session.waiters[requestId] = waiter
        val c = session.credentials
        val url = c.tokenUrl
        if (url != null && host.isApprovedTokenUrl(url)) {
            val fetched = withContext(Dispatchers.IO) { postForToken(url, c.userId, c.token) }
            if (owns(session) && session.waiters[requestId] === waiter && session.revision == revision && fetched != null) {
                storeToken(fetched, session)
            }
        }
        if (!waiter.isCompleted && owns(session) && session.revision == revision) {
            emit("token_needed", JSONObject().put("user_id", c.userId).put("request_id", requestId))
        }
        val token = withTimeoutOrNull(10_000) { waiter.await() }
        if (session.waiters[requestId] === waiter) session.waiters.remove(requestId)
        if (token == null && session.valid && activeSession === session && session.revision == revision &&
            live != null && ledger.live()?.epoch == live.epoch) {
            endSdkCall(live)
            endedFromSdk(live, "token_expired")
        }
        return token
    }

    /** POST {"user_id", "expired_token"} to the approved token_url; reply {"token"}. No redirects. */
    private fun postForToken(url: String, userId: String, expired: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(JSONObject().put("user_id", userId).put("expired_token", expired).toString().toByteArray())
            }
            if (conn.responseCode != 200) {
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("token").takeIf { it.isNotEmpty() && it.length <= 4096 }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- push (StreamPushReceiver)

    /** Hands one of Stream's FCM messages to Stream, exactly once; Stream rings it. */
    fun handlePush(extras: Bundle): Boolean {
        // Not configured: there is no client to ring with. Every call type already runs on the
        // audio service with Telecom (registry()), so nothing is registered per push.
        if (client == null) return false
        // A ring this app already showed from Stream's connection is not shown twice.
        val cid = extras.getString("call_cid")
        if (cid != null && extras.getString("type") == "call.ring") {
            pushCaller = Triple(cid, extras.getString("created_by_id") ?: "", extras.getString("created_by_display_name") ?: "")
        }
        if (cid != null && extras.getString("type") == "call.missed") missedPush = cid
        if (extras.getString("type") == "call.ring" && cid != null && !systemRings.claim(cid, SystemClock.elapsedRealtime())) return true
        return FirebaseMessagingDelegate.handleRemoteMessage(RemoteMessage(extras))
    }

    // ---------------------------------------------------------------- page commands

    fun join(type: String, id: String, create: Boolean, ring: Boolean, members: List<String>): StreamCallLedger.Outcome {
        val c = client ?: return ledger.join("$type:$id")
        if (ledger.live() == null && StreamCallPlatform.recordingActive(app)) return StreamCallLedger.Outcome.error("audio_busy")
        val outcome = ledger.join("$type:$id")
        val live = ledger.live()
        if (!outcome.isOk || live == null) return outcome
        val sdkCall = c.call(type, id)
        bind(sdkCall)
        emit("connecting", JSONObject().put("call_cid", live.cid).put("direction", "outgoing"))
        scope.launch {
            sdkCall.microphone.setEnabled(live.wantedMuted() != true)
            val r = sdkCall.join(
                create = create,
                createOptions = if (members.isEmpty()) null else CreateCallOptions(memberIds = members),
                ring = ring,
            )
            if (r is Result.Success) joinFinished(live, sdkCall) else endedFromSdk(live, "failed")
        }
        return outcome
    }

    private fun joinFinished(l: StreamCallLedger.Live, sdkCall: Call) {
        val o = ledger.connected(l.cid, l.epoch)
        if (o.isOk) {
            val wanted = ledger.live()?.wantedMuted()
            emit("joined", JSONObject().put("call_cid", l.cid).put("muted", wanted == true).put("audio_route", route(sdkCall)))
            if (wanted != null) applyMute(wanted, l.epoch)
        } else if (ledger.live()?.cid != l.cid) {
            // drop: the page left or this attempt is not current. Leave the SDK call, unless a newer
            // attempt for the same cid is live (Stream keeps one Call object per cid).
            sdkCall.leave()
        }
    }

    fun leave(): StreamCallLedger.Outcome {
        val l = ledger.live() ?: return StreamCallLedger.Outcome.error("no_call")
        systemRings.release(l.cid)
        val o = ledger.leave()
        endSdkCall(l)
        unbind()
        if (o.isOk) emit("ended", JSONObject().put("call_cid", l.cid).put("reason", o.value.removePrefix("ended:")))
        return o
    }

    /** Ringing: decline (reject); otherwise leave. Stream ends the Telecom call and the notification. */
    private fun endSdkCall(l: StreamCallLedger.Live) {
        val c = call ?: client?.let { cl ->
            val type = StreamCallRules.callType(l.cid) ?: return
            cl.call(type, StreamCallRules.callId(l.cid))
        } ?: return
        if (l.state() == StreamCallLedger.RINGING) {
            scope.launch {
                c.reject()
                c.leave()
            }
        } else {
            c.leave()
        }
    }

    fun setMuted(muted: Boolean): StreamCallLedger.Outcome {
        val o = ledger.setMuted(muted)
        val l = ledger.live()
        if (o.isOk && o.value == "apply" && l != null) applyMute(muted, l.epoch)
        return o
    }

    private fun applyMute(muted: Boolean, epoch: Int) {
        val c = call ?: return
        c.microphone.setEnabled(!muted)
        if (ledger.muteApplied(epoch, muted).isOk) emit("muted", JSONObject().put("call_cid", c.cid).put("muted", muted))
    }

    fun setSpeaker(on: Boolean): JSONObject? {
        val c = call ?: return null
        val state = ledger.live()?.state() ?: return null
        if (state == StreamCallLedger.RINGING) return null
        c.speaker.setSpeakerPhone(on)
        return JSONObject().put("speaker", on).put("audio_route", route(c))
    }

    fun status(): JSONObject {
        val l = ledger.live()
        return JSONObject()
            .put("configured", ledger.configured())
            .put("user_id", credentials?.userId ?: JSONObject.NULL)
            .put("state", ledger.status())
            .put("call_cid", l?.cid ?: JSONObject.NULL)
            .put("muted", l?.muted() ?: false)
            .put("speaker", call?.speaker?.isEnabled?.value ?: false)
            .put("audio_route", call?.let { route(it) } ?: "none")
            .put("push", pushState())
    }

    fun reset() {
        ledger.live()?.let { endSdkCall(it) }
        ledger.reset()
    }

    // ---------------------------------------------------------------- the lock-screen activity

    /** The call a CallIntentActivity intent names (Stream's own extra), or null. */
    fun callIdOf(intent: Intent): String? =
        IntentCompat.getParcelableExtra(intent, NotificationHandler.INTENT_EXTRA_CALL_CID, StreamCallId::class.java)?.cid

    fun callerNameOf(intent: Intent): String? =
        intent.getStringExtra(NotificationHandler.INTENT_EXTRA_CALL_DISPLAY_NAME)

    /** Answer in the system UI (notification Accept, full-screen Answer): accept, then join. */
    fun answer(cid: String) {
        val l = ledger.live() ?: return
        if (l.cid != cid) return
        val o = ledger.answered(cid)
        if (!o.isOk) return
        val type = StreamCallRules.callType(cid) ?: return
        val c = call ?: client?.call(type, StreamCallRules.callId(cid)) ?: return
        bind(c)
        emit("connecting", JSONObject().put("call_cid", cid).put("direction", "incoming"))
        scope.launch {
            val accepted = c.accept()
            if (accepted !is Result.Success) return@launch endedFromSdk(l, "failed")
            val joined = c.join()
            if (joined is Result.Success) joinFinished(l, c) else endedFromSdk(l, "failed")
        }
    }

    /** Decline from the full-screen screen. */
    fun decline(cid: String) {
        if (ledger.live()?.cid == cid) leave()
    }

    // ---------------------------------------------------------------- SDK state

    private fun ringingChanged(ringing: Call?) {
        // Stream stopped ringing a call this page does not hold (declined elsewhere, missed, or a ring
        // for a call already over): its system ring is gone, so the same cid rings again next time.
        pendingRing?.cancel()
        pendingRing = null
        val before = streamRinging
        streamRinging = ringing?.cid
        if (before != null && before != streamRinging && before != ledger.live()?.cid) systemRings.release(before)
        if (ringing == null) return
        // Stream sets ringingCall on call.ring before that call has handled the event, so its ringing
        // state can still be Idle here (ClientState.handleEvent, then CallState.updateRingingState,
        // 1.35.0). The ring is taken when the state leaves Idle, if it is Incoming and the call is
        // still Stream's ringing call in the same session; an outgoing ring (ours) is left alone. The
        // wait ends at the next ringingCall change, a new session, or RING_WAIT_MS.
        val session = activeSession
        pendingRing = scope.launch {
            val state = withTimeoutOrNull(RING_WAIT_MS) { ringing.state.ringingState.first { it !is RingingState.Idle } }
            if (state is RingingState.Incoming && streamRinging == ringing.cid && activeSession === session) incomingRing(ringing)
        }
    }

    private fun incomingRing(ringing: Call) {
        val o = ledger.incoming(ringing.cid)
        if (o.isOk) {
            if (missedPush == ringing.cid) missedPush = null
            bind(ringing)
            val by = ringing.state.createdBy.value
            val pushed = pushCaller?.takeIf { it.first == ringing.cid }
            val callerId = by?.id ?: pushed?.second ?: ""
            val callerName = by?.userNameOrId ?: pushed?.third?.ifEmpty { null } ?: callerId
            // Rung over Stream's connection (app running): Stream shows no system ring for that by
            // itself, so it is shown through Stream's own push path, exactly as for an FCM ring.
            val show = systemRings.claim(ringing.cid, SystemClock.elapsedRealtime())
            systemRings.ringing(ringing.cid)
            if (show) {
                client?.onRingingCall(StreamCallId(ringing.type, ringing.id), callerName, emptyMap())
            }
            emit("incoming", JSONObject().put("call_cid", ringing.cid)
                .put("caller_id", callerId).put("caller_name", callerName))
        } else if (o.value != "duplicate") {
            // Not this page's call, so its ring ends here and the same cid rings again next time.
            systemRings.release(ringing.cid)
            // One live call: the second ring is declined as busy, the way Stream reports it.
            if (o.value == "busy") scope.launch { ringing.reject() }
        }
    }

    private fun activeChanged(active: Call?) {
        val l = ledger.live() ?: return
        // The joined call is no longer Stream's active call. Ended by the other side (Stream's
        // call.ended sets endedAt before the SDK leaves): "remote"; hung up in the system UI or left by
        // the SDK: "local". Both as on iOS.
        if (l.state() != StreamCallLedger.JOINED || active?.cid == l.cid) return
        val remote = call?.takeIf { it.cid == l.cid }?.state?.endedAt?.value != null
        endedFromSdk(l, if (remote) "remote" else "local")
    }

    private fun bind(c: Call) {
        unbind()
        call = c
        val l = ledger.live() ?: return
        callWatchers = mutableListOf(
            scope.launch {
                c.state.ringingState.collect { rs ->
                    val now = ledger.live() ?: return@collect
                    if (now.epoch != l.epoch) return@collect
                    when (rs) {
                        is RingingState.RejectedByAll -> endedFromSdk(now, if (now.incoming) incomingEnd(c) else "rejected")
                        is RingingState.TimeoutNoAnswer -> endedFromSdk(now, "missed")
                        else -> Unit
                    }
                }
            },
            scope.launch {
                c.state.connection.collect { conn ->
                    val now = ledger.live() ?: return@collect
                    if (now.epoch != l.epoch) return@collect
                    if (conn is RealtimeConnection.Failed || conn is RealtimeConnection.ReconnectingFailed) endedFromSdk(now, "failed")
                }
            },
            scope.launch {
                c.microphone.selectedDevice.collect {
                    val now = ledger.live() ?: return@collect
                    if (now.epoch == l.epoch) emit("audio_route", JSONObject().put("call_cid", now.cid).put("audio_route", route(c)))
                }
            },
        )
    }

    /** Why Stream ended an incoming ring, as on iOS: Stream's missed push, this user's own rejection
     *  (here or on another device), else the caller (rejected or ended the call while it rang). */
    private fun incomingEnd(c: Call): String = when {
        missedPush == c.cid -> "missed"
        credentials?.userId in c.state.rejectedBy.value -> "declined"
        else -> "cancelled"
    }

    private fun unbind() {
        callWatchers.forEach { it.cancel() }
        callWatchers = mutableListOf()
        call = null
    }

    private fun endedFromSdk(l: StreamCallLedger.Live, reason: String) {
        systemRings.release(l.cid)
        if (!ledger.ended(l.cid, l.epoch, reason).isOk) return
        unbind()
        emit("ended", JSONObject().put("call_cid", l.cid).put("reason", reason))
    }

    /** The page's name for the selected device; other device kinds pass through by class name. */
    private fun route(c: Call): String = when (val d = c.microphone.selectedDevice.value) {
        null -> "none"
        is StreamAudioDevice.Earpiece -> "earpiece"
        is StreamAudioDevice.Speakerphone -> "speaker"
        is StreamAudioDevice.WiredHeadset -> "wired"
        is StreamAudioDevice.BluetoothHeadset -> "bluetooth"
        else -> d.javaClass.simpleName
    }

    private fun emit(event: String, data: JSONObject) = host.emit(event, data)

    private fun log(message: String) = host.log(message)

    private companion object {
        const val NO_TOKEN = ""
        const val RING_WAIT_MS = 60_000L
    }
}

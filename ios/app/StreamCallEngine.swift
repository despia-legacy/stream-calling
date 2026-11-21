import AVFAudio
import Combine
import Foundation
import StreamVideo

/// The only file of the template that talks to Stream (StreamVideo 1.57.0, product `StreamVideo`
/// only, no Stream UI). Main actor, like the extension that owns it.
///
/// Ownership, each one stated once:
/// - PushKit: the app's VoIP push handler owns the app's one PKPushRegistry and hands the token and every VoIP push to
///   the extension. The template never sets `CallKitAdapter.streamVideo` and never calls
///   `registerForIncomingCalls`, which would create Stream's own second registry.
/// - CallKit: Stream's `CallKitService` is the app's one CXProvider. The template gives it the
///   client and reports each push through it, so CallKit's audio activation reaches Stream's audio
///   stack through Stream's own delegate.
/// - Audio session: the template never configures, activates or deactivates it. CallKit and Stream
///   do; the template only reads it (release only what you activated, and this file activates
///   nothing).
@MainActor
final class StreamCallEngine {
    /// Page events, without `seq` (the extension adds it).
    var emit: (String, [String: Any]) -> Void = { _, _ in }
    var log: (String) -> Void = { _ in }
    /// The bridge's https rule for a stored URL (NativeExtContext.isApprovedURL for configure.token_url).
    var isApprovedTokenURL: (String) -> Bool = { _ in false }

    private(set) var ledger = StreamCallLedger()
    private(set) var credentials: StreamCallCredentials?
    private var client: StreamVideo?
    /// The SDK call object of the live ledger call.
    private var call: Call?
    private var subscriptions = Set<AnyCancellable>()
    /// The PushKit token as Stream registers it; nil until PushKit gives one.
    private var voipToken: String?
    private var voipInvalidated = false
    private final class TokenSession {
        var credentials: StreamCallCredentials
        var valid = true
        var revision: UInt64 = 0
        var waiters: [String: (String?) -> Void] = [:]
        init(_ credentials: StreamCallCredentials) { self.credentials = credentials }
    }
    private var activeSession: TokenSession?
    private var candidateSession: TokenSession?
    private var routeObserver: NSObjectProtocol?

    @Injected(\.callKitService) private var callKit
    /// Used only for its `callSettings` (audio on, video off) and the CallKit appearance; its
    /// `streamVideo` is never set (that would register a second PushKit registry).
    @Injected(\.callKitAdapter) private var callKitAdapter

    // MARK: - Launch and credentials

    /// Process start, including a background launch for a VoIP push: rebuild the client from what
    /// `configure` stored, so the push that woke the app can be reported and answered.
    func restore() {
        callKitAdapter.callSettings = CallSettings(audioOn: true, videoOn: false, speakerOn: false)
        callKit.supportsVideo = false
        routeObserver = NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification,
                                                               object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.routeChanged() }
        }
        guard let stored = StreamCallKeychain.load() else { return }
        credentials = stored
        let session = TokenSession(stored)
        activeSession = session
        attach(build(session), session: session)
        _ = ledger.configure()
    }

    private func build(_ session: TokenSession) -> StreamVideo {
        let c = session.credentials
        return StreamVideo(apiKey: c.apiKey,
                    user: User(id: c.userId, name: c.userName),
                    token: UserToken(rawValue: c.token),
                    pushNotificationsConfig: .make(pushProviderName: "ios-apn", voipProviderName: c.voipProvider),
                    tokenProvider: { [weak self] done in
                        Task { @MainActor in
                            guard let self else { return done(.failure(StreamCallError.notConfigured)) }
                            self.refreshToken(session) { token in
                                if let token { done(.success(UserToken(rawValue: token))) } else { done(.failure(StreamCallError.tokenExpired)) }
                            }
                        }
                    })
    }

    private func attach(_ c: StreamVideo, session: TokenSession) {
        subscriptions.removeAll()
        client = c
        callKit.streamVideo = c
        c.eventPublisher()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] event in
                MainActor.assumeIsolated {
                    guard let self, self.activeSession === session else { return }
                    self.handle(event)
                }
            }
            .store(in: &subscriptions)
        c.state.$activeCall
            .receive(on: DispatchQueue.main)
            .sink { [weak self] active in
                MainActor.assumeIsolated {
                    guard let self, self.activeSession === session else { return }
                    self.activeCallChanged(active)
                }
            }
            .store(in: &subscriptions)
        callKit.eventPipeline
            .receive(on: DispatchQueue.main)
            .sink { [weak self] event in
                MainActor.assumeIsolated {
                    guard let self, self.activeSession === session else { return }
                    self.callKitEvent(event)
                }
            }
            .store(in: &subscriptions)
    }

    /// Stores the credentials and connects. Replies with the push state; throws a code.
    func configure(_ c: StreamCallCredentials, isActive: @escaping () -> Bool = { true }) async throws -> String {
        if ledger.live != nil { throw StreamCallError.busy }
        var next = c
        if credentials?.userId == c.userId { next.registeredVoipToken = credentials?.registeredVoipToken }
        invalidate(candidateSession)
        let session = TokenSession(next)
        candidateSession = session
        let fresh = build(session)
        do {
            try await fresh.connect()
        } catch {
            let current = candidateSession === session && session.valid
            if current { candidateSession = nil }
            invalidate(session)
            await fresh.disconnect()
            throw current ? StreamCallError.connectFailed : StreamCallError.sessionChanged
        }
        guard candidateSession === session, session.valid else {
            await fresh.disconnect()
            throw StreamCallError.sessionChanged
        }
        guard isActive() else {
            candidateSession = nil
            invalidate(session)
            await fresh.disconnect()
            throw StreamCallError.timedOut
        }
        if ledger.live != nil {
            candidateSession = nil
            invalidate(session)
            await fresh.disconnect()
            throw StreamCallError.busy
        }
        guard StreamCallKeychain.save(session.credentials) else {
            candidateSession = nil
            invalidate(session)
            await fresh.disconnect()
            throw StreamCallError.storageFailed
        }
        let previous = credentials
        let old = client
        invalidate(activeSession)
        activeSession = session
        candidateSession = nil
        credentials = session.credentials
        attach(fresh, session: session)
        _ = ledger.configure()
        if let old, old !== fresh {
            if previous?.userId != session.credentials.userId, let registered = previous?.registeredVoipToken {
                _ = try? await old.deleteDevice(id: registered)
            }
            await old.disconnect()
        }
        guard activeSession === session else { throw StreamCallError.sessionChanged }
        await registerVoipToken()
        guard activeSession === session else { throw StreamCallError.sessionChanged }
        return pushState
    }

    func setToken(_ token: String, requestId: String? = nil) throws {
        let session: TokenSession
        if let requestId {
            guard let owner = [activeSession, candidateSession].compactMap({ $0 }).first(where: {
                $0.valid && $0.waiters[requestId] != nil
            }) else { throw StreamCallError.tokenRequestExpired }
            session = owner
        } else {
            guard let active = activeSession, active.valid else { throw StreamCallError.notConfigured }
            session = active
        }
        guard storeToken(token, session: session) else { throw StreamCallError.storageFailed }
    }

    private func invalidate(_ session: TokenSession?) {
        guard let session else { return }
        session.valid = false
        let waiters = Array(session.waiters.values)
        session.waiters.removeAll()
        waiters.forEach { $0(nil) }
    }

    private func owns(_ session: TokenSession) -> Bool {
        session.valid && (activeSession === session || candidateSession === session)
    }

    private func storeToken(_ token: String, session: TokenSession) -> Bool {
        guard owns(session) else { return false }
        var next = session.credentials
        next.token = token
        if activeSession === session {
            guard StreamCallKeychain.save(next) else { return false }
            credentials = next
        }
        session.credentials = next
        session.revision += 1
        let waiters = Array(session.waiters.values)
        session.waiters.removeAll()
        waiters.forEach { $0(token) }
        return true
    }

    func logout() async {
        let live = ledger.live
        if let l = live { endSDKCall(l) }
        _ = ledger.logout()
        if let l = live { emit("ended", ["call_cid": l.cid, "reason": "logout"]) }
        invalidate(candidateSession)
        invalidate(activeSession)
        candidateSession = nil
        activeSession = nil
        call = nil
        subscriptions.removeAll()
        let c = client
        client = nil
        callKit.streamVideo = nil
        let registered = credentials?.registeredVoipToken
        credentials = nil
        StreamCallKeychain.delete()
        if let c {
            if let registered { _ = try? await c.deleteDevice(id: registered) }
            await c.disconnect()
        }
    }

    /// Token refresh, in this order: the developer's `token_url` when one was configured (it works
    /// with no page loaded), then the page (`token_needed`, answered by `set_token`, 10 s), else the
    /// call ends with `token_expired`. The developer's server decides whether to issue a token.
    private func refreshToken(_ session: TokenSession, _ reply: @escaping (String?) -> Void) {
        guard owns(session) else { return reply(nil) }
        let revision = session.revision
        let live = ledger.live
        let requestId = UUID().uuidString
        var finished = false
        let finish: (String?) -> Void = { [weak self] token in
            guard !finished else { return }
            finished = true
            session.waiters.removeValue(forKey: requestId)
            if token == nil, let self, session.valid, self.activeSession === session,
               session.revision == revision, let live, self.ledger.live?.epoch == live.epoch {
                self.endSDKCall(live)
                self.endedFromSDK(live, reason: "token_expired")
            }
            reply(token)
        }
        session.waiters[requestId] = finish
        let c = session.credentials
        Task { @MainActor in
            if let url = c.tokenURL, isApprovedTokenURL(url),
               let token = await StreamCallTokenFetch.post(url, userId: c.userId, expired: c.token) {
                guard owns(session), session.waiters[requestId] != nil, session.revision == revision else { return }
                if storeToken(token, session: session) { return }
            }
            guard owns(session), session.waiters[requestId] != nil, session.revision == revision else { return }
            emit("token_needed", ["user_id": c.userId, "request_id": requestId])
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            finish(nil)
        }
    }

    var pushState: String {
        if voipInvalidated { return "unavailable" }
        guard let t = voipToken else { return "pending" }
        return credentials?.registeredVoipToken == t ? "registered" : "pending"
    }

    // MARK: - VoIP push (the app's VoIP push handler calls these through the extension)

    func didUpdateVoIPToken(_ token: Data?) {
        voipInvalidated = token == nil
        voipToken = token.map(StreamCallRules.hexToken)
        Task { await registerVoipToken() }
    }

    private func registerVoipToken() async {
        guard let c = client, let session = activeSession else { return }
        let old = session.credentials.registeredVoipToken
        guard let token = voipToken else {
            if voipInvalidated, let old {
                _ = try? await c.deleteDevice(id: old)
                guard activeSession === session, client === c else { return }
                var next = session.credentials
                next.registeredVoipToken = nil
                if StreamCallKeychain.save(next) { session.credentials = next; credentials = next }
            }
            return
        }
        guard token != old else { return }
        do { try await c.setVoipDevice(id: token) }
        catch {
            log("VoIP device registration failed; it is retried at the next configure or token update")
            return
        }
        guard activeSession === session, client === c else { return }
        if let old { _ = try? await c.deleteDevice(id: old) }
        guard activeSession === session, client === c else { return }
        var next = session.credentials
        next.registeredVoipToken = token
        if StreamCallKeychain.save(next) { session.credentials = next; credentials = next }
    }

    /// Reports the push to CallKit through Stream's provider in this same main-queue turn, and runs
    /// PushKit's completion only inside CallKit's completion. There is no path that skips the report:
    /// with no client Stream still reports, then ends the call (`notConfigured`).
    func reportIncoming(_ payload: [AnyHashable: Any], completion: @escaping () -> Void) {
        let push = StreamCallRules.Push(payload)
        let tracked = push.isCallCid ? ledger.incoming(push.cid) : .error("bad_request")
        let once = StreamCallOnce(completion)
        callKit.reportIncomingCall(push.cid, localizedCallerName: push.callerName, callerId: push.callerId,
                                   hasVideo: false) { [weak self] error in
            once.run()
            Task { @MainActor in self?.reported(push, tracked: tracked, error: error) }
        }
    }

    private func reported(_ push: StreamCallRules.Push, tracked: StreamCallLedger.Outcome, error: Error?) {
        switch tracked {
        case .ok:
            guard let l = ledger.live, l.cid == push.cid else { return }
            if error != nil {
                endedFromSDK(l, reason: "failed")
                return
            }
            if let parts = StreamCallRules.splitCid(push.cid), let c = client {
                call = c.call(callType: parts.type, callId: parts.id)
            }
            emit("incoming", ["call_cid": push.cid, "caller_id": push.callerId, "caller_name": push.callerName])
        case .error("busy"):
            // One live call: the push was reported (Apple's duty), now it is declined as busy.
            if error == nil { callKit.callEnded(push.cid, ringingTimedOut: false, leaveReason: "busy") }
        default:
            // duplicate (Stream reuses its entry for the same cid), not configured (Stream already
            // ended it as notConfigured) or a payload with no call: nothing more to do.
            break
        }
    }

    // MARK: - Page commands

    /// `audio_busy` when a recording holds the session; else the ledger's answer. The SDK join runs
    /// after the reply; its outcome arrives as `joined` or `ended`.
    func join(type: String, id: String, create: Bool, ring: Bool, members: [String]) -> StreamCallLedger.Outcome {
        guard let c = client else { return .error("not_configured") }
        let session = AVAudioSession.sharedInstance()
        if ledger.live == nil,
           !StreamCallRules.mayJoin(category: session.category.rawValue, recordCategory: AVAudioSession.Category.record.rawValue) {
            return .error("audio_busy")
        }
        let cid = "\(type):\(id)"
        let outcome = ledger.join(cid)
        guard case .ok = outcome, let l = ledger.live else { return outcome }
        let sdkCall = c.call(callType: type, callId: id,
                             callSettings: CallSettings(audioOn: true, videoOn: false, speakerOn: false))
        call = sdkCall
        emit("connecting", ["call_cid": cid, "direction": "outgoing"])
        let options = members.isEmpty ? nil : CreateCallOptions(memberIds: members)
        Task { @MainActor in
            do {
                _ = try await sdkCall.join(create: create, options: options, ring: ring)
                self.joinFinished(l, sdkCall)
            } catch {
                self.endedFromSDK(l, reason: "failed")
            }
        }
        return outcome
    }

    private func joinFinished(_ l: StreamCallLedger.Live, _ sdkCall: Call) {
        switch ledger.connected(l.cid, epoch: l.epoch) {
        case .ok:
            let wanted = ledger.live?.wantedMuted
            emit("joined", ["call_cid": l.cid, "muted": wanted ?? false, "audio_route": currentRoute])
            if let wanted { applyMute(wanted, epoch: l.epoch) }
        default:
            // drop: the page left, or this attempt is not current. Leave the SDK call, unless a newer
            // attempt for the same cid is live (Stream caches one Call object per cid).
            if ledger.live?.cid != l.cid { sdkCall.leave() }
        }
    }

    func leave() -> StreamCallLedger.Outcome {
        guard let l = ledger.live else { return .error("no_call") }
        let outcome = ledger.leave()
        endSDKCall(l)
        call = nil
        if case .ok(let detail) = outcome {
            emit("ended", ["call_cid": l.cid, "reason": String(detail.dropFirst("ended:".count))])
        }
        return outcome
    }

    /// A call CallKit knows (every incoming call) ends through CallKit, which rejects a ringing call
    /// and leaves an active one; a page-started call is left directly.
    private func endSDKCall(_ l: StreamCallLedger.Live) {
        if l.incoming {
            callKit.callEnded(l.cid, ringingTimedOut: false)
        } else {
            call?.leave()
        }
    }

    func setMuted(_ muted: Bool) -> StreamCallLedger.Outcome {
        let outcome = ledger.setMuted(muted)
        if outcome == .ok("apply"), let l = ledger.live { applyMute(muted, epoch: l.epoch) }
        return outcome
    }

    private func applyMute(_ muted: Bool, epoch: Int) {
        guard let c = call else { return }
        Task { @MainActor in
            do {
                if muted { try await c.microphone.disable() } else { try await c.microphone.enable() }
            } catch {
                emit("error", ["code": "mute_failed", "message": "the microphone could not be changed",
                               "call_cid": c.cId])
                return
            }
            if case .ok = ledger.muteApplied(epoch: epoch, muted: muted) {
                emit("muted", ["call_cid": c.cId, "muted": muted])
            }
        }
    }

    /// Through the SDK only; the reply carries the route the session reports afterwards.
    func setSpeaker(_ on: Bool) async throws -> [String: Any] {
        guard let c = call, ledger.live?.state == .joined || ledger.live?.state == .connecting else { throw StreamCallError.noCall }
        do {
            if on { try await c.speaker.enableSpeakerPhone() } else { try await c.speaker.disableSpeakerPhone() }
        } catch {
            throw StreamCallError.routeFailed
        }
        return ["speaker": c.speaker.status == .enabled, "audio_route": currentRoute]
    }

    func status() -> [String: Any] {
        var out: [String: Any] = ["configured": ledger.configured, "state": ledger.status, "push": pushState,
                                  "audio_route": currentRoute]
        out["user_id"] = credentials?.userId ?? NSNull()
        out["call_cid"] = ledger.live?.cid ?? NSNull()
        out["muted"] = ledger.live?.muted ?? false
        out["speaker"] = call?.speaker.status == .enabled
        return out
    }

    func terminate() {
        if let l = ledger.live { endSDKCall(l) }
        _ = ledger.reset()
    }

    // MARK: - SDK and system state

    private func callKitEvent(_ event: CallKitService.Event) {
        guard let l = ledger.live, l.incoming else { return }
        switch event {
        case .accept:
            if case .ok = ledger.answered(l.cid) {
                emit("connecting", ["call_cid": l.cid, "direction": "incoming"])
            }
        case .joining(let c):
            if c.cId == l.cid { call = c }
        case .joined:
            if let c = call, c.cId == l.cid { joinFinished(l, c) }
        case .reject:
            endedFromSDK(l, reason: "declined")
        case .idle:
            break
        }
    }

    private func handle(_ event: VideoEvent) {
        guard let l = ledger.live else { return }
        switch event {
        case .typeCallEndedEvent(let e) where e.callCid == l.cid:
            endedFromSDK(l, reason: l.state == .ringing && l.incoming ? "cancelled" : "remote")
        case .typeCallAcceptedEvent(let e) where e.callCid == l.cid && l.incoming && l.state == .ringing
            && e.user.id == credentials?.userId:
            endedFromSDK(l, reason: "answered_elsewhere")
        case .typeCallRejectedEvent(let e) where e.callCid == l.cid:
            if l.incoming, l.state == .ringing {
                endedFromSDK(l, reason: e.user.id == credentials?.userId ? "declined" : "cancelled")
            } else if !l.incoming, l.state != .joined {
                // Ringing an outgoing call: every rejection is a member's; the caller stays until
                // the SDK ends the call or the page leaves.
                emit("error", ["code": "rejected", "message": "a member declined the call", "call_cid": l.cid])
            }
        case .typeCallMissedEvent(let e) where e.callCid == l.cid && l.incoming:
            endedFromSDK(l, reason: "missed")
        default:
            break
        }
    }

    private func activeCallChanged(_ active: Call?) {
        guard let l = ledger.live, l.state == .joined, active?.cId != l.cid else { return }
        // The joined call is no longer the SDK's active call: hung up in the system UI or left by
        // the SDK (a remote end arrives first as a call.ended event and is handled there).
        endedFromSDK(l, reason: "local")
    }

    private func endedFromSDK(_ l: StreamCallLedger.Live, reason: String) {
        guard case .ok = ledger.ended(l.cid, epoch: l.epoch, reason: reason) else { return }
        call = nil
        emit("ended", ["call_cid": l.cid, "reason": reason])
    }

    private func routeChanged() {
        guard ledger.live != nil else { return }
        emit("audio_route", ["call_cid": ledger.live?.cid ?? "", "audio_route": currentRoute])
    }

    /// Read only: reading the route is not ownership.
    var currentRoute: String {
        guard let port = AVAudioSession.sharedInstance().currentRoute.outputs.first?.portType else { return "none" }
        return StreamCallRules.routeName(portType: port.rawValue,
                                         builtInReceiver: AVAudioSession.Port.builtInReceiver.rawValue,
                                         builtInSpeaker: AVAudioSession.Port.builtInSpeaker.rawValue,
                                         headphones: AVAudioSession.Port.headphones.rawValue,
                                         bluetooth: [AVAudioSession.Port.bluetoothHFP.rawValue,
                                                     AVAudioSession.Port.bluetoothA2DP.rawValue,
                                                     AVAudioSession.Port.bluetoothLE.rawValue])
    }
}

enum StreamCallError: Error {
    case notConfigured, tokenExpired, busy, connectFailed, storageFailed, noCall, routeFailed, sessionChanged, tokenRequestExpired, timedOut

    var code: String {
        switch self {
        case .notConfigured: return "not_configured"
        case .tokenExpired: return "token_expired"
        case .busy: return "busy"
        case .connectFailed: return "connect_failed"
        case .storageFailed: return "storage_failed"
        case .noCall: return "no_call"
        case .routeFailed: return "route_failed"
        case .sessionChanged: return "session_changed"
        case .tokenRequestExpired: return "token_request_expired"
        case .timedOut: return "timeout"
        }
    }
}

/// PushKit's completion, run exactly once from CallKit's completion (which may arrive on any queue).
final class StreamCallOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var body: (() -> Void)?

    init(_ body: @escaping () -> Void) { self.body = body }

    func run() {
        lock.lock()
        let b = body
        body = nil
        lock.unlock()
        b?()
    }
}

/// The optional token refresh endpoint: POST {"user_id", "expired_token"}, reply {"token"}. An
/// ephemeral session that refuses every redirect, so the expired token never leaves the approved host.
enum StreamCallTokenFetch {
    private final class NoRedirect: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    static func post(_ url: String, userId: String, expired: String) async -> String? {
        guard let u = URL(string: url), u.scheme == "https",
              let body = try? JSONSerialization.data(withJSONObject: ["user_id": userId, "expired_token": expired]) else { return nil }
        var req = URLRequest(url: u, timeoutInterval: 10)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        let session = URLSession(configuration: .ephemeral, delegate: NoRedirect(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        guard let (data, resp) = try? await session.data(for: req), (resp as? HTTPURLResponse)?.statusCode == 200,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = obj["token"] as? String, !token.isEmpty, token.count <= 4096 else { return nil }
        return token
    }
}

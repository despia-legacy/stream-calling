import AVFAudio
import Foundation
import UserNotifications

/// Stream audio calling for a V3 app: a template the app's developer owns. Audio only; the call
/// screen is the system's own (CallKit). The page starts or joins a call, leaves it, mutes and
/// switches between earpiece and loudspeaker; incoming calls ring through a VoIP push and CallKit,
/// also with the app closed.
///
/// No key or token is built into the app: `configure` takes the API key, the user and a token the
/// developer's own server issued. They are stored in the Keychain so a VoIP push can be answered
/// with no page loaded, and `logout` removes them. The token never appears in a reply, an event or
/// a log line.
///
/// This is the app's one VoIP owner (`claims.voip`): the app's VoIP push handler calls
/// `didUpdateVoIPToken` and `didReceiveIncomingVoIPPush`, and every push is reported to CallKit
/// before its completion runs. It builds only with the dashboard's built-in Stream option off
/// (the build checks that an app has one VoIP owner).
///
/// Each command answers once. `join` answers as soon as the call is accepted into the ledger; the
/// outcome arrives as `joined` or `ended`. After every await, `call.isActive` is checked before a
/// new side effect. Events carry `call_cid` and `seq` (a counter that only grows); `status` is the
/// truth, since an event sent while no page is loaded is dropped.
///
/// Main actor, as NativeExtension requires (the build checks it).
@MainActor
final class StreamCallExtension: NativeExtension, NativeExtVoIPHandler {
    private var ctx: NativeExtContext!
    private let engine = StreamCallEngine()
    private var seq = 0

    required init() {}

    func didLaunch(_ context: NativeExtContext) {
        ctx = context
        engine.emit = { [weak self] name, data in self?.send(name, data) }
        engine.log = { [weak self] message in self?.ctx.log(message) }
        engine.isApprovedTokenURL = { [weak self] url in
            self?.ctx.isApprovedURL(url, route: "configure", param: "token_url") ?? false
        }
        engine.restore()
    }

    func willTerminate() {
        engine.terminate()
    }

    private func send(_ name: String, _ data: [String: Any]) {
        seq += 1
        var payload = data
        payload["seq"] = seq
        ctx.emit(name, payload)
    }

    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        switch call.route {
        case "configure": return await configure(call)
        case "set_token": return setToken(call)
        case "logout":
            await engine.logout()
            return .ok([:])
        case "permissions": return await permissions(call)
        case "join": return join(call)
        case "leave":
            let cid = engine.ledger.live?.cid
            switch engine.leave() {
            case .ok: return .ok(["call_cid": cid ?? "", "state": "ended"])
            case .error(let code): return .error(code: code, message: StreamCallRules.message(code))
            case .drop, .ignored: return .error(code: "failed", message: "the call was not changed")
            }
        case "set_muted":
            guard let muted = call.params["muted"] as? Bool else { return .error(code: "bad_request", message: "muted is required") }
            switch engine.setMuted(muted) {
            case .error(let code): return .error(code: code, message: "there is no call")
            default: return .ok(["muted": muted, "call_cid": engine.ledger.live?.cid ?? ""])
            }
        case "set_speaker": return await setSpeaker(call)
        case "status": return .ok(engine.status())
        default: return .error(code: "not_registered", message: call.route)
        }
    }

    private func configure(_ call: NativeExtCall) async -> NativeExtResult {
        guard let apiKey = call.params["api_key"] as? String, StreamCallRules.isName(apiKey),
              let userId = call.params["user_id"] as? String, StreamCallRules.isUserId(userId),
              let token = call.params["token"] as? String, !token.isEmpty else {
            return .error(code: "bad_request", message: "api_key, user_id and token are required")
        }
        let provider = call.params["voip_provider"] as? String ?? "ios-voip"
        guard StreamCallRules.isName(provider) else { return .error(code: "bad_request", message: "voip_provider is malformed") }
        let creds = StreamCallCredentials(apiKey: apiKey, userId: userId, userName: call.params["user_name"] as? String,
                                          token: token, tokenURL: call.params["token_url"] as? String,
                                          voipProvider: provider, registeredVoipToken: nil)
        do {
            let push = try await engine.configure(creds)
            guard call.isActive else { return .error(code: "timeout", message: "configure completed after the route timed out") }
            return .ok(["user_id": userId, "push": push])
        } catch let e as StreamCallError {
            return .error(code: e == .connectFailed ? "connect_failed" : e.code, message: "the Stream client was not configured")
        } catch {
            return .error(code: "connect_failed", message: "the Stream client was not configured")
        }
    }

    private func setToken(_ call: NativeExtCall) -> NativeExtResult {
        guard let token = call.params["token"] as? String, !token.isEmpty else {
            return .error(code: "bad_token", message: "token is required")
        }
        do {
            try engine.setToken(token, requestId: call.params["request_id"] as? String)
            return .ok([:])
        } catch let e as StreamCallError {
            return .error(code: e.code, message: "the token was not stored")
        } catch {
            return .error(code: "storage_failed", message: "the token was not stored")
        }
    }

    private func permissions(_ call: NativeExtCall) async -> NativeExtResult {
        let session = AVAudioSession.sharedInstance()
        if call.params["request"] as? Bool == true, session.recordPermission == .undetermined {
            _ = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
                session.requestRecordPermission { c.resume(returning: $0) }
            }
            guard call.isActive else { return .error(code: "timeout", message: "the permission answer came after the route timed out") }
        }
        let mic: String
        switch session.recordPermission {
        case .granted: mic = "granted"
        case .denied: mic = "denied"
        default: mic = "undetermined"
        }
        // CallKit rings without notification permission; it is reported, never requested here.
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard call.isActive else { return .error(code: "timeout", message: "the permission read came after the route timed out") }
        let notes: String
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: notes = "granted"
        case .denied: notes = "denied"
        default: notes = "undetermined"
        }
        return .ok(["microphone": mic, "notifications": notes])
    }

    private func join(_ call: NativeExtCall) -> NativeExtResult {
        guard let type = call.params["call_type"] as? String, StreamCallRules.isName(type),
              let id = call.params["call_id"] as? String, StreamCallRules.isName(id) else {
            return .error(code: "bad_request", message: "call_type and call_id are letters, digits, '_' or '-'")
        }
        let ring = call.params["ring"] as? Bool ?? false
        guard let members = StreamCallRules.members(call.params["members"] as? String ?? ""), !ring || !members.isEmpty else {
            return .error(code: "bad_request", message: "members is a comma-separated list of user ids; ring needs at least one")
        }
        if AVAudioSession.sharedInstance().recordPermission == .denied {
            return .error(code: "permission_denied", message: "microphone access is denied")
        }
        return reply(engine.join(type: type, id: id, create: call.params["create"] as? Bool ?? false, ring: ring, members: members),
                     ["state": "connecting"])
    }

    private func setSpeaker(_ call: NativeExtCall) async -> NativeExtResult {
        guard let on = call.params["on"] as? Bool else { return .error(code: "bad_request", message: "on is required") }
        do {
            let out = try await engine.setSpeaker(on)
            guard call.isActive else { return .error(code: "timeout", message: "the route changed after the call timed out") }
            return .ok(out)
        } catch let e as StreamCallError {
            return .error(code: e.code, message: e == .noCall ? "there is no call" : "the audio route could not be changed")
        } catch {
            return .error(code: "route_failed", message: "the audio route could not be changed")
        }
    }

    private func reply(_ outcome: StreamCallLedger.Outcome, _ data: [String: Any]) -> NativeExtResult {
        switch outcome {
        case .ok:
            var out = data
            out["call_cid"] = engine.ledger.live?.cid ?? NSNull()
            return .ok(out)
        case .error(let code): return .error(code: code, message: StreamCallRules.message(code))
        case .drop, .ignored: return .error(code: "failed", message: "the call was not changed")
        }
    }

    // MARK: - NativeExtVoIPHandler (called by the app's VoIP push handler on the main queue)

    func didUpdateVoIPToken(_ token: Data?) {
        engine.didUpdateVoIPToken(token)
    }

    /// Every push is reported to CallKit, through Stream's provider, before `completion` runs; the
    /// completion runs inside CallKit's completion. No branch skips the report.
    func didReceiveIncomingVoIPPush(_ payload: [AnyHashable: Any], completion: @escaping () -> Void) {
        engine.reportIncoming(payload, completion: completion)
    }
}

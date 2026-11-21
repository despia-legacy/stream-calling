import Foundation

/// Pure rules of the Stream calling template (Foundation only, so they can be tested on their own
/// with swiftc). Nothing here touches the network, the audio session
/// or CallKit.
enum StreamCallRules {
    /// A Stream VoIP push, decoded with Stream's own keys and fallbacks
    /// (StreamVideo 1.57.0, CallKit/CallKitPushNotificationAdapter.swift `decodePayload`), so the
    /// template reports exactly what Stream's own adapter would report.
    struct Push: Equatable {
        let cid: String
        let callerName: String
        let callerId: String

        static let unknownText = "Unknown Caller"

        init(cid: String, callerName: String, callerId: String) {
            self.cid = cid
            self.callerName = callerName
            self.callerId = callerId
        }

        init(_ payload: [AnyHashable: Any]) {
            guard let stream = payload["stream"] as? [String: Any] else {
                self.init(cid: "unknown", callerName: Push.unknownText, callerId: Push.unknownText)
                return
            }
            let display = stream["call_display_name"] as? String ?? ""
            self.init(cid: stream["call_cid"] as? String ?? "unknown",
                      callerName: display.isEmpty ? (stream["created_by_display_name"] as? String ?? Push.unknownText) : display,
                      callerId: stream["created_by_id"] as? String ?? Push.unknownText)
        }

        /// A cid the page can name: "<type>:<id>" with both parts non-empty.
        var isCallCid: Bool { StreamCallRules.splitCid(cid) != nil }
    }

    /// "<type>:<id>" into its parts, or nil.
    static func splitCid(_ cid: String) -> (type: String, id: String)? {
        guard let i = cid.firstIndex(of: ":") else { return nil }
        let type = String(cid[..<i]), id = String(cid[cid.index(after: i)...])
        guard !type.isEmpty, !id.isEmpty else { return nil }
        return (type, id)
    }

    /// The PushKit token as Stream registers it: lower-case hex, two digits per byte
    /// (CallKitPushNotificationAdapter.swift, `didUpdate pushCredentials`).
    static func hexToken(_ token: Data) -> String {
        token.map { String(format: "%02x", $0) }.joined()
    }

    /// Whether a page-started join may proceed, judged from the audio session configuration that is
    /// in place now (the only in-process witness). The recorder template records
    /// under the `record` category; joining would reconfigure the session under a recording in flight,
    /// so the join is refused with `audio_busy`. Every other configuration, the process default and a
    /// call's own included, is allowed: the template never takes the session from anyone else, it
    /// simply never configures it (Stream and CallKit do).
    static func mayJoin(category: String, recordCategory: String) -> Bool {
        category != recordCategory
    }

    /// The page's name for an audio output port. Platform port names not listed here pass through
    /// unchanged, so the list stays open.
    static func routeName(portType: String, builtInReceiver: String, builtInSpeaker: String,
                          headphones: String, bluetooth: [String]) -> String {
        if portType == builtInReceiver { return "earpiece" }
        if portType == builtInSpeaker { return "speaker" }
        if portType == headphones { return "wired" }
        if bluetooth.contains(portType) { return "bluetooth" }
        return portType
    }

    /// A call type, call id, API key or provider name: 1 to 64 of letters, digits, '_' and '-'
    /// (the characters Stream accepts in call ids and call type names).
    static func isName(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 64 && s.unicodeScalars.allSatisfy { CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-").contains($0) }
    }

    /// A Stream user id: 1 to 128 of letters, digits, '@', '_' and '-'.
    static func isUserId(_ s: String) -> Bool {
        !s.isEmpty && s.count <= 128 && s.unicodeScalars.allSatisfy { CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@_-").contains($0) }
    }

    /// The `members` parameter, comma-separated user ids, at most 32; nil when one is malformed.
    static func members(_ raw: String) -> [String]? {
        if raw.isEmpty { return [] }
        let ids = raw.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        guard ids.count <= 32, ids.allSatisfy(isUserId) else { return nil }
        return ids
    }

    /// The message that goes with one of the template's error codes.
    static func message(_ code: String) -> String {
        switch code {
        case "not_configured": return "call configure first"
        case "busy": return "another call is live"
        case "no_call": return "there is no call"
        case "audio_busy": return "a recording holds the audio session"
        default: return "the call was not changed"
        }
    }
}

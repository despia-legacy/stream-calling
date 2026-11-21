import Foundation

/// The call state machine of the Stream calling template. Pure: Foundation only, so it can be tested
/// on its own with swiftc against the same state table as the Java StreamCallLedger. One call is live at a time (Stream holds one active call; its CallKit provider has one call
/// group). Every attempt gets an epoch from a counter the ledger owns, and every asynchronous result
/// carries the epoch it was started under, so a result that arrives after `leave`, or for an earlier
/// attempt of the same call id, never changes the current call. Main actor only.
struct StreamCallLedger {
    enum State: String {
        case ringing, connecting, joined
    }

    enum Outcome: Equatable {
        case ok(String)
        case error(String)
        /// A join result for an attempt that is no longer current: the engine leaves that SDK call.
        case drop
        /// A late callback that changes nothing.
        case ignored

        var line: String {
            switch self {
            case .ok(let detail): return "ok:\(detail)"
            case .error(let code): return "error:\(code)"
            case .drop: return "drop"
            case .ignored: return "ignored"
            }
        }
    }

    struct Live: Equatable {
        let cid: String
        let epoch: Int
        let incoming: Bool
        var state: State
        /// The mute the page asked for; nil until it asks.
        var wantedMuted: Bool?
        var muted = false
    }

    private(set) var configured = false
    private(set) var live: Live?
    private var epochs = 0

    var status: String { live?.state.rawValue ?? "idle" }

    mutating func configure() -> Outcome {
        configured = true
        return .ok("configured")
    }

    mutating func logout() -> Outcome {
        configured = false
        let n = live == nil ? 0 : 1
        live = nil
        return .ok("logout:\(n)")
    }

    mutating func join(_ cid: String) -> Outcome {
        guard configured else { return .error("not_configured") }
        guard live == nil else { return .error("busy") }
        epochs += 1
        live = Live(cid: cid, epoch: epochs, incoming: false, state: .connecting)
        return .ok("connecting:\(epochs)")
    }

    mutating func incoming(_ cid: String) -> Outcome {
        guard configured else { return .error("not_configured") }
        if let l = live { return .error(l.cid == cid ? "duplicate" : "busy") }
        epochs += 1
        live = Live(cid: cid, epoch: epochs, incoming: true, state: .ringing)
        return .ok("ringing:\(epochs)")
    }

    mutating func answered(_ cid: String) -> Outcome {
        guard var l = live, l.cid == cid, l.state == .ringing else { return .ignored }
        l.state = .connecting
        live = l
        return .ok("connecting:\(l.epoch)")
    }

    mutating func connected(_ cid: String, epoch: Int) -> Outcome {
        guard var l = live, l.cid == cid, l.epoch == epoch, l.state == .connecting else { return .drop }
        l.state = .joined
        live = l
        return .ok("joined")
    }

    mutating func ended(_ cid: String, epoch: Int, reason: String) -> Outcome {
        guard let l = live, l.cid == cid, l.epoch == epoch else { return .ignored }
        live = nil
        return .ok("ended:\(reason)")
    }

    mutating func leave() -> Outcome {
        guard let l = live else { return .error("no_call") }
        live = nil
        return .ok(l.state == .ringing ? "ended:declined" : "ended:local")
    }

    mutating func setMuted(_ muted: Bool) -> Outcome {
        guard var l = live else { return .error("no_call") }
        l.wantedMuted = muted
        live = l
        return .ok(l.state == .joined ? "apply" : "pending")
    }

    func wanted() -> Outcome {
        guard let l = live else { return .error("no_call") }
        return .ok("wanted:" + (l.wantedMuted.map { $0 ? "true" : "false" } ?? "none"))
    }

    mutating func muteApplied(epoch: Int, muted: Bool) -> Outcome {
        guard var l = live, l.epoch == epoch, l.state == .joined else { return .ignored }
        l.muted = muted
        live = l
        return .ok("muted:\(muted)")
    }

    mutating func reset() -> Outcome {
        let n = live == nil ? 0 : 1
        live = nil
        return .ok("reset:\(n)")
    }
}

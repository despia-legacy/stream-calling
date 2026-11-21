import Foundation
import Security

/// What `configure` stores, so a VoIP push can be answered when iOS launches the app in the
/// background with no web page loaded. Nothing here is built into the app: every value comes from
/// the page, which got the token from the developer's own server.
struct StreamCallCredentials: Codable, Equatable {
    var apiKey: String
    var userId: String
    var userName: String?
    var token: String
    /// https URL the engine may POST to for a fresh token; re-checked with `isApprovedURL` before use.
    var tokenURL: String?
    /// The VoIP push provider name configured in the Stream dashboard.
    var voipProvider: String
    /// The VoIP token last registered with Stream, so a new one replaces it and logout removes it.
    var registeredVoipToken: String?
}

/// One Keychain generic-password item, readable after the first unlock, on this device only (a VoIP
/// push can arrive while the phone is locked). The token is never logged.
enum StreamCallKeychain {
    private static let service = "despia.ext.streamcall"
    private static let account = "credentials"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func load() -> StreamCallCredentials? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        // A corrupt item reads as "not configured"; the next configure replaces it.
        return try? JSONDecoder().decode(StreamCallCredentials.self, from: data)
    }

    /// True when the item was written.
    @discardableResult
    static func save(_ c: StreamCallCredentials) -> Bool {
        guard let data = try? JSONEncoder().encode(c) else { return false }
        let update: [String: Any] = [kSecValueData as String: data,
                                     kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var add = query
        add.merge(update) { $1 }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func delete() {
        SecItemDelete(query as CFDictionary)
    }
}

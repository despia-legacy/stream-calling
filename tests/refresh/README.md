# Refresh ownership checks

`prepare.py --out <scratch-directory>` copies the real call ledgers and extracts the production configure, logout, token refresh and session helpers without rewriting their bodies. The templates provide controlled SDK connect/disconnect, HTTP continuation and storage boundaries. Credentials are synthetic and remain inside the test process.

Compile `SwiftEngine.swift`, `StreamCallLedger.swift` and `SwiftChecks.swift` with Swift 5 language mode and Foundation. Compile `StreamCallLedger.java`, then `KotlinEngine.kt` and `KotlinChecks.kt` with Kotlin, kotlinx-coroutines and org.json on the classpath. Run each checks executable. A failure returns a nonzero exit code.

The checks cover suspended HTTP replies, user switches including the same user logging in again, newer manual tokens, multiple waiters, expired request IDs, storage failure, candidate connections and late logout/configure completion. They do not compile the vendor SDKs or prove calling, push delivery, Keychain persistence or mobile lifecycle behavior.

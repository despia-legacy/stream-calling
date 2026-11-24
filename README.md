# Stream Calling

Source preview for Despia V3. The runtime update and automatic discovery rollout are awaiting release. Rebuild and install the native app to include these files; uploading them does not change an installed app.

Audio calls through Stream Video, with native CallKit and Telecom integration.

This ready-made extension is part of Despia V3 Extensions. First-party V4 support comes through the new package registry, with a different package shape.

[V3 extension documentation](https://setup.despia.com/experiments/v3-extensions) and [extension skill](https://github.com/despia-legacy/extension-skill).

## Web frameworks and app builders

Use the Despia SDK with React, Vue, Svelte, Next.js, TanStack or HTML5. Projects built with Lovable, Base44, Replit and other app builders need custom JavaScript and static file hosting. Claude Code, Codex and Cursor can edit the same extension files.

## What is included

- `manifest.json`: the extension's routes, parameters, events and native files.
- `ios/` and `android/`: the Swift and Kotlin or Java sources and resources the manifest names.
- `extensions.example.json`: the entry for `public/despia/extensions.json`.
- `streamcall.policy.json`: a record of the native capabilities used by this extension. Keep it with the source; it does not go into `public/despia/`.
- `example/server/stream-token.example.js`: a token endpoint to adapt on your server. It is not part of the native build.

## Requirements

- A Despia V3 app and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- A Stream Video account (API key and secret) and a server that issues user tokens (an example is in `example/`).
- An Apple Developer account for the VoIP push configuration, and a Firebase project for Android push.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/streamcall/` in your web app. The folder name is the extension id, so pages call `extension://streamcall/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"streamcall"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Rebuild and install your Despia V3 app once the runtime update and automatic discovery rollout are released. Signed build verification binds source hashes, dependency pins, app identity and final artifacts. It is separate from human code review. Changes to native files or the manifest require another native build.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Call the published SDK directly and watch the exact answer variable declared in `manifest.json`. The SDK resolves to an object keyed by that variable; the native envelope is inside it. Check both a missing reply and `ok: false` before using `data`. Native errors resolve through the SDK; they do not reject its promise. A single watched variable becomes `undefined` after 30 seconds without a reply.

Await calls that use the same answer variable sequentially. The SDK's command queue does not correlate concurrent responses. Encode every query value with `encodeURIComponent`, including JSON text. Spaces become `%20`; a literal plus becomes `%2B`. Do not use unmodified `URLSearchParams` form encoding, which uses plus for spaces.

Importing the SDK is safe during server rendering. Run native calls only in browser click handlers or mounted client hooks, including client components in Next.js. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features. Optional event handlers use the declared callback on `globalThis`, not an SDK subscription API. Save and restore any callback your app already owns when a component mounts and unmounts.

## Calling it

```javascript
import despia from 'despia-native';

const answerReply = await despia('extension://streamcall/status', ['streamcallStatus']);
const answer = answerReply.streamcallStatus;
if (answer === undefined) throw new Error('No native reply within 30 seconds');
if (!answer.ok) throw new Error(answer.code + ': ' + answer.error);

console.log(answer.data);
```

Calls use `extension://streamcall/<route>?<params>`. Each route answers once, in the page that made the call: the runtime sets the route's answer variable in the page, then calls the route's callback. Only declared parameters are accepted. Keep large data on the native side and pass identifiers through the bridge. A call URL is at most 8192 characters.

Every answer has the same shape: `ok`, `provider`, `platform`, `runtime` (`3`), `bridge` (`1`), `extension`, `route`, `data` (your handler's data on success, `null` on error), and on error `error` (a message) and `code`.

Standard error codes: `bad_request` (an undeclared or missing parameter, or a value of the wrong type), `not_registered` (this build does not contain the extension or route), `forbidden_frame` (the call did not come from the top-level page), `forbidden_origin` (an origin that is not allowed), `rate_limited` (more than 20 calls per second to one extension), `timeout` (no answer within the route's `timeout_ms`), `unavailable`, `platform_limit` (this platform or OS version has no such capability), `handler_error`, plus the codes this extension lists below.

---

## About

This extension adds audio calls through Stream Video. Your page configures the client, starts or joins a call, leaves it, mutes and switches the loudspeaker. The native handlers integrate incoming calls with CallKit and Telecom. Closed-app ringing requires device validation with your push configuration.

- **iOS:** CallKit and a VoIP push (the system call screen, Lock Screen, Bluetooth and CarPlay routing).
- **Android:** a self-managed Telecom call with Stream's call notification and a full-screen ring.
- **App open:** a ring that reaches the running app over Stream's connection rings the same system call screen, with or without push. A push for the same call that arrives afterwards does not ring twice.

---

## Set it up

1. **Stream dashboard.** Create the APN VoIP configuration (its name is `voip_provider`, default `ios-voip`) with your VoIP key or certificate, and the Firebase configuration (its name is `fcm_provider`, default `firebase`). Turn ringing on for the call type you use.
2. **Apple Developer portal.** Turn on Push Notifications for your App ID, and create the VoIP certificate or APNs key.
3. **Despia.** Turn the built-in Stream option off. An app has one VoIP owner, and with this template it is the template. Turn on the microphone purpose string (iOS), push for Android with your own `google-services.json`, and `RECORD_AUDIO` and `BLUETOOTH_CONNECT` (Android). The manifest lists them under `requires`.
4. **Your server.** Run the token endpoint. The Stream API secret stays on your server and never goes into the app.
5. **Your files.** Copy the files in this repository, then change the values listed under "Values to set before you build".

---

## Values to set before you build

Before you build:

- In `manifest.json`, in the `configure` route, replace `tokens.example.com` in `token_url.hosts` with the host of your token server.

Adapt `example/server/stream-token.example.js` on your own server. Despia never fetches or approves that server file. The Android dependencies must be the versions your app's base build already resolves. The build refuses others. This extension uses Stream Android 1.35.0 and push-firebase 1.3.4, which are on Maven Central. Newer versions are published only on Stream's own Maven repository, which this template does not add. You may use them by adding that repository in your own build. On iOS it uses StreamVideo 1.57.0, pinned in `ios/Package.resolved`.

---

## Using it from your web app

```javascript
import despia from 'despia-native';

const credentials = await (await fetch('/stream/token', { method: 'POST' })).json();

const configuredReply = await despia(`extension://streamcall/configure?api_key=${encodeURIComponent(credentials.apiKey)}&user_id=${encodeURIComponent(credentials.userId)}&token=${encodeURIComponent(credentials.token)}&token_url=${encodeURIComponent('https://tokens.example.com/stream/refresh')}`, ['streamcallConfigure']);
const configured = configuredReply.streamcallConfigure;
if (configured === undefined) throw new Error('No native reply within 30 seconds');
if (!configured.ok) throw new Error(configured.code + ': ' + configured.error);

const previousStreamcallIncoming = globalThis.onStreamcallIncoming;
const handleStreamcallIncoming = call => {
  try {
    if (typeof previousStreamcallIncoming === 'function') previousStreamcallIncoming(call);
  } finally {
    console.log(call.call_cid, call.caller_name)
  }
};
globalThis.onStreamcallIncoming = handleStreamcallIncoming;
const cleanupStreamcallIncoming = () => {
  if (globalThis.onStreamcallIncoming !== handleStreamcallIncoming) return;
  if (previousStreamcallIncoming === undefined) delete globalThis.onStreamcallIncoming;
  else globalThis.onStreamcallIncoming = previousStreamcallIncoming;
};
// Call cleanupStreamcallIncoming() when this component unmounts.

const previousStreamcallJoined = globalThis.onStreamcallJoined;
const handleStreamcallJoined = call => {
  try {
    if (typeof previousStreamcallJoined === 'function') previousStreamcallJoined(call);
  } finally {
    console.log(call.call_cid, call.audio_route)
  }
};
globalThis.onStreamcallJoined = handleStreamcallJoined;
const cleanupStreamcallJoined = () => {
  if (globalThis.onStreamcallJoined !== handleStreamcallJoined) return;
  if (previousStreamcallJoined === undefined) delete globalThis.onStreamcallJoined;
  else globalThis.onStreamcallJoined = previousStreamcallJoined;
};
// Call cleanupStreamcallJoined() when this component unmounts.

const previousStreamcallEnded = globalThis.onStreamcallEnded;
const handleStreamcallEnded = call => {
  try {
    if (typeof previousStreamcallEnded === 'function') previousStreamcallEnded(call);
  } finally {
    console.log(call.call_cid, call.reason)
  }
};
globalThis.onStreamcallEnded = handleStreamcallEnded;
const cleanupStreamcallEnded = () => {
  if (globalThis.onStreamcallEnded !== handleStreamcallEnded) return;
  if (previousStreamcallEnded === undefined) delete globalThis.onStreamcallEnded;
  else globalThis.onStreamcallEnded = previousStreamcallEnded;
};
// Call cleanupStreamcallEnded() when this component unmounts.


const joinedReply = await despia(`extension://streamcall/join?call_type=${encodeURIComponent('audio_call')}&call_id=${encodeURIComponent('support-42')}&create=${encodeURIComponent(true)}&ring=${encodeURIComponent(true)}&members=${encodeURIComponent('agent-7')}`, ['streamcallJoin']);
const joined = joinedReply.streamcallJoin;
if (joined === undefined) throw new Error('No native reply within 30 seconds');
if (!joined.ok) throw new Error(joined.code + ': ' + joined.error);

console.log(joined.data);
```

### Credentials

Nothing is built into the app. `configure` takes the API key, the user and a token from your page, which got them from your server. They are stored on the device (the Keychain on iOS, the extension's private storage on Android), so a push can ring with no page loaded. `logout` ends a live call, attempts to remove the push device from Stream, and deletes local credentials. Remote device removal needs a working connection and valid server authorization. Android orders push registration and cleanup before replacing its SDK client. A failed connection or credential save restores the prior session after cache cleanup succeeds and attempts push registration again. If local push-cache cleanup fails, the command returns `storage_failed` and leaves the runtime unconfigured. Retry configure after storage recovers; the prior credentials remain in private storage unless you logged out. The extension does not include token values in route replies, page events or its log messages.

When the token expires, the app first asks the `token_url` you configured (this works with no page loaded). If that endpoint is absent or cannot renew the token, it sends `token_needed` with `user_id` and `request_id`, then waits 10 seconds for `set_token`. Pass the event's `request_id` back with your new token so a late response cannot update another login. Omitting `request_id` updates only the currently active session. Expired or unknown IDs return `token_request_expired`. If no token comes, the call ends with the reason `token_expired`. The example server renews a token only within 7 days after it expired.

### Routes

| Route         | Parameters                                                                                              | Answer `data` | Answer variable | Callback |
| ------------- | ------------------------------------------------------------------------------------------------------- | ------------- | --- | --- |
| `configure`   | `api_key`, `user_id`, `token` (required), `user_name`, `token_url` (https, an approved host), `voip_provider`, `fcm_provider` | `user_id`, `push` (the push registration state) | `streamcallConfigure` | `onStreamcallConfigure` |
| `set_token`   | `token` (required), `request_id` (from `token_needed`, recommended)                                                                                      | An empty object | `streamcallSetToken` | `onStreamcallSetToken` |
| `logout`      | none                                                                                                    | An empty object | `streamcallLogout` | `onStreamcallLogout` |
| `permissions` | `request` (bool)                                                                                        | `microphone`, `notifications`; on Android also `bluetooth` and `full_screen` | `streamcallPermissions` | `onStreamcallPermissions` |
| `join`        | `call_type` (required, for example `audio_call`), `call_id` (required), `create`, `ring`, `members` (comma-separated user ids; `ring` needs at least one) | `state: "connecting"` and the call | `streamcallJoin` | `onStreamcallJoin` |
| `leave`       | none                                                                                                    | `call_cid`, `state: "ended"` | `streamcallLeave` | `onStreamcallLeave` |
| `set_muted`   | `muted` (bool, required)                                                                                | `muted`, `call_cid` | `streamcallSetMuted` | `onStreamcallSetMuted` |
| `set_speaker` | `on` (bool, required)                                                                                   | The audio route | `streamcallSetSpeaker` | `onStreamcallSetSpeaker` |
| `status`      | none                                                                                                    | `configured`, `state`, `push`, `audio_route`, `user_id`, `call_cid`, `muted`, `speaker` | `streamcallStatus` | `onStreamcallStatus` |

`status` is the truth. An event sent while no page is loaded is dropped.

### Events

Every event carries `call_cid` and a growing `seq`.

| Event          | Callback                    | Scope |
| -------------- | --------------------------- | ----- |
| `incoming`     | `onStreamcallIncoming`      | `app` |
| `connecting`   | `onStreamcallConnecting`    | `app` |
| `joined`       | `onStreamcallJoined`        | `app` |
| `ended`        | `onStreamcallEnded`         | `app`. Carries `reason` |
| `muted`        | `onStreamcallMuted`         | `app` |
| `audio_route`  | `onStreamcallAudioRoute`    | `app` |
| `error`        | `onStreamcallError`         | `app` |
| `token_needed` | `onStreamcallTokenNeeded`   | `app` |

### Error codes

Besides the standard codes: `not_configured`, `busy`, `no_call`, `audio_busy` (a join while the recorder sample records), `permission_denied`, `connect_failed`, `storage_failed`, `bad_token`, `token_request_expired` (a refresh request is no longer pending), `session_changed` (configure was superseded by logout or another configure) and, for a failed audio route change, `route_failed`.

---

## Permissions and capabilities

### iOS

- **Microphone.** `NSMicrophoneUsageDescription`, under `requires.usage_descriptions`.
- **Background mode.** `audio`.
- **VoIP.** The manifest claims the app's one VoIP owner (`claims.voip`). PushKit is registered at app launch, so a push can wake a closed app. Stream's CallKit service handles the system call screen. The extension never creates a second PushKit registry.
- **Every push rings or ends.** Each VoIP push is reported to CallKit before PushKit's completion runs. A push with no call in it, or one that arrives while no user is configured (after `logout`), is reported and ended at once, so nothing rings as an unknown caller.
- **Audio.** The extension never configures the audio session. Stream and the system do.

### Android

- **Permissions.** `RECORD_AUDIO` and `BLUETOOTH_CONNECT`, under `requires.permissions`, and the restricted `USE_FULL_SCREEN_INTENT` for the Lock Screen ring.
- **Push.** Your app keeps its one Firebase messaging service. `StreamPushReceiver` hands only Stream's messages to Stream.
- **Call screen.** `CallIntentActivity`, opened by Stream's call notification, with Answer and Decline on the Lock Screen.
- **Full-screen ring (Android 14 and later).** The user can turn the full-screen permission off for an app, and Google Play grants it by default only to calling apps. `permissions` reports it as `full_screen`. With `request: true` and the permission off, it opens the system page where the user turns it back on. Without it, the call still rings as a heads-up notification.
- **Removed pieces.** The module manifest removes Stream's camera, screen-share and livestream pieces.
- **Exported components.** The push receiver, and Android Telecom's connection service and mute receiver, recorded in the capability policy and verified against the built artifact.

---

## Platform limits

- **One VoIP owner.** The app has one PushKit owner, this extension. The build refuses it next to base-app code that registers PushKit itself.
- **China.** An app with this extension links PushKit and CallKit. Remove China from the app's available territories in App Store Connect.
- **Android dependencies.** Use the Maven repository and exact dependencies declared in `manifest.json` and `android/streamcall/build.gradle`.
- Account switching is experimental. Verify push-device registrations in your Stream project when changing users.
- Background ringing and Lock Screen answering remain preview features.

## Verification

Controlled build hosts compiled and linked this extension against StreamVideo 1.57.0 on iOS and Stream Video 1.35.0 with push 1.3.4 on Android. This verifies native SDK compatibility. Live calls, account configuration, background push delivery, native bridge execution and device storage behavior remain unverified.

## License

[Despia V3 Extension Source-Available Commercial License](LICENSE), version 1.4.
This template is proprietary and source available.

You may inspect, fork, customize, build and run it for noncommercial development,
evaluation and testing without a paid Despia license, including work on an app
you plan to release commercially later.

Production or commercial use requires a valid Despia project and an applicable
Despia license for that application and operating system. A Solo Pack covers
its licensed OS, iOS or Android. A Hybrid Pack covers both. Your applicable
license includes all provided V3 templates you have lawful access to and any
number of your own extensions, with no separate extension purchase or
per-extension fee.

Keep the license and applicable notices. Third-party material keeps its own
terms. Experimental V3 extensions do not include a commitment to maintenance
or a V4 port. See [LICENSE](LICENSE) for the full terms and [Despia](https://despia.com)
for licensing.

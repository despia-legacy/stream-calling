# Stream Calling

Source preview for Despia V3. This snapshot requires the runtime update currently awaiting release and an approved extension-enabled native build. Uploading these files does not change an installed app.

Audio calls through Stream Video, with native CallKit and Telecom integration.

This ready-made extension is part of Despia V3 Extensions. First-party V4 support comes through the new package registry, with a different package shape.

[V3 extension documentation](https://setup.despia.com/experiments/v3-extensions) and [extension skill](https://github.com/despia-legacy/extension-skill).

## Web frameworks and app builders

Use the Despia SDK with React, Vue, Svelte, Next.js, TanStack or HTML5. Projects built with Lovable, Base44, Replit and other app builders need custom JavaScript and static file hosting. Claude Code, Codex and Cursor can edit the same extension files.

## What is included

- `manifest.json`: the extension's routes, parameters, events and native files.
- `ios/` and `android/`: the Swift and Kotlin or Java sources and resources the manifest names.
- `extensions.example.json`: the entry for `public/despia/extensions.json`.
- `streamcall.policy.json`: the approval policy. It is a file you send to Despia with your approval request, together with the extension files. It is not served from your site and does not go into `public/despia/`.
- `example/`: your own code. `example/src/lib/streamcall.js` is a page-side helper and `example/server/stream-token.example.js` is a token endpoint. They are not part of the native build.

## Requirements

- A Despia V3 app with V3 Extensions enabled, and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- A Stream Video account (API key and secret) and a server that issues user tokens (an example is in `example/`).
- An Apple Developer account for the VoIP push configuration, and a Firebase project for Android push.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/streamcall/` in your web app. The folder name is the extension id, so pages call `extension://streamcall/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"streamcall"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Request approval and a new native build in Despia, and send `streamcall.policy.json` with the request. Despia reviews and approves these exact files before a build includes them, and any change to the manifest or to a native file needs a new approval and a new native build. It cannot be applied over the air.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Copy `example/src/lib/despia-extension.js` to your app's `src/lib/` and adjust the import path if needed. It imports `despia` from `despia-native`, calls the SDK and returns the native answer's `data`. It checks native error envelopes and throws an error with their `code`. The SDK itself resolves native `ok: false` envelopes; it does not reject them. A single watched value becomes `undefined` after 30 seconds, which this helper reports as `sdk_timeout`.

Each helper call forwards a unique native callback into a separate watched value, so overlapping calls do not share response globals. `onExtensionEvent` registers the declared native event callbacks and returns a cleanup function. Events are callbacks, not values you can poll from the SDK proxy.

Importing the SDK is safe during server rendering. Run calls and event subscriptions only from browser click handlers or mounted client hooks. React and Vue can use the browser APIs used by this helper; in Next.js and other server-rendered apps, keep these calls in client code. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features.

## Calling it

```javascript
import { extensionCall } from './lib/despia-extension.js';

const answer = await extensionCall('streamcall', 'status');
console.log(answer);
```

Calls use `extension://streamcall/<route>?<params>`. Each route answers once, in the page that made the call: the runtime sets the route's answer variable in the page, then calls the route's callback. Only declared parameters are accepted. Keep large data on the native side and pass identifiers through the bridge. A call URL is at most 8192 characters.

Every answer has the same shape: `ok`, `provider`, `platform`, `runtime` (`3`), `bridge` (`1`), `extension`, `route`, `data` (your handler's data on success, `null` on error), and on error `error` (a message) and `code`.

Standard error codes: `bad_request` (an undeclared or missing parameter, or a value of the wrong type), `not_registered` (this build does not contain the extension or route), `forbidden_frame` (the call did not come from the top-level page), `forbidden_origin` (an origin that is not allowed), `rate_limited` (more than 20 calls per second to one extension), `timeout` (no answer within the route's `timeout_ms`), `unavailable`, `platform_limit` (this platform or OS version has no such capability), `handler_error`, plus the codes this extension lists below.

---

## About

This extension adds audio calls through Stream Video. Your page configures the client, starts or joins a call, leaves it, mutes and switches the loudspeaker. The native handlers integrate incoming calls with CallKit and Telecom. Closed-app ringing requires device validation with your push configuration.

- **iOS:** CallKit and a VoIP push (the system call screen, Lock Screen, Bluetooth and CarPlay routing).
- **Android:** a self-managed Telecom call with Stream's call notification and a full-screen ring.

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

The two files in `example/` are yours. Copy both helpers from `example/src/lib/` into your web app's `src/lib/` and run `example/server/stream-token.example.js` on your own server. Despia never fetches or approves them. The Android dependencies must be the versions your app's base build already resolves. The build refuses others. This extension uses Stream Android 1.35.0 and push-firebase 1.3.4, which are on Maven Central. Newer versions are published only on Stream's own Maven repository, which this template does not add. You may use them by adding that repository in your own build. On iOS it uses StreamVideo 1.57.0, pinned in `ios/Package.resolved`.

---

## Using it from your web app

```javascript
import { configure, join, leave, setMuted, on, refreshTokensWith } from "./lib/streamcall.js";

const getToken = async () => (await fetch("/stream/token", { method: "POST" })).json();
const { apiKey, userId, token } = await getToken();
await configure({ apiKey, userId, token, tokenUrl: "https://tokens.example.com/stream/refresh" });
refreshTokensWith(async () => (await getToken()).token);

on("incoming", (c) => console.log("ringing", c.call_cid, c.caller_name)); // the system call screen rings too
on("joined", (c) => console.log("in call", c.call_cid, c.audio_route));
on("ended", (c) => console.log("ended", c.call_cid, c.reason));

await join({ callId: "support-42", ring: true, members: ["agent-7"] });
// Later: setMuted(true); leave();
```

The helper in `example/src/lib/streamcall.js` gives one promise per route and one handler per event: `configure`, `setToken`, `logout`, `permissions`, `join`, `leave`, `setMuted`, `setSpeaker`, `status`, `on(event)` and `refreshTokensWith`. It rejects with an `Error` whose `code` is the answer's code.

### Credentials

Nothing is built into the app. `configure` takes the API key, the user and a token from your page, which got them from your server. They are stored on the device (the Keychain on iOS, the extension's private storage on Android), so a push can ring with no page loaded. `logout` ends a live call, removes the push device from Stream, and deletes them. The token never appears in a reply, an event or a log line.

When the token expires, the app first asks the `token_url` you configured (this works with no page loaded). Without one it sends `token_needed` and waits 10 seconds for `set_token`. If no token comes, the call ends with the reason `token_expired`. The example server renews a token only within 7 days after it expired.

### Routes

| Route         | Parameters                                                                                              | Answer `data` | Answer variable | Callback |
| ------------- | ------------------------------------------------------------------------------------------------------- | ------------- | --- | --- |
| `configure`   | `api_key`, `user_id`, `token` (required), `user_name`, `token_url` (https, an approved host), `voip_provider`, `fcm_provider` | `user_id`, `push` (the push registration state) | `streamcallConfigure` | `onStreamcallConfigure` |
| `set_token`   | `token` (required)                                                                                      | An empty object | `streamcallSetToken` | `onStreamcallSetToken` |
| `logout`      | none                                                                                                    | An empty object | `streamcallLogout` | `onStreamcallLogout` |
| `permissions` | `request` (bool)                                                                                        | `microphone`, `notifications` | `streamcallPermissions` | `onStreamcallPermissions` |
| `join`        | `call_type` (required, the helper defaults to `audio_call`), `call_id` (required), `create`, `ring`, `members` (comma-separated user ids; `ring` needs at least one) | `state: "connecting"` and the call | `streamcallJoin` | `onStreamcallJoin` |
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

Besides the standard codes: `not_configured`, `busy`, `no_call`, `audio_busy` (a join while the recorder sample records), `permission_denied`, `connect_failed`, `storage_failed`, `bad_token` and, for a failed audio route change, `route_failed`.

---

## Permissions and capabilities

### iOS

- **Microphone.** `NSMicrophoneUsageDescription`, under `requires.usage_descriptions`.
- **Background mode.** `audio`.
- **VoIP.** The manifest claims the app's one VoIP owner (`claims.voip`). Stream's CallKit service handles the system call screen. The extension never creates a second PushKit registry.
- **Audio.** The extension never configures the audio session. Stream and the system do.

### Android

- **Permissions.** `RECORD_AUDIO` and `BLUETOOTH_CONNECT`, under `requires.permissions`, and the restricted `USE_FULL_SCREEN_INTENT` for the Lock Screen ring.
- **Push.** Your app keeps its one Firebase messaging service. `StreamPushReceiver` hands only Stream's messages to Stream.
- **Call screen.** `CallIntentActivity`, opened by Stream's call notification, with Answer and Decline on the Lock Screen.
- **Removed pieces.** The module manifest removes Stream's camera, screen-share and livestream pieces.
- **Exported components.** The push receiver, and Android Telecom's connection service and mute receiver, listed in the approval policy exactly as the build shows them.

---

## Platform limits

- **One VoIP owner.** With the built-in Stream option on, the build refuses this template.
- **Android dependencies.** Use the Maven repository and exact dependencies declared in `manifest.json` and `android/streamcall/build.gradle`.
- Account switching is experimental. Verify push-device registrations in your Stream project when changing users.
- Background ringing and Lock Screen answering remain preview features.

## License

[Despia V3 Extension Source-Available Commercial License](LICENSE).
You may inspect the source. Using or modifying this extension requires a valid
Despia commercial license and a valid Despia project for the particular app.
The standard price is **USD 249 per application per operating system**.
iOS and Android require separate licenses. See [Despia](https://despia.com).
Third-party SDKs keep their own licenses. Experimental V3 extensions do not
include a commitment to maintenance or a V4 port.

## Source and licensing

Browse, clone or fork this source to inspect it. Public GitHub forks remain public. For private development, clone into a private workspace or private repository. Modification, building, execution and deployment require a valid Despia commercial license and a valid Despia project for the application. The standard price is USD 249 per application per operating system. Licensed users may customize the extension or use it as a reference for new functionality in that licensed app. Keep the license and applicable notices. See [LICENSE](LICENSE) for the terms.

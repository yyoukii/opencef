# OpenCEF-Android

OpenCEF-Android is an open-source Android framework that exposes a small,
CEF-style API - browser creation, navigation, visibility, resizing,
lifecycle/loading events, and a two-way JavaScript <-> Kotlin event bridge -
for rendering HTML/CSS/JavaScript UI inside an Android application.

**This project is NOT the official Chromium Embedded Framework (CEF) port
for Android.** The upstream CEF project has no official Android support.
OpenCEF-Android reaches a similar developer experience by wrapping Android's
built-in `WebView` (itself Chromium-based) behind a CEF-shaped API.

## Current Scope

- **Milestone 1**: a minimal, standalone Android WebView framework - browser
  creation, destruction, and the JavaScript bridge.
- **Milestone 2**: browser lifecycle & navigation - `loadUrl`/`reload`/
  `stopLoading`, `show`/`hide`, `resize`.
- **Milestone 3**: browser events, a richer JavaScript <-> Kotlin bridge, and
  a tiny built-in JS runtime. See "Milestone 3 API" below.

All three milestones intentionally exclude: native C++/JNI, GTA integration,
SA-MP/open.mp integration, networking, a database, a DI framework, Compose,
coroutines/Flow/RxJava, and authentication. Those remain out of scope.

## Architecture

```
demo  ->  android  ->  core
```

- `core` - pure Kotlin/JVM module. Contracts only (`BrowserInstance`,
  `BrowserManager`, `JSBridge`, `MessageSerializer`) plus one concrete,
  Android-free implementation (`EventBus`). No Android dependency at all -
  unit-testable on a plain JVM.
- `android` - Android library module. Implements the `core` contracts using
  `android.webkit.WebView` (`AndroidBrowserManager`, `WebViewBrowserInstance`,
  `WebViewJsBridge`, `JsonMessageSerializer`).
- `demo` - a minimal Android application (no Material, no Compose) that
  wires everything together and demonstrates the API.

## Build Instructions

Requires JDK 17 and the Android SDK (`compileSdk 35`, `minSdk 26`). The
Gradle Wrapper is checked into the repository, so a separate Gradle install
is not required.

1. Build everything:
   ```
   ./gradlew build
   ```
2. Run just the `core` unit tests:
   ```
   ./gradlew :core:test
   ```

## Continuous Integration

`.github/workflows/android.yml` builds and tests the project on every push
and pull request using GitHub-hosted Ubuntu runners (JDK 17, Android SDK
platform 35 and matching build-tools installed via `sdkmanager`, no local
Android SDK required). On success it uploads the debug APK
(`demo/build/outputs/apk/debug/*.apk`) as a workflow artifact named
`OpenCEF-Android-debug`.

## Running the Demo

```
./gradlew :demo:installDebug
```

Launch "OpenCEF Demo". A status label and a row of buttons sit above the
browser:
- The status label shows **Loading...** / **Ready**, driven by the
  `"loading"`/`"loaded"` browser events.
- **Reload** / **Stop** / **Hide/Show** / **Resize** / **Page 2** work as in
  Milestone 2.
- The page itself (`index.html`) has a login form (unchanged from Milestone
  1/2) plus a **Send Demo Message** / **Unsubscribe** pair demonstrating the
  Milestone 3 round trip: JS emits `"demoMessage"` -> native receives it via
  `browser.on("demoMessage")` -> native replies with
  `browser.emit("nativeMessage", ...)` -> JS receives it via
  `window.cef.on("nativeMessage", ...)`. **Unsubscribe** calls
  `window.cef.off(...)`, after which further replies stop appearing.

The browser is destroyed in `onDestroy()`.

## Milestone 3 API

`BrowserInstance` (in `core`, still Android-free) adds:

```kotlin
val isLoading: Boolean

fun on(event: String, listener: (payload: String) -> Unit)
fun off(event: String, listener: (payload: String) -> Unit)
fun emit(event: String, payload: String)
```

`on`/`off`/`emit` are one small event channel, not two: it carries both
framework-level browser events and JavaScript <-> native bridge messages,
all as `(event: String, payload: String)` where `payload` is a JSON string.

**Browser events** (fired by the framework itself, listened to with
`browser.on(...)`):

| Event               | Payload                                  | Fired from                          |
|----------------------|-------------------------------------------|--------------------------------------|
| `created`            | `{}`                                      | right after `BrowserManager.create()` |
| `navigation`         | `{"url": "..."}`                          | `WebViewClient.onPageStarted`        |
| `loading`             | `{"url": "..."}`                          | `WebViewClient.onPageStarted`        |
| `loaded`              | `{"url": "..."}`                          | `WebViewClient.onPageFinished`       |
| `error`               | `{"url","errorCode","description"}`       | `WebViewClient.onReceivedError` (main frame only) |
| `visibilityChanged`   | `{"visible": true/false}`                 | `show()` / `hide()`                  |
| `destroyed`           | `{}`                                      | `destroy()`, before listeners are cleared |

`isLoading` and `url` are kept current by this instance privately
subscribing to its own `"loading"`/`"loaded"`/`"navigation"` events - the
same mechanism any other caller would use, not a separate code path. Because
of this, `url` now also tracks in-page navigation the page itself triggers
(a link, a JS redirect), not just `create()`/`loadUrl()` calls - an
improvement over Milestone 2, where this was a documented limitation.

**JavaScript -> native** (unchanged contract from Milestone 1/2):

```html
<script>
  window.cef.emit('login', JSON.stringify({ username: 'test', password: 'example' }));
</script>
```
```kotlin
browser.on("login") { payload -> /* payload is the JSON string */ }
```

**Native -> JavaScript** (new):

```kotlin
browser.emit("nativeMessage", jsonPayload)
```
```html
<script>
  window.cef.on('nativeMessage', function (payload) {
    var data = JSON.parse(payload);
  });
</script>
```

**Built-in JS runtime**: `window.cef.on(event, callback)` / `window.cef.off(event, callback)`
are injected via `evaluateJavascript()` right when each page finishes
loading (`onPageFinished`), on top of the already-present native-backed
`window.cef.emit(...)`. Because each navigation gets a fresh JS context,
this injection re-runs on every page load - `window.cef.on(...)` is only
guaranteed to exist **after** the page has finished loading, e.g. inside a
`window.addEventListener('load', ...)` handler, as the demo does.

**Isolation**: every `BrowserInstance` owns its own private `EventBus`.
Events and bridge messages from one browser are never visible to another
browser's listeners, and a shared/global event channel was deliberately
not used.

## Security Notes

- The demo only ever loads local assets (`file:///android_asset/...`) - it
  never requests the `INTERNET` permission and never loads a remote URL.
- `addJavascriptInterface` exposes the bridge to whatever page is currently
  loaded in that `WebView`. Any page loaded into a browser created by this
  framework must be trusted content (bundled with the app, not remote or
  user-controlled), since it has access to `window.cef.emit(...)` and, from
  Milestone 3 on, `window.cef.on(...)`/`off(...)`.
- No URL allowlisting is implemented yet. That is a deliberate gap for this
  milestone, acceptable only because nothing beyond trusted local assets is
  ever loaded - `loadUrl()` performs no validation of its own beyond
  Kotlin's non-nullable `String` type.

## Known Limitations

- `on`/`off`/`emit` carry JSON strings only - no request/response
  correlation, no Promises, no RPC framework yet.
- `resize()` is not remembered across `attachTo()`/re-parenting - each
  `attachTo()` call resets sizing to fill its container.
- No native C++/JNI layer yet - pure Kotlin/Android only.
- `WebView` behavior (including all lifecycle/event callbacks) is not
  covered by the JVM unit tests in `core` - by design; it belongs in an
  instrumented test in a later milestone.

## Testing

`core` has JVM unit tests for `EventBus`, including its Milestone 3
`clear()` behavior (`:core:test`). `WebView` behavior, including the new
lifecycle callbacks and JS runtime injection, is not unit-tested here on
purpose - it requires a real Android runtime, so it belongs in an
instrumented test in a later milestone.

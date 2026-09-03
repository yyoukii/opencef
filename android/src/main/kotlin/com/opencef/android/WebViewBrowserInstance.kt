package com.opencef.android

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.opencef.core.BrowserHandle
import com.opencef.core.BrowserInstance
import com.opencef.core.EventBus
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal class WebViewBrowserInstance(
    override val handle: BrowserHandle,
    initialUrl: String,
    private val webView: WebView,
    private val eventBus: EventBus,
) : BrowserInstance {

    private val destroyed = AtomicBoolean(false)

    // Tracked locally instead of read live from the WebView, so these
    // properties stay safe to read from any thread without needing to
    // marshal onto the UI thread just to answer a getter. url and isLoading
    // are kept current by subscribing to this instance's own "navigation"/
    // "loading"/"loaded" events below, the same way any other caller would.
    @Volatile
    private var currentUrl: String = initialUrl

    @Volatile
    private var visibleState = true

    @Volatile
    private var loadingState = true

    init {
        eventBus.on("navigation") { payload -> readUrl(payload)?.let { currentUrl = it } }
        eventBus.on("loading") { loadingState = true }
        eventBus.on("loaded") { loadingState = false }
    }

    override val url: String
        get() = currentUrl

    override val isVisible: Boolean
        get() = visibleState

    override val isLoading: Boolean
        get() = loadingState

    override val isDestroyed: Boolean
        get() = destroyed.get()

    override fun loadUrl(url: String) {
        if (destroyed.get()) return
        currentUrl = url
        runOnUiThread {
            if (!destroyed.get()) {
                webView.loadUrl(url)
            }
        }
    }

    override fun reload() {
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get()) {
                webView.reload()
            }
        }
    }

    override fun stopLoading() {
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get()) {
                webView.stopLoading()
            }
        }
    }

    override fun show() {
        if (destroyed.get()) return
        visibleState = true
        eventBus.onMessage("visibilityChanged", "{\"visible\":true}")
        runOnUiThread {
            if (!destroyed.get()) {
                webView.visibility = View.VISIBLE
            }
        }
    }

    override fun hide() {
        // Only toggles visibility, never removeView(): hide() must preserve
        // the instance and its loaded page state, unlike destroy().
        if (destroyed.get()) return
        visibleState = false
        eventBus.onMessage("visibilityChanged", "{\"visible\":false}")
        runOnUiThread {
            if (!destroyed.get()) {
                webView.visibility = View.GONE
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get()) {
                webView.layoutParams = ViewGroup.LayoutParams(width, height)
            }
        }
    }

    override fun executeJS(code: String) {
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get()) {
                webView.evaluateJavascript(code, null)
            }
        }
    }

    override fun on(event: String, listener: (payload: String) -> Unit) {
        // Registering on an already-destroyed browser would only retain a
        // listener closure that can never fire - skip it rather than leak it.
        if (destroyed.get()) return
        eventBus.on(event, listener)
    }

    override fun off(event: String, listener: (payload: String) -> Unit) {
        // Always allowed, even after destroy(): removing a reference can
        // never leak, and eventBus.clear() below already removes everything
        // anyway, so this is just a normal (possibly redundant) no-op then.
        eventBus.off(event, listener)
    }

    override fun emit(event: String, payload: String) {
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get()) {
                val call = "window.cef && window.cef.__dispatch && window.cef.__dispatch(" +
                    "${JSONObject.quote(event)}, ${JSONObject.quote(payload)})"
                webView.evaluateJavascript(call, null)
            }
        }
    }

    override fun destroy() {
        // compareAndSet makes this idempotent even under concurrent calls:
        // only the caller that flips false -> true actually tears down the
        // WebView. The event fires (while listeners are still registered)
        // and the listener map is cleared before the WebView itself is
        // touched, so nothing here depends on the posted teardown running.
        if (!destroyed.compareAndSet(false, true)) return
        eventBus.onMessage("destroyed", "{}")
        eventBus.clear()
        runOnUiThread {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    // attachTo() always (re)applies MATCH_PARENT sizing - it is the
    // "place me in this container, filling it" entry point. A previous
    // resize() is not remembered across attachTo(); call resize() again
    // afterward if a custom size is still needed post-(re)attach.
    fun attachTo(container: ViewGroup) {
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get()) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                container.addView(
                    webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    private fun readUrl(payload: String): String? =
        runCatching { JSONObject(payload).getString("url") }.getOrNull()

    // Runs immediately when already on the UI thread instead of always
    // deferring via post(). This matters for callers like
    // Activity.onDestroy(), which already runs on the UI thread: without
    // this, destroy() would only be queued, not actually executed, by the
    // time onDestroy() returns, leaving the WebView briefly (or, if the
    // process dies right after) permanently undestroyed. Callers on other
    // threads still get marshalled via post() as before.
    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            webView.post(action)
        }
    }
}

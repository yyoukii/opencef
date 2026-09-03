package com.opencef.android

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.opencef.core.BrowserHandle
import com.opencef.core.BrowserInstance
import com.opencef.core.BrowserManager
import com.opencef.core.EventBus
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class AndroidBrowserManager(
    private val context: Context,
) : BrowserManager {

    private val instances = ConcurrentHashMap<BrowserHandle, WebViewBrowserInstance>()
    private val nextId = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun create(url: String): BrowserInstance {
        val handle = BrowserHandle(nextId.getAndIncrement())
        // Each browser gets its own EventBus: lifecycle events and JS bridge
        // messages for this instance are never visible to another instance's
        // listeners (see BrowserInstance.on/off/emit).
        val eventBus = EventBus()
        val webView = createWebViewOnUiThread(url, eventBus)
        val instance = WebViewBrowserInstance(handle, url, webView, eventBus)
        instances[handle] = instance
        eventBus.onMessage("created", "{}")
        return instance
    }

    override fun destroy(handle: BrowserHandle) {
        instances.remove(handle)?.destroy()
    }

    override fun destroyAll() {
        instances.keys.toList().forEach { destroy(it) }
    }

    override fun get(handle: BrowserHandle): BrowserInstance? = instances[handle]

    fun attachTo(handle: BrowserHandle, container: ViewGroup) {
        instances[handle]?.attachTo(container)
    }

    // create() must return synchronously even when called from a background
    // thread, but WebView can only be constructed on the UI thread. If we're
    // already there, build directly; otherwise hop over and block this
    // thread until the UI thread finishes.
    //
    // buildWebView() runs inside the posted block, so any exception it
    // throws is caught there and re-thrown on the calling thread after the
    // latch releases - this must happen in a finally block, otherwise an
    // exception would leave the latch at 1 forever and the calling thread
    // would block indefinitely instead of seeing the failure.
    private fun createWebViewOnUiThread(url: String, eventBus: EventBus): WebView {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return buildWebView(url, eventBus)
        }
        var result: WebView? = null
        var failure: Throwable? = null
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                result = buildWebView(url, eventBus)
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            throw IllegalStateException("Unable to post WebView creation to the main thread")
        }
        latch.await()
        failure?.let { throw it }
        return checkNotNull(result)
    }

    private fun buildWebView(url: String, eventBus: EventBus): WebView {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = OpenCefWebViewClient(eventBus)
        // Only trusted, bundled local content should ever be loaded here -
        // this bridge is reachable by whatever page is currently loaded.
        webView.addJavascriptInterface(WebViewJsBridge(eventBus), "cef")
        webView.loadUrl(url)
        return webView
    }
}

// Translates raw WebView lifecycle callbacks into the same small
// (event, jsonPayload) shape used everywhere else in this framework -
// nothing here leaks WebView/WebResourceError/WebResourceRequest types out
// through BrowserInstance.on(). Runs on the UI thread, same as the WebView
// callbacks it overrides.
private class OpenCefWebViewClient(private val eventBus: EventBus) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val payload = JSONObject().put("url", url).toString()
        eventBus.onMessage("navigation", payload)
        eventBus.onMessage("loading", payload)
    }

    override fun onPageFinished(view: WebView, url: String) {
        // Re-installed on every navigation, since each page load gets a
        // fresh JS context with no memory of previously-registered
        // window.cef.on() listeners.
        view.evaluateJavascript(JS_RUNTIME_BOOTSTRAP, null)
        val payload = JSONObject().put("url", url).toString()
        eventBus.onMessage("loaded", payload)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val payload = JSONObject()
            .put("url", request.url.toString())
            .put("errorCode", error.errorCode)
            .put("description", error.description?.toString().orEmpty())
            .toString()
        eventBus.onMessage("error", payload)
    }

    private companion object {
        // Upgrades window.cef (already present as the native emit() bridge)
        // with a tiny JS-side listener registry, without losing the
        // original native-backed emit(). Guarded so re-running this on a
        // page that (unexpectedly) already has it does not drop listeners
        // registered since the last injection.
        const val JS_RUNTIME_BOOTSTRAP = """
(function() {
    if (!window.cef || window.cef.__opencefRuntime) { return; }
    var nativeEmit = window.cef.emit.bind(window.cef);
    var listeners = {};
    window.cef = {
        __opencefRuntime: true,
        emit: function(event, payload) { nativeEmit(event, payload); },
        on: function(event, callback) {
            (listeners[event] || (listeners[event] = [])).push(callback);
        },
        off: function(event, callback) {
            var list = listeners[event];
            if (!list) { return; }
            var index = list.indexOf(callback);
            if (index !== -1) { list.splice(index, 1); }
        },
        __dispatch: function(event, payload) {
            (listeners[event] || []).slice().forEach(function(cb) { cb(payload); });
        }
    };
})();
"""
    }
}

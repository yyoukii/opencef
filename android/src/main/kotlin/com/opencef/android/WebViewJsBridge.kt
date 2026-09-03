package com.opencef.android

import android.webkit.JavascriptInterface
import com.opencef.core.JSBridge

class WebViewJsBridge(private val jsBridge: JSBridge) {

    // Android does not guarantee @JavascriptInterface methods run on the UI
    // thread - in practice this runs on a WebView-internal thread. onMessage()
    // (EventBus) then invokes listeners synchronously on that same thread.
    @JavascriptInterface
    fun emit(event: String, payload: String) {
        jsBridge.onMessage(event, payload)
    }
}

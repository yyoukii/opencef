package com.opencef.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// Thread contract: onMessage() runs listeners synchronously, on whichever
// thread called onMessage() - it does not dispatch to the UI thread itself.
// For the Android JS bridge, that thread is a WebView-internal thread, not
// the UI thread (see WebViewJsBridge). A listener that needs to touch a
// WebView must marshal to the UI thread itself; BrowserInstance.executeJS()
// already does this internally, so calling it from a listener is safe as-is.
class EventBus : JSBridge {

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()

    fun on(event: String, listener: (payload: String) -> Unit) {
        listeners.computeIfAbsent(event) { CopyOnWriteArrayList() }.add(listener)
    }

    // Removal relies on listener reference equality, so pass the same
    // function reference given to on() rather than a newly created lambda.
    fun off(event: String, listener: (payload: String) -> Unit) {
        listeners[event]?.remove(listener)
    }

    override fun onMessage(event: String, payload: String) {
        listeners[event]?.forEach { it(payload) }
    }

    // Releases every registered listener. Used when the owner (e.g. a
    // destroyed BrowserInstance) needs to break references held by listener
    // closures, which may otherwise keep an Activity or other caller state
    // reachable for longer than expected.
    fun clear() {
        listeners.clear()
    }
}

package com.opencef.core

interface BrowserInstance {
    val handle: BrowserHandle
    val url: String
    val isVisible: Boolean
    val isLoading: Boolean
    val isDestroyed: Boolean

    // No validation, parsing, or rewriting is done here: Kotlin's
    // non-nullable String already rules out null at compile time, and
    // anything beyond that (malformed/empty/relative URLs) is passed
    // through as-is to the platform loader. url reflects this value
    // immediately, and is also kept in sync with organic in-page
    // navigation (see the "navigation" event below).
    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()

    // hide() must preserve the instance and its loaded page state -
    // it is a visibility toggle, not a detach/destroy.
    fun show()
    fun hide()

    // width/height are plain pixel dimensions applied directly to the
    // platform's layout params; no separate sizing unit or layout system
    // is introduced here.
    fun resize(width: Int, height: Int)

    fun executeJS(code: String)

    // Browser lifecycle events (created, loading, loaded, navigation,
    // error, visibilityChanged, destroyed) and JavaScript -> native
    // messages sent via window.cef.emit() on the page both arrive here as
    // (event, jsonPayload) - one small event channel, not two. Each
    // BrowserInstance has its own private channel, so events/messages from
    // one browser are never visible to another browser's listeners.
    fun on(event: String, listener: (payload: String) -> Unit)
    fun off(event: String, listener: (payload: String) -> Unit)

    // Native -> JavaScript: dispatches to window.cef.on(event, ...)
    // listeners registered on the currently loaded page, if any.
    fun emit(event: String, payload: String)

    fun destroy()
}

// Lifecycle contract: once isDestroyed is true, every method above except
// off() is a deterministic no-op - none of them throw, and none of them
// touch the underlying platform view or retain new listener references.
// off() always still works, since removing a reference can never leak.
// destroy() itself remains idempotent.

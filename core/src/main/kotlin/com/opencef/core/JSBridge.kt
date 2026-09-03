package com.opencef.core

interface JSBridge {
    fun onMessage(event: String, payload: String)
}

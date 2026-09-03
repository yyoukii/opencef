package com.opencef.core

interface BrowserManager {
    fun create(url: String): BrowserInstance
    fun destroy(handle: BrowserHandle)
    fun destroyAll()
    fun get(handle: BrowserHandle): BrowserInstance?
}

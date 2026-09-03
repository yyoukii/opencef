package com.opencef.demo

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.opencef.android.AndroidBrowserManager
import com.opencef.android.JsonMessageSerializer
import com.opencef.core.BrowserInstance
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val serializer = JsonMessageSerializer()
    private lateinit var browserManager: AndroidBrowserManager
    private lateinit var browser: BrowserInstance
    private lateinit var browserContainer: FrameLayout
    private lateinit var statusLabel: TextView
    private var resizedSmall = false
    private var onPageTwo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusLabel = TextView(this)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        browserContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(statusLabel)
            addView(controls)
            addView(browserContainer)
        }
        setContentView(root)

        browserManager = AndroidBrowserManager(this)
        browser = browserManager.create("file:///android_asset/www/index.html")
        browserManager.attachTo(browser.handle, browserContainer)

        wireBrowserEvents()

        addControlButton(controls, "Reload") { browser.reload() }
        addControlButton(controls, "Stop") { browser.stopLoading() }
        addControlButton(controls, "Hide/Show") { toggleVisibility() }
        addControlButton(controls, "Resize") { toggleResize() }
        addControlButton(controls, "Page 2") { toggleNavigation() }
    }

    // Every listener here that touches the status TextView wraps the update
    // in runOnUiThread(): "loading"/"loaded" happen to already fire on the
    // UI thread (they come from WebViewClient), but "login" and
    // "demoMessage" arrive on a WebView-managed thread (see WebViewJsBridge)
    // - the same rule applies to all of them regardless, rather than relying
    // on knowing which thread each specific event happens to fire from.
    private fun wireBrowserEvents() {
        browser.on("loading") { runOnUiThread { statusLabel.text = "Loading..." } }
        browser.on("loaded") { runOnUiThread { statusLabel.text = "Ready" } }
        browser.on("login") { payload -> handleLogin(payload) }
        browser.on("demoMessage") { payload -> handleDemoMessage(payload) }
    }

    private fun toggleVisibility() {
        if (browser.isVisible) browser.hide() else browser.show()
    }

    private fun toggleResize() {
        resizedSmall = !resizedSmall
        if (resizedSmall) {
            browser.resize(browserContainer.width / 2, browserContainer.height / 2)
        } else {
            browser.resize(browserContainer.width, browserContainer.height)
        }
    }

    private fun toggleNavigation() {
        onPageTwo = !onPageTwo
        val target = if (onPageTwo) {
            "file:///android_asset/www/page2.html"
        } else {
            "file:///android_asset/www/index.html"
        }
        browser.loadUrl(target)
    }

    private fun addControlButton(row: LinearLayout, label: String, onClick: () -> Unit) {
        row.addView(
            Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            },
        )
    }

    private fun handleLogin(payload: String) {
        val data = serializer.fromJson(payload)
        val username = data["username"] as? String ?: "unknown"
        val message = JSONObject.quote("Welcome, $username! Native received your login.")
        browser.executeJS("window.demo && window.demo.onNativeMessage($message)")
    }

    private fun handleDemoMessage(payload: String) {
        val data = serializer.fromJson(payload)
        val text = data["text"] as? String ?: ""
        val response = mapOf("text" to "Native saw: $text")
        browser.emit("nativeMessage", serializer.toJson(response))
    }

    override fun onDestroy() {
        browserManager.destroyAll()
        super.onDestroy()
    }
}

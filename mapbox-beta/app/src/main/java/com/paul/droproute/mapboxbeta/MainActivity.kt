package com.paul.droproute.mapboxbeta

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val navRequest = 1301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.allowUniversalAccessFromFileURLs = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, true, false)
            }
        }
        web.addJavascriptInterface(NativeMapBridge(), "NativeMap")
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
        }
        web.loadUrl("file:///android_asset/index.html")
    }

    inner class NativeMapBridge {
        @JavascriptInterface fun isAvailable(): Boolean = true

        @JavascriptInterface fun openNavigation(payload: String) {
            runOnUiThread {
                val i = Intent(this@MainActivity, NativeNavigationActivity::class.java)
                i.putExtra("payload", payload)
                startActivityForResult(i, navRequest)
            }
        }

        @JavascriptInterface fun openOfflineMaps(token: String) {
            runOnUiThread {
                val i = Intent(this@MainActivity, OfflineMapActivity::class.java)
                i.putExtra("token", token)
                startActivity(i)
            }
        }
    }

    @Deprecated("legacy result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != navRequest || resultCode != RESULT_OK || data == null) return
        val action = data.getStringExtra("action") ?: "back"
        val lat = data.getDoubleExtra("lat", Double.NaN)
        val lon = data.getDoubleExtra("lon", Double.NaN)
        val acc = data.getDoubleExtra("acc", 9999.0)
        val js = "window.nativeMapAction && window.nativeMapAction(${JSONObject.quote(action)},${if (lat.isFinite()) lat else "null"},${if (lon.isFinite()) lon else "null"},${if (acc.isFinite()) acc else 9999.0});"
        web.evaluateJavascript(js, null)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

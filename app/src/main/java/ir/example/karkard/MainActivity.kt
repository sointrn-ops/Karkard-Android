package ir.example.karkard

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.util.AtomicFile
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var recordsFile: AtomicFile

    inner class KarbanStorage {
        @JavascriptInterface
        fun hasSavedRecords(): Boolean = recordsFile.baseFile.exists()

        @JavascriptInterface
        fun loadRecords(): String {
            return try {
                if (!recordsFile.baseFile.exists()) return ""
                recordsFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun saveRecords(json: String): Boolean {
            if (json.length > 10_000_000) return false
            val stream = try {
                recordsFile.startWrite()
            } catch (_: Exception) {
                return false
            }

            return try {
                stream.write(json.toByteArray(Charsets.UTF_8))
                recordsFile.finishWrite(stream)
                true
            } catch (_: Exception) {
                recordsFile.failWrite(stream)
                false
            }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recordsFile = AtomicFile(File(filesDir, "karban_records.json"))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            addJavascriptInterface(KarbanStorage(), "KarbanStorage")
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}

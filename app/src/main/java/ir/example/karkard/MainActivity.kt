package ir.example.karkard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var recordsFile: AtomicFile

    companion object {
        private const val REQUEST_EXPORT_BACKUP = 7101
        private const val REQUEST_IMPORT_BACKUP = 7102
        private const val KEY_ALIAS = "karban_records_key_v1"
        private const val ENCRYPTED_PREFIX = "KARBAN1:"
        private const val MAX_JSON_CHARS = 10_000_000
        private const val STORAGE_ERROR = "__KARBAN_STORAGE_ERROR__"
    }

    inner class KarbanStorage {
        @JavascriptInterface
        fun loadRecords(): String {
            return try {
                val stored = recordsFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (stored.startsWith(ENCRYPTED_PREFIX)) {
                    decryptRecords(stored.removePrefix(ENCRYPTED_PREFIX))
                } else {
                    // Upgrade older plaintext app-private data to encrypted storage.
                    if (stored.isNotEmpty() && !saveRecords(stored)) return STORAGE_ERROR
                    stored
                }
            } catch (_: FileNotFoundException) {
                ""
            } catch (error: Exception) {
                android.util.Log.e("KARBAN", "Could not read encrypted records", error)
                STORAGE_ERROR
            }
        }

        @JavascriptInterface
        fun saveRecords(json: String): Boolean {
            if (json.length > MAX_JSON_CHARS) return false
            val encrypted = try {
                ENCRYPTED_PREFIX + encryptRecords(json)
            } catch (error: Exception) {
                android.util.Log.e("KARBAN", "Could not encrypt records", error)
                return false
            }
            val stream = try {
                recordsFile.startWrite()
            } catch (_: Exception) {
                return false
            }

            return try {
                stream.write(encrypted.toByteArray(Charsets.UTF_8))
                recordsFile.finishWrite(stream)
                true
            } catch (_: Exception) {
                recordsFile.failWrite(stream)
                false
            }
        }

        @JavascriptInterface
        fun exportBackup(json: String) {
            if (json.length > 10_000_000) return
            runOnUiThread {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "KARBAN-backup.json")
                }
                pendingBackupJson = json
                startActivityForResult(intent, REQUEST_EXPORT_BACKUP)
            }
        }

        @JavascriptInterface
        fun importBackup() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                startActivityForResult(intent, REQUEST_IMPORT_BACKUP)
            }
        }
    }

    private var pendingBackupJson: String? = null

    private fun getRecordsKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptRecords(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getRecordsKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptRecords(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > 12) { "Invalid encrypted record data" }
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getRecordsKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }


    private fun handleAppNavigation(url: String): Boolean {
        if (url === "file:///android_asset/index.html" || url.startsWith("file:///android_asset/index.html#")) return false
        if (url.startsWith("mailto:") || url.startsWith("tel:")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                // No compatible external app.
            }
        }
        return true
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recordsFile = AtomicFile(File(filesDir, "karban_records.json"))
        WebView.setWebContentsDebuggingEnabled(false)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            addJavascriptInterface(KarbanStorage(), "KarbanStorage")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return handleAppNavigation(request.url.toString())
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleAppNavigation(url)
                }
            }
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

    @Deprecated("Deprecated in Android API  Activity result flow retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            pendingBackupJson = null
            return
        }

        val uri: Uri = data.data!!
        when (requestCode) {
            REQUEST_EXPORT_BACKUP -> {
                val json = pendingBackupJson ?: return
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Unable to open backup destination")
                    webView.evaluateJavascript("window.onNativeBackupExported(true)", null)
                } catch (_: Exception) {
                    webView.evaluateJavascript("window.onNativeBackupExported(false)", null)
                } finally {
                    pendingBackupJson = null
                }
            }
            REQUEST_IMPORT_BACKUP -> {
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("Unable to read backup")
                    if (json.length > 10_000_000) throw IllegalArgumentException("Backup file is too large")
                    val quoted = JSONObject.quote(json)
                    webView.evaluateJavascript("window.importBackupFromNative($quoted)", null)
                } catch (_: Exception) {
                    webView.evaluateJavascript("window.onNativeBackupImported(false)", null)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}

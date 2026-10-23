package com.ahmdrb.dictionary

import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var database: SQLiteDatabase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(DictionaryBridge(), "DictionaryDB")
        setContentView(webView)

        webView.loadData(
            "<html><meta charset='utf-8'><body style='font-family:sans-serif;padding:24px'>" +
                "<h3>Preparing offline dictionary…</h3>" +
                "<p>The dictionary is being installed on this device. Please wait.</p>" +
                "</body></html>",
            "text/html",
            "UTF-8"
        )

        executor.execute {
            try {
                val dbFile = File(filesDir, "dictionary.db")

                if (!dbFile.exists() || dbFile.length() == 0L) {
                    val tempFile = File(filesDir, "dictionary.db.tmp")
                    tempFile.delete()
                    assets.open("dictionary.db").use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    if (!tempFile.renameTo(dbFile)) {
                        tempFile.copyTo(dbFile, overwrite = true)
                        tempFile.delete()
                    }
                }

                val opened = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )

                val check = opened.rawQuery("PRAGMA quick_check", null)
                val valid = check.use {
                    it.moveToFirst() && it.getString(0) == "ok"
                }

                if (!valid) {
                    opened.close()
                    throw IllegalStateException("Dictionary database integrity check failed")
                }

                database = opened

                runOnUiThread {
                    webView.loadUrl("file:///android_asset/index.html")
                }
            } catch (e: Exception) {
                android.util.Log.e("DictionarySetup", "Dictionary initialization failed", e)

                val errorType = e.javaClass.simpleName
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")

                val errorMessage = (e.message ?: "(no message)")
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\\", "&#92;")
                    .replace("\n", " ")
                    .replace("\r", " ")

                runOnUiThread {
                    webView.loadData(
                        "<html><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                            "<body style='font-family:sans-serif;padding:24px;overflow-wrap:anywhere'>" +
                            "<h3>Dictionary setup failed</h3>" +
                            "<p><b>Exception:</b> " + errorType + "</p>" +
                            "<p><b>Message:</b> " + errorMessage + "</p>" +
                            "<p>Please share these error details for diagnosis.</p>" +
                            "</body></html>",
                        "text/html",
                        "UTF-8"
                    )
                }
            }
        }
    }

    inner class DictionaryBridge {
        @JavascriptInterface
        fun search(direction: String, rawQuery: String): String {
            val output = JSONArray()
            val db = database ?: return output.toString()

            if (direction != "en-id" && direction != "id-en") {
                return output.toString()
            }

            val term = rawQuery.trim().lowercase()
            if (term.isEmpty() || term.length > 200) {
                return output.toString()
            }

            val escaped = term
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")

            synchronized(db) {
                val cursor = db.rawQuery(
                    """
                    SELECT t.term, e.word, e.definition, t.is_alias
                    FROM terms t
                    JOIN entries e ON e.id = t.entry_id
                    WHERE t.direction = ?
                      AND t.normalized LIKE ? ESCAPE '!'
                    ORDER BY
                      CASE WHEN t.normalized = ? THEN 0 ELSE 1 END,
                      t.normalized,
                      t.id
                    LIMIT 50
                    """.trimIndent(),
                    arrayOf(direction, "$escaped%", term)
                )

                cursor.use {
                    while (it.moveToNext()) {
                        output.put(
                            JSONObject()
                                .put("term", it.getString(0))
                                .put("word", it.getString(1))
                                .put("definition", it.getString(2))
                                .put("alias", it.getInt(3) == 1)
                        )
                    }
                }
            }

            return output.toString()
        }
    }

    override fun onDestroy() {
        database?.close()
        executor.shutdown()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("DictionaryDB")
            webView.destroy()
        }
        super.onDestroy()
    }
}

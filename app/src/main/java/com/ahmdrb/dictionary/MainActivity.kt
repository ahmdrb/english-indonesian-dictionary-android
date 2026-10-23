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

                val needsRefresh = !dbFile.exists() ||
                    dbFile.length() == 0L ||
                    !hasCategoriesTable(dbFile)

                if (needsRefresh) {
                    val tempFile = File(filesDir, "dictionary.db.tmp")
                    tempFile.delete()

                    assets.open("dictionary.db").use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }

                    val candidate = SQLiteDatabase.openDatabase(
                        tempFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    )

                    val valid = try {
                        val check = candidate.rawQuery("PRAGMA quick_check", null)
                        val integrityOk = check.use {
                            it.moveToFirst() && it.getString(0) == "ok"
                        }
                        integrityOk && hasCategoriesTable(candidate)
                    } finally {
                        candidate.close()
                    }

                    if (!valid) {
                        tempFile.delete()
                        throw IllegalStateException(
                            "The packaged dictionary failed validation"
                        )
                    }

                    if (dbFile.exists() && !dbFile.delete()) {
                        tempFile.delete()
                        throw IllegalStateException(
                            "Could not replace the older dictionary database"
                        )
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

                if (!valid || !hasCategoriesTable(opened)) {
                    opened.close()
                    throw IllegalStateException(
                        "Dictionary database integrity or category check failed"
                    )
                }

                database = opened

                runOnUiThread {
                    webView.loadUrl("file:///android_asset/index.html")
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "DictionarySetup",
                    "Dictionary initialization failed",
                    e
                )

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

    private fun hasCategoriesTable(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false

        return try {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            try {
                hasCategoriesTable(db)
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasCategoriesTable(db: SQLiteDatabase): Boolean {
        val cursor = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='categories'",
            null
        )
        return cursor.use { it.moveToFirst() }
    }

    inner class DictionaryBridge {
        @JavascriptInterface
        fun categories(): String {
            val output = JSONArray()
            val db = database ?: return output.toString()

            synchronized(db) {
                val cursor = db.rawQuery(
                    """
                    SELECT c.slug, c.name, p.slug,
                           COUNT(DISTINCT ce.entry_id)
                    FROM categories c
                    LEFT JOIN categories p ON p.id = c.parent_id
                    LEFT JOIN category_entries ce ON ce.category_id = c.id
                    GROUP BY c.id, c.slug, c.name, p.slug
                    ORDER BY CASE WHEN c.parent_id IS NULL THEN 0 ELSE 1 END,
                             COALESCE(p.name, c.name), c.name
                    """.trimIndent(),
                    null
                )

                cursor.use {
                    while (it.moveToNext()) {
                        val item = JSONObject()
                            .put("slug", it.getString(0))
                            .put("name", it.getString(1))
                            .put("count", it.getInt(3))

                        if (it.isNull(2)) {
                            item.put("parentSlug", JSONObject.NULL)
                        } else {
                            item.put("parentSlug", it.getString(2))
                        }

                        output.put(item)
                    }
                }
            }

            return output.toString()
        }

        @JavascriptInterface
        fun browseLetter(
            direction: String,
            letter: String,
            offset: Int,
            pageSize: Int
        ): String {
            val output = JSONArray()
            val db = database ?: return output.toString()

            if ((direction != "en-id" && direction != "id-en") ||
                letter.length != 1 ||
                letter[0].uppercaseChar() !in 'A'..'Z' ||
                offset < 0 || offset > 2000000 ||
                pageSize !in 1..100) {
                return output.toString()
            }

            synchronized(db) {
                val cursor = db.rawQuery(
                    """
                    SELECT t.term, e.word, e.definition, t.is_alias
                    FROM terms t
                    JOIN entries e ON e.id = t.entry_id
                    WHERE t.direction = ?
                      AND t.normalized LIKE ?
                    ORDER BY t.normalized, t.id
                    LIMIT ? OFFSET ?
                    """.trimIndent(),
                    arrayOf(
                        direction,
                        letter.lowercase() + "%",
                        pageSize.toString(),
                        offset.toString()
                    )
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

        @JavascriptInterface
        fun search(direction: String, rawQuery: String): String {
            return searchInternal(direction, rawQuery, null)
        }

        @JavascriptInterface
        fun searchInCategory(
            direction: String,
            rawQuery: String,
            categorySlug: String
        ): String {
            if (categorySlug.isBlank() || categorySlug.length > 100) {
                return JSONArray().toString()
            }
            return searchInternal(direction, rawQuery, categorySlug)
        }

        private fun searchInternal(
            direction: String,
            rawQuery: String,
            categorySlug: String?
        ): String {
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

            val categoryJoin = if (categorySlug != null) {
                "JOIN category_entries ce ON ce.entry_id = e.id " +
                    "JOIN categories c ON c.id = ce.category_id"
            } else {
                ""
            }

            val categoryFilter = if (categorySlug != null) {
                "AND c.slug = ?"
            } else {
                ""
            }

            synchronized(db) {
                val cursor = db.rawQuery(
                    """
                    SELECT t.term, e.word, e.definition, t.is_alias
                    FROM terms t
                    JOIN entries e ON e.id = t.entry_id
                    $categoryJoin
                    WHERE t.direction = ?
                      AND t.normalized LIKE ? ESCAPE '!'
                      $categoryFilter
                    ORDER BY
                      CASE WHEN t.normalized = ? THEN 0 ELSE 1 END,
                      t.normalized,
                      t.id
                    LIMIT 50
                    """.trimIndent(),
                    if (categorySlug == null) {
                        arrayOf(direction, "$escaped%", term)
                    } else {
                        arrayOf(direction, "$escaped%", categorySlug, term)
                    }
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

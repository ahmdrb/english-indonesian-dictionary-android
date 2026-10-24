package com.ahmdrb.dictionary

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val packagedDictionaryVersion = 3
    private val REQUEST_EXPORT_BACKUP = 7401
    private val REQUEST_IMPORT_BACKUP = 7402
    private val REQUEST_IMPORT_PDF = 7403
    private var pendingBackupJson: String? = null
    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var database: SQLiteDatabase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(DictionaryBridge(), "DictionaryDB")
        webView.addJavascriptInterface(BackupBridge(), "BackupBridge")
        webView.addJavascriptInterface(PdfImportBridge(), "PdfImportBridge")
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

                val savedDbVersion = getSharedPreferences(
                    "dictionary_database", MODE_PRIVATE
                ).getInt("version", 0)

                val needsRefresh = !dbFile.exists() ||
                    dbFile.length() == 0L ||
                    !hasCategoriesTable(dbFile) ||
                    savedDbVersion < packagedDictionaryVersion

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

                getSharedPreferences(
                    "dictionary_database", MODE_PRIVATE
                ).edit().putInt("version", packagedDictionaryVersion).apply()

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
                    SELECT
                        MIN(t.term) AS term,
                        MIN(e.word) AS word,
                        MIN(e.definition) AS definition,
                        MIN(t.is_alias) AS is_alias
                    FROM terms t
                    JOIN entries e ON e.id = t.entry_id
                    WHERE t.direction = ?
                      AND t.normalized LIKE ?
                      AND LENGTH(TRIM(t.term)) > 0
                    GROUP BY LOWER(TRIM(t.term))
                    ORDER BY MIN(t.is_alias), MIN(t.normalized), LOWER(TRIM(t.term))
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


    inner class BackupBridge {
        @JavascriptInterface
        fun exportJson(json: String) {
            if (json.isBlank() || json.length > 512_000) {
                notifyBackupError("The backup is empty or exceeds the 512 KB safety limit.")
                return
            }

            runOnUiThread {
                try {
                    pendingBackupJson = json
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(
                            Intent.EXTRA_TITLE,
                            "english-indonesian-dictionary-backup.json"
                        )
                    }
                    startActivityForResult(intent, REQUEST_EXPORT_BACKUP)
                } catch (e: Exception) {
                    pendingBackupJson = null
                    notifyBackupError(
                        "Could not open the save dialog: " +
                            (e.message ?: "Unknown error")
                    )
                }
            }
        }

        @JavascriptInterface
        fun importJson() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            arrayOf(
                                "application/json",
                                "text/json",
                                "text/plain"
                            )
                        )
                    }
                    startActivityForResult(intent, REQUEST_IMPORT_BACKUP)
                } catch (e: Exception) {
                    notifyBackupError(
                        "Could not open the file picker: " +
                            (e.message ?: "Unknown error")
                    )
                }
            }
        }
    }


    inner class PdfImportBridge {
        @JavascriptInterface
        fun choosePdf() {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/pdf"
                    }
                    startActivityForResult(intent, REQUEST_IMPORT_PDF)
                } catch (e: Exception) {
                    notifyPdfError(
                        "Could not open the PDF picker: " +
                            (e.message ?: "Unknown error")
                    )
                }
            }
        }
    }

    private fun notifyPdfStatus(message: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.onNativePdfStatus(" +
                        JSONObject.quote(message) + ")",
                    null
                )
            }
        }
    }

    private fun notifyPdfError(message: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.onNativePdfError(" +
                        JSONObject.quote(message) + ")",
                    null
                )
            }
        }
    }

    private fun processPdf(uri: Uri) {
        executor.execute {
            try {
                notifyPdfStatus("Opening PDF…")

                PDFBoxResourceLoader.init(applicationContext)

                val extracted = StringBuilder()
                var pageCount = 0

                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        throw IllegalStateException(
                            "Could not open the selected PDF."
                        )
                    }

                    PDDocument.load(input).use { document ->
                        pageCount = document.numberOfPages

                        if (pageCount == 0) {
                            throw IllegalArgumentException(
                                "The PDF contains no pages."
                            )
                        }
                        if (pageCount > 2000) {
                            throw IllegalArgumentException(
                                "This PDF has more than 2,000 pages. " +
                                    "Please use a smaller PDF."
                            )
                        }

                        val stripper = PDFTextStripper()

                        for (page in 1..pageCount) {
                            stripper.startPage = page
                            stripper.endPage = page
                            extracted.append(stripper.getText(document))
                                .append('\n')

                            if (extracted.length > 5_000_000) {
                                throw IllegalArgumentException(
                                    "Extracted text exceeds the 5 MB safety " +
                                        "limit. Please use a smaller PDF."
                                )
                            }

                            if (page == 1 || page % 5 == 0 ||
                                page == pageCount) {
                                notifyPdfStatus(
                                    "Reading PDF: page $page of $pageCount…"
                                )
                            }
                        }
                    }
                }

                if (extracted.isBlank()) {
                    throw IllegalArgumentException(
                        "No selectable text was found. This may be a " +
                            "scanned PDF; image OCR is not supported yet."
                    )
                }

                val db = database ?: throw IllegalStateException(
                    "The dictionary database is not ready."
                )

                notifyPdfStatus("Finding vocabulary in the dictionary…")

                val tokenRegex =
                    Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")

                val tokens = tokenRegex.findAll(extracted.toString())
                    .map { it.value.lowercase() }
                    .toList()

                val stopWords = setOf(
                    // Articles, pronouns, and basic grammar words
                    "a", "an", "the", "and", "or", "but", "if", "then",
                    "else", "when", "while", "where", "what", "which",
                    "who", "whom", "whose", "why", "how", "is", "am",
                    "are", "was", "were", "be", "been", "being", "do",
                    "does", "did", "have", "has", "had", "can", "could",
                    "will", "would", "shall", "should", "may", "might",
                    "must", "to", "of", "in", "on", "at", "by", "for",
                    "from", "with", "without", "about", "as", "into",
                    "through", "over", "under", "between", "after",
                    "before", "up", "down", "out", "off", "not", "no",
                    "nor", "so", "than", "too", "very", "it", "its",
                    "this", "that", "these", "those", "i", "me", "my",
                    "mine", "we", "us", "our", "ours", "you", "your",
                    "yours", "he", "him", "his", "she", "her", "hers",
                    "they", "them", "their", "theirs", "there", "here",
                    "all", "any", "some", "each", "every", "both",
                    "either", "neither", "another", "others", "much",
                    "many", "more", "most", "other", "such", "only",
                    "own", "same", "also", "just", "now", "once",
                    "again", "still", "already", "because", "although",
                    "though", "however", "therefore", "thus", "yet",

                    // Common numbers, time words, and quantities
                    "one", "two", "three", "four", "five", "six",
                    "seven", "eight", "nine", "ten", "first", "second",
                    "third", "last", "next", "few", "several", "enough",
                    "less", "least", "lot", "lots", "hundred", "thousand",
                    "day", "days", "week", "weeks", "month", "months",
                    "year", "years", "today", "tomorrow", "yesterday",
                    "time", "times", "hour", "hours", "minute", "minutes",
                    "morning", "afternoon", "evening", "night", "nights",
                    "always", "never", "often", "sometimes", "usually",
                    "soon", "later", "early", "late", "recently",

                    // Common everyday verbs
                    "become", "became", "get", "gets", "got", "gotten",
                    "getting", "make", "makes", "made", "making", "take",
                    "takes", "took", "taken", "taking", "come", "comes",
                    "came", "coming", "go", "goes", "went", "gone", "going",
                    "know", "knows", "knew", "known", "knowing", "think",
                    "thinks", "thought", "thinking", "see", "sees", "saw",
                    "seen", "seeing", "look", "looks", "looked", "looking",
                    "use", "uses", "used", "using", "find", "finds", "found",
                    "give", "gives", "gave", "given", "giving", "tell",
                    "tells", "told", "telling", "ask", "asks", "asked",
                    "asking", "try", "tries", "tried", "trying", "need",
                    "needs", "needed", "needing", "want", "wants", "wanted",
                    "like", "likes", "liked", "liking", "love", "loves",
                    "loved", "feel", "feels", "felt", "feeling", "seem",
                    "seems", "seemed", "keep", "keeps", "kept", "let",
                    "lets", "leave", "leaves", "left", "put", "puts",
                    "set", "sets", "start", "starts", "started", "stop",
                    "stops", "stopped", "turn", "turns", "turned", "move",
                    "moves", "moved", "play", "plays", "played", "run",
                    "runs", "ran", "walk", "walks", "walked", "sit", "sat",
                    "stand", "stands", "stood", "bring", "brings", "brought",
                    "begin", "begins", "began", "begun", "show", "shows",
                    "showed", "shown", "hear", "hears", "heard", "listen",
                    "listens", "listened", "speak", "speaks", "spoke",
                    "spoken", "talk", "talks", "talked", "read", "reads",
                    "write", "writes", "wrote", "written", "learn", "learns",
                    "learned", "teach", "teaches", "taught", "work", "works",
                    "worked", "working", "live", "lives", "lived", "happen",
                    "happens", "happened", "help", "helps", "helped", "call",
                    "calls", "called", "ask", "asked", "wait", "waits",
                    "waited", "follow", "follows", "followed", "open",
                    "opens", "opened", "close", "closes", "closed", "eat",
                    "eats", "ate", "eaten", "drink", "drinks", "drank",
                    "drunk", "sleep", "sleeps", "slept", "buy", "buys",
                    "bought", "pay", "pays", "paid", "meet", "meets",
                    "met", "send", "sends", "sent", "win", "wins", "won",
                    "lose", "loses", "lost", "change", "changes", "changed",
                    "grow", "grows", "grew", "grown", "fall", "falls",
                    "fell", "fallen", "cut", "cuts", "break", "breaks",
                    "broke", "broken", "build", "builds", "built", "carry",
                    "carries", "carried", "hold", "holds", "held", "pass",
                    "passes", "passed", "return", "returns", "returned",

                    // Common adjectives and descriptions
                    "good", "bad", "great", "best", "better", "worst",
                    "worse", "new", "old", "young", "big", "small", "large",
                    "little", "long", "short", "high", "low", "tall", "wide",
                    "narrow", "heavy", "light", "strong", "weak", "easy",
                    "easier", "hard", "difficult", "simple", "important",
                    "different", "same", "right", "wrong", "true", "false",
                    "real", "sure", "clear", "full", "empty", "free", "busy",
                    "ready", "able", "possible", "impossible", "likely",
                    "happy", "sad", "angry", "afraid", "sorry", "nice",
                    "kind", "fine", "okay", "beautiful", "pretty", "ugly",
                    "hot", "cold", "warm", "cool", "dry", "wet", "clean",
                    "dirty", "fast", "slow", "quick", "quiet", "loud",
                    "safe", "dangerous", "rich", "poor", "cheap", "expensive",
                    "close", "near", "far", "local", "whole", "main",
                    "special", "common", "normal", "usual", "available",
                    "possible", "personal", "public", "private", "sure",

                    // Common nouns and everyday objects
                    "thing", "things", "person", "people", "man", "woman",
                    "men", "women", "child", "children", "boy", "girl",
                    "friend", "friends", "family", "families", "mother",
                    "father", "mom", "dad", "parent", "parents", "son",
                    "daughter", "brother", "sister", "husband", "wife",
                    "name", "names", "word", "words", "way", "ways", "part",
                    "parts", "case", "kind", "number", "place", "home",
                    "house", "room", "door", "window", "table", "chair",
                    "bed", "floor", "wall", "building", "school", "class",
                    "student", "teacher", "book", "paper", "page", "pen",
                    "phone", "computer", "car", "bus", "train", "road",
                    "street", "city", "town", "country", "world", "area",
                    "water", "food", "bread", "rice", "milk", "coffee",
                    "tea", "money", "price", "hand", "hands", "head", "face",
                    "eye", "eyes", "ear", "ears", "mouth", "nose", "hair",
                    "body", "arm", "arms", "leg", "legs", "foot", "feet",
                    "heart", "mind", "life", "lives", "side", "end", "top",
                    "bottom", "front", "back", "center", "line", "point",
                    "group", "team", "company", "business", "job", "office",
                    "problem", "question", "answer", "idea", "story",
                    "example", "fact", "reason", "result", "information",
                    "family", "party", "government", "service", "system",
                    "power", "state", "level", "order", "history", "today",
                    "event", "war", "music", "game", "games", "picture",
                    "photo", "image", "color", "sound", "voice", "light",
                    "air", "fire", "earth", "land", "sea", "river", "tree",
                    "plant", "animal", "dog", "cat", "bird", "fish",
                    "weather", "sun", "moon", "star", "sky", "rain", "snow",
                    "wind", "morning", "afternoon", "evening", "night",

                    // Common adverbs and conversational words
                    "very", "really", "quite", "rather", "almost", "maybe",
                    "perhaps", "probably", "certainly", "actually", "basically",
                    "especially", "exactly", "simply", "quickly", "slowly",
                    "easily", "usually", "finally", "suddenly", "together",
                    "alone", "away", "around", "inside", "outside", "above",
                    "below", "forward", "backward", "here", "there", "everywhere",
                    "somewhere", "anywhere", "nowhere", "yes", "yeah", "nope",
                    "please", "thanks", "thank", "hello", "hi", "hey", "goodbye",
                    "okay", "well", "anyway", "else", "perhaps", "instead",
                    "otherwise", "meanwhile", "thereby", "thereafter",

                    // Additional words requested for exclusion
                    "understand", "understands", "understood", "understanding",
                    "goes", "went", "gone", "opens", "opened", "opening",
                    "smaller", "smallest", "windows"
                )

                // Load the offline frequency list for PDF imports only.
                // This runs on the background executor, not the UI thread.
                val frequentWords = assets.open("english-common-words.txt")
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        lines.map { it.trim().lowercase() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    }

                // Count individual-word occurrences only and keep at most
                // 60,000 distinct candidates to bound memory on large PDFs.
                val occurrences = LinkedHashMap<String, Int>()
                for (candidate in tokens) {
                    if (candidate in stopWords ||
                        candidate in frequentWords) continue
                    if (candidate.length > 100) continue

                    if (occurrences.containsKey(candidate)) {
                        occurrences[candidate] =
                            (occurrences[candidate] ?: 0) + 1
                    } else if (occurrences.size < 60000) {
                        occurrences[candidate] = 1
                    }
                }

                if (occurrences.isEmpty()) {
                    throw IllegalArgumentException(
                        "No usable English words were found in this PDF."
                    )
                }

                data class Match(
                    val term: String,
                    val word: String,
                    val definition: String,
                    val normalized: String,
                    val alias: Boolean,
                    val occurrences: Int
                )

                val matches = LinkedHashMap<String, Match>()
                val candidates = occurrences.keys.toList()
                val chunks = candidates.chunked(250)

                synchronized(db) {
                    chunks.forEachIndexed { chunkIndex, chunk ->
                        val placeholders =
                            chunk.joinToString(",") { "?" }

                        val sql = """
                            SELECT t.term, e.word, e.definition,
                                   t.normalized, t.is_alias
                            FROM terms t
                            JOIN entries e ON e.id = t.entry_id
                            WHERE t.direction = 'en-id'
                              AND t.normalized IN ($placeholders)
                            ORDER BY t.is_alias ASC, t.id ASC
                        """.trimIndent()

                        val cursor = db.rawQuery(sql, chunk.toTypedArray())
                        cursor.use {
                            while (it.moveToNext()) {
                                val term = it.getString(0) ?: continue
                                val word = it.getString(1) ?: term
                                val definition = it.getString(2) ?: ""
                                val normalized = it.getString(3)
                                    ?.trim()?.lowercase() ?: continue
                                val alias = it.getInt(4) != 0

                                if (!occurrences.containsKey(normalized)) {
                                    continue
                                }

                                val previous = matches[normalized]
                                if (previous == null ||
                                    (previous.alias && !alias)) {
                                    matches[normalized] = Match(
                                        term = term,
                                        word = word,
                                        definition = definition,
                                        normalized = normalized,
                                        alias = alias,
                                        occurrences =
                                            occurrences[normalized] ?: 1
                                    )
                                }
                            }
                        }

                        if (chunkIndex % 10 == 0 ||
                            chunkIndex == chunks.lastIndex) {
                            notifyPdfStatus(
                                "Matching dictionary terms: " +
                                    "${chunkIndex + 1} of ${chunks.size} batches…"
                            )
                        }
                    }
                }

                val ranked = matches.values.sortedWith(
                    compareByDescending<Match> {
                        it.normalized.split(Regex("\\s+")).size
                    }.thenByDescending {
                        it.occurrences
                    }.thenBy {
                        it.normalized
                    }
                ).take(1000)

                val output = JSONArray()
                for (match in ranked) {
                    output.put(
                        JSONObject()
                            .put("term", match.term)
                            .put("word", match.word)
                            .put("definition", match.definition)
                            .put("direction", "en-id")
                            .put("occurrences", match.occurrences)
                    )
                }

                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onNativePdfMatches(" +
                            JSONObject.quote(output.toString()) + "," +
                            pageCount + "," + tokens.size + ")",
                        null
                    )
                }
            } catch (e: Exception) {
                notifyPdfError(
                    e.message ?: "Could not read or process this PDF."
                )
            }
        }
    }

    private fun notifyBackupError(message: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.onNativeBackupError(" +
                        JSONObject.quote(message) + ")",
                    null
                )
            }
        }
    }

    @Deprecated("Uses the document picker result callback for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_EXPORT_BACKUP &&
            requestCode != REQUEST_IMPORT_BACKUP &&
            requestCode != REQUEST_IMPORT_PDF) {
            return
        }

        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            if (requestCode == REQUEST_EXPORT_BACKUP) {
                pendingBackupJson = null
            }
            return
        }

        val uri: Uri = data.data!!

        when (requestCode) {
            REQUEST_EXPORT_BACKUP -> {
                val json = pendingBackupJson
                pendingBackupJson = null

                if (json == null) {
                    notifyBackupError("There is no backup data ready to save.")
                    return
                }

                try {
                    contentResolver.openOutputStream(uri, "wt").use { output ->
                        if (output == null) {
                            throw IllegalStateException(
                                "Could not open the selected file for writing."
                            )
                        }
                        output.write(json.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }

                    webView.evaluateJavascript(
                        "window.onNativeBackupExported()",
                        null
                    )
                } catch (e: Exception) {
                    notifyBackupError(
                        "Could not save the backup: " +
                            (e.message ?: "Unknown error")
                    )
                }
            }

            REQUEST_IMPORT_BACKUP -> {
                try {
                    val json = contentResolver.openInputStream(uri).use { input ->
                        if (input == null) {
                            throw IllegalStateException(
                                "Could not open the selected backup file."
                            )
                        }

                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 512_000) {
                                throw IllegalArgumentException(
                                    "The selected backup exceeds the 512 KB safety limit."
                                )
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    }

                    webView.evaluateJavascript(
                        "window.onNativeBackupFileLoaded(" +
                            JSONObject.quote(json) + ")",
                        null
                    )
                } catch (e: Exception) {
                    notifyBackupError(
                        "Could not read the selected file: " +
                            (e.message ?: "Unknown error")
                    )
                }
            }

            REQUEST_IMPORT_PDF -> processPdf(uri)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.handleAndroidBack ? window.handleAndroidBack() : false"
            ) { result ->
                if (result != "true") {
                    finish()
                }
            }
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        database?.close()
        executor.shutdown()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("DictionaryDB")
            webView.removeJavascriptInterface("PdfImportBridge")
            webView.destroy()
        }
        super.onDestroy()
    }
}

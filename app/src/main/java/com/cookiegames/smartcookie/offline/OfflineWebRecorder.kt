package com.cookiegames.smartcookie.offline

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * High-performance, lightweight engine for saving web pages and recording
 * dynamic web games / web applications for 100% offline gameplay and browsing.
 */
object OfflineWebRecorder {

    private const val TAG = "OfflineWebRecorder"
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"

    data class SavedWebItem(
        val id: String,
        val title: String,
        val originalUrl: String,
        val date: Long,
        val resourceCount: Int,
        val entryFile: File,
        val dir: File,
        val isGamePackage: Boolean
    )

    data class RecordingSession(
        val id: String,
        var title: String,
        var initialUrl: String,
        val host: String,
        val dir: File,
        val capturedCount: AtomicInteger = AtomicInteger(0),
        val capturedUrls: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
        val startTime: Long = System.currentTimeMillis()
    )

    @Volatile var isRecording: Boolean = false
        private set

    @Volatile var currentSession: RecordingSession? = null
        private set

    @Volatile var activePlaybackDir: File? = null
    @Volatile private var activePlaybackMap: Map<String, String>? = null

    var onResourceCapturedListener: ((Int) -> Unit)? = null

    private fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            BigInteger(1, md.digest(input.toByteArray(Charsets.UTF_8))).toString(16).padStart(32, '0')
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    fun getMimeType(url: String, defaultMime: String = "application/octet-stream"): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val ext = MimeTypeMap.getFileExtensionFromUrl(clean).toLowerCase(Locale.ROOT)
        return when (ext) {
            "wasm" -> "application/wasm"
            "json" -> "application/json"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "mp3" -> "audio/mpeg"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mht", "mhtml" -> "multipart/related"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: defaultMime
        }
    }

    private fun getInternalDir(context: Context, name: String): File {
        val dir = File(context.filesDir, name)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun unescapeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            val tokener = JSONTokener(raw)
            val obj = tokener.nextValue()
            if (obj is String) obj else raw
        } catch (e: Exception) {
            raw
        }
    }

    private fun injectBaseHref(html: String, baseUrl: String): String {
        if (baseUrl.isBlank()) return html
        val baseTag = "<base href=\"$baseUrl\">"
        val headIdx = html.indexOf("<head", ignoreCase = true)
        if (headIdx != -1) {
            val closeHeadTag = html.indexOf('>', headIdx)
            if (closeHeadTag != -1) {
                return html.substring(0, closeHeadTag + 1) + "\n" + baseTag + "\n" + html.substring(closeHeadTag + 1)
            }
        }
        return "$baseTag\n$html"
    }

    /**
     * Instantly saves the current web page as an HTML snapshot with base href,
     * ensuring it renders natively without downloading as a binary file.
     */
    fun savePageSnapshot(
        context: Context,
        webView: WebView?,
        title: String,
        currentUrl: String,
        callback: (File?) -> Unit
    ) {
        if (webView == null) {
            callback(null)
            return
        }
        val safeTitle = title.take(30).replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ_ -]"), "_").trim()
        val pagesDir = getInternalDir(context, "saved_pages")
        val timestamp = System.currentTimeMillis()
        val baseName = "${if (safeTitle.isBlank()) "pagina" else safeTitle}_$timestamp"
        val htmlFile = File(pagesDir, "$baseName.html")
        val mhtFile = File(pagesDir, "$baseName.mht")

        // 1. Save MHT as background archive
        try {
            webView.saveWebArchive(mhtFile.absolutePath, false, null)
        } catch (e: Exception) {
            Log.e(TAG, "saveWebArchive error: ${e.message}")
        }

        // 2. Extract DOM HTML for native rendering
        try {
            webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { rawResult ->
                var content = unescapeJsString(rawResult)
                if (content.isNotBlank()) {
                    content = injectBaseHref(content, currentUrl)
                    val fullDoc = "<!DOCTYPE html>\n$content"
                    try {
                        htmlFile.writeText(fullDoc, Charsets.UTF_8)
                        callback(htmlFile)
                        return@evaluateJavascript
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing html snapshot: ${e.message}")
                    }
                }
                if (mhtFile.exists() && mhtFile.length() > 0) {
                    callback(mhtFile)
                } else {
                    callback(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluateJavascript error: ${e.message}")
            if (mhtFile.exists() && mhtFile.length() > 0) {
                callback(mhtFile)
            } else {
                callback(null)
            }
        }
    }

    /**
     * Starts an offline recording session for the given web page / game.
     */
    fun startRecording(
        context: Context,
        webView: WebView?,
        title: String,
        callback: (Boolean) -> Unit
    ) {
        if (webView == null || isRecording) {
            callback(false)
            return
        }
        val url = webView.url ?: ""
        if (url.isBlank() || url.startsWith("file:") || url.startsWith("about:")) {
            callback(false)
            return
        }

        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val host = uri?.host ?: "game"
        val id = "game_${System.currentTimeMillis()}"
        val gamesDir = getInternalDir(context, "saved_offline_webs")
        val sessionDir = File(gamesDir, id)
        if (!sessionDir.exists()) sessionDir.mkdirs()

        // Save initial archive
        val initialMht = File(sessionDir, "archive.mht")
        try {
            webView.saveWebArchive(initialMht.absolutePath, false, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving initial archive: ${e.message}")
        }

        // Save initial HTML snapshot
        val initialHtml = File(sessionDir, "index.html")
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { rawResult ->
            val content = unescapeJsString(rawResult)
            if (content.isNotBlank()) {
                try {
                    initialHtml.writeText("<!DOCTYPE html>\n" + injectBaseHref(content, url), Charsets.UTF_8)
                } catch (_: Exception) {}
            }
        }

        val session = RecordingSession(
            id = id,
            title = if (title.isBlank()) host else title,
            initialUrl = url,
            host = host,
            dir = sessionDir
        )

        currentSession = session
        isRecording = true

        saveManifest(session, isFinal = false)
        callback(true)
    }

    /**
     * Notifies the recorder that the page navigated or changed title during recording.
     */
    fun onPageChanged(url: String?, title: String?) {
        val session = currentSession ?: return
        if (url != null && !url.startsWith("file:") && !url.startsWith("about:")) {
            session.initialUrl = url
            if (!title.isNullOrBlank()) {
                session.title = title
            }
        }
    }

    /**
     * Stops the current offline recording session and seals the package with full HTML + assets.
     */
    fun stopRecording(
        context: Context,
        webView: WebView?,
        callback: (SavedWebItem?) -> Unit
    ) {
        val session = currentSession
        if (session == null) {
            callback(null)
            return
        }
        isRecording = false

        val entryFile = File(session.dir, "index.html")
        val archiveMht = File(session.dir, "archive.mht")

        val finishAction = {
            saveManifest(session, isFinal = true)
            val finalEntry = if (entryFile.exists() && entryFile.length() > 0) entryFile else archiveMht
            val item = SavedWebItem(
                id = session.id,
                title = session.title,
                originalUrl = session.initialUrl,
                date = session.startTime,
                resourceCount = session.capturedCount.get(),
                entryFile = finalEntry,
                dir = session.dir,
                isGamePackage = true
            )
            currentSession = null
            callback(item)
        }

        if (webView != null) {
            try {
                webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { rawResult ->
                    val content = unescapeJsString(rawResult)
                    if (content.isNotBlank()) {
                        try {
                            val enrichedHtml = "<!DOCTYPE html>\n" + injectBaseHref(content, session.initialUrl)
                            entryFile.writeText(enrichedHtml, Charsets.UTF_8)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing final index.html: ${e.message}")
                        }
                    }
                    finishAction()
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "evaluateJavascript on stop failed: ${e.message}")
            }
        }

        finishAction()
    }

    private fun saveManifest(session: RecordingSession, isFinal: Boolean) {
        try {
            val json = JSONObject()
            json.put("id", session.id)
            json.put("title", session.title)
            json.put("url", session.initialUrl)
            json.put("host", session.host)
            json.put("date", session.startTime)
            json.put("resourceCount", session.capturedCount.get())
            json.put("isFinal", isFinal)

            val mapping = JSONObject()
            for ((key, value) in session.capturedUrls) {
                mapping.put(key, value)
            }
            json.put("capturedMap", mapping)

            File(session.dir, "manifest.json").writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving manifest: ${e.message}")
        }
    }

    /**
     * Prepares offline playback for a saved web game.
     */
    fun preparePlayback(gameDir: File) {
        activePlaybackDir = gameDir
        activePlaybackMap = try {
            val manifestFile = File(gameDir, "manifest.json")
            if (manifestFile.exists()) {
                val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
                val mapJson = json.optJSONObject("capturedMap")
                val map = mutableMapOf<String, String>()
                mapJson?.keys()?.forEach { k ->
                    map[k] = mapJson.getString(k)
                }
                map
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Intercepts network requests to capture assets while recording,
     * or serve cached assets when playing back offline.
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val urlStr = request.url.toString()

        // 1. OFFLINE PLAYBACK MODE
        val playbackDir = activePlaybackDir
        if (playbackDir != null && playbackDir.exists()) {
            val cachedName = activePlaybackMap?.get(urlStr)
            if (cachedName != null) {
                val cachedFile = File(playbackDir, cachedName)
                if (cachedFile.exists()) {
                    return createResponseFromFile(urlStr, cachedFile)
                }
            }

            // Fallback: lookup by hash
            val hashName = "res_${md5(urlStr)}"
            val matchingFiles = playbackDir.listFiles { _, name -> name.startsWith(hashName) }
            if (matchingFiles != null && matchingFiles.isNotEmpty()) {
                return createResponseFromFile(urlStr, matchingFiles[0])
            }
        }

        // 2. ACTIVE RECORDING MODE
        if (isRecording) {
            val session = currentSession ?: return null

            if (request.method.equals("GET", ignoreCase = true)) {
                if (urlStr.startsWith("data:") || urlStr.startsWith("blob:") || urlStr.startsWith("javascript:")) {
                    return null
                }

                // If already captured, serve from local file
                val existingName = session.capturedUrls[urlStr]
                if (existingName != null) {
                    val localFile = File(session.dir, existingName)
                    if (localFile.exists()) {
                        return createResponseFromFile(urlStr, localFile)
                    }
                }

                // Download, save, and return stream
                try {
                    val urlObj = URL(urlStr)
                    val conn = urlObj.openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 10000
                    conn.instanceFollowRedirects = true

                    request.requestHeaders?.forEach { (k, v) ->
                        try { conn.setRequestProperty(k, v) } catch (_: Exception) {}
                    }

                    // Ensure essential browser headers
                    val cookie = CookieManager.getInstance().getCookie(urlStr)
                    if (!cookie.isNullOrBlank()) {
                        conn.setRequestProperty("Cookie", cookie)
                    }
                    if (conn.getRequestProperty("User-Agent").isNullOrBlank()) {
                        conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                    }
                    if (conn.getRequestProperty("Referer").isNullOrBlank() && session.initialUrl.isNotBlank()) {
                        conn.setRequestProperty("Referer", session.initialUrl)
                    }
                    conn.setRequestProperty("Accept", "*/*")

                    val code = conn.responseCode
                    if (code in 200..299) {
                        var inStream: InputStream = conn.inputStream
                        val contentEncoding = conn.contentEncoding
                        if ("gzip".equals(contentEncoding, ignoreCase = true)) {
                            inStream = GZIPInputStream(inStream)
                        }

                        val bytes = inStream.use { it.readBytes() }
                        if (bytes.isNotEmpty() && bytes.size < 60 * 1024 * 1024) { // Up to 60MB per asset
                            val cleanPath = urlStr.substringBefore('?').substringBefore('#')
                            var ext = MimeTypeMap.getFileExtensionFromUrl(cleanPath).toLowerCase(Locale.ROOT)
                            if (ext.isBlank()) {
                                ext = when (conn.contentType?.substringBefore(';')?.trim()) {
                                    "application/wasm" -> "wasm"
                                    "application/json" -> "json"
                                    "application/javascript" -> "js"
                                    "text/css" -> "css"
                                    "audio/mpeg" -> "mp3"
                                    "audio/ogg" -> "ogg"
                                    "image/png" -> "png"
                                    "image/jpeg" -> "jpg"
                                    "image/webp" -> "webp"
                                    else -> "bin"
                                }
                            }
                            val fileName = "res_${md5(urlStr)}.$ext"
                            val savedFile = File(session.dir, fileName)
                            FileOutputStream(savedFile).use { it.write(bytes) }

                            session.capturedUrls[urlStr] = fileName
                            val count = session.capturedCount.incrementAndGet()
                            onResourceCapturedListener?.invoke(count)

                            val mime = conn.contentType?.substringBefore(';') ?: getMimeType(urlStr)
                            val encoding = if ("gzip".equals(contentEncoding, ignoreCase = true)) "UTF-8" else (contentEncoding ?: "UTF-8")
                            val headers = mutableMapOf<String, String>()
                            conn.headerFields?.forEach { (k, list) ->
                                if (k != null && list.isNotEmpty() && !k.equals("Content-Encoding", ignoreCase = true)) {
                                    headers[k] = list.joinToString(", ")
                                }
                            }
                            headers["Access-Control-Allow-Origin"] = "*"
                            headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS, HEAD"
                            headers["Access-Control-Allow-Headers"] = "*"
                            return WebResourceResponse(mime, encoding, 200, "OK", headers, ByteArrayInputStream(bytes))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recording capture error for $urlStr: ${e.message}")
                }
            }
        }

        return null
    }

    private fun createResponseFromFile(url: String, file: File): WebResourceResponse? {
        return try {
            val mime = getMimeType(url)
            val stream = FileInputStream(file)
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
                "Access-Control-Allow-Headers" to "*"
            )
            WebResourceResponse(mime, "UTF-8", 200, "OK", headers, stream)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns all saved offline web pages and game packages across internal and external storage.
     */
    fun getSavedItems(context: Context): List<SavedWebItem> {
        val items = mutableListOf<SavedWebItem>()
        val seenIds = mutableSetOf<String>()

        val candidateDirs = listOfNotNull(
            File(context.filesDir, "saved_pages"),
            context.getExternalFilesDir("saved_pages")
        )

        // 1. Single snapshots (.html and .mht)
        for (pagesDir in candidateDirs) {
            if (!pagesDir.exists()) continue
            pagesDir.listFiles { _, name ->
                name.endsWith(".html", ignoreCase = true) || name.endsWith(".mht", ignoreCase = true)
            }?.forEach { f ->
                if (seenIds.add(f.nameWithoutExtension)) {
                    val nameClean = f.nameWithoutExtension.substringBeforeLast('_')
                    items.add(
                        SavedWebItem(
                            id = f.name,
                            title = nameClean.ifBlank { "Página guardada" },
                            originalUrl = f.absolutePath,
                            date = f.lastModified(),
                            resourceCount = 1,
                            entryFile = f,
                            dir = f.parentFile ?: pagesDir,
                            isGamePackage = false
                        )
                    )
                }
            }
        }

        // 2. Full offline game/web packages
        val candidateGameDirs = listOfNotNull(
            File(context.filesDir, "saved_offline_webs"),
            context.getExternalFilesDir("saved_offline_webs")
        )

        for (gamesDir in candidateGameDirs) {
            if (!gamesDir.exists()) continue
            gamesDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
                if (seenIds.add(dir.name)) {
                    var title = dir.name
                    var url = ""
                    var count = 0
                    var date = dir.lastModified()

                    val manifest = File(dir, "manifest.json")
                    if (manifest.exists()) {
                        try {
                            val json = JSONObject(manifest.readText(Charsets.UTF_8))
                            title = json.optString("title", title)
                            url = json.optString("url", "")
                            count = json.optInt("resourceCount", 0)
                            date = json.optLong("date", date)
                        } catch (_: Exception) {}
                    }

                    val indexHtml = File(dir, "index.html")
                    val indexMht = File(dir, "index.mht")
                    val archiveMht = File(dir, "archive.mht")

                    val entryFile = when {
                        indexHtml.exists() && indexHtml.length() > 0 -> indexHtml
                        indexMht.exists() && indexMht.length() > 0 -> indexMht
                        archiveMht.exists() && archiveMht.length() > 0 -> archiveMht
                        else -> indexHtml
                    }

                    if (entryFile.exists() || manifest.exists()) {
                        items.add(
                            SavedWebItem(
                                id = dir.name,
                                title = title,
                                originalUrl = url,
                                date = date,
                                resourceCount = count,
                                entryFile = entryFile,
                                dir = dir,
                                isGamePackage = true
                            )
                        )
                    }
                }
            }
        }

        items.sortByDescending { it.date }
        return items
    }

    /**
     * Deletes a saved web or game package from disk.
     */
    fun deleteSavedItem(item: SavedWebItem): Boolean {
        return try {
            if (item.isGamePackage) {
                item.dir.deleteRecursively()
            } else {
                item.entryFile.delete()
            }
        } catch (e: Exception) {
            false
        }
    }
}

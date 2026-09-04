package com.cookiegames.smartcookie.offline

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance, lightweight engine for saving web pages and recording
 * dynamic web games / web applications for 100% offline gameplay and browsing.
 */
object OfflineWebRecorder {

    private const val TAG = "OfflineWebRecorder"

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
        val title: String,
        val initialUrl: String,
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
        val ext = MimeTypeMap.getFileExtensionFromUrl(clean).lowercase()
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

    /**
     * Instantly saves the current web page as an MHTML snapshot.
     */
    fun savePageSnapshot(
        context: Context,
        webView: WebView?,
        title: String,
        callback: (File?) -> Unit
    ) {
        if (webView == null) {
            callback(null)
            return
        }
        val safeTitle = title.take(30).replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ_ -]"), "_").trim()
        val pagesDir = File(context.getExternalFilesDir(null), "saved_pages")
        if (!pagesDir.exists()) pagesDir.mkdirs()

        val fileName = "${if (safeTitle.isBlank()) "pagina" else safeTitle}_${System.currentTimeMillis()}.mht"
        val targetFile = File(pagesDir, fileName)

        try {
            webView.saveWebArchive(targetFile.absolutePath, false) { path ->
                if (path != null && File(path).exists() && File(path).length() > 0) {
                    callback(File(path))
                } else {
                    callback(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveWebArchive failed: ${e.message}")
            callback(null)
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
        val gamesDir = File(context.getExternalFilesDir(null), "saved_offline_webs")
        val sessionDir = File(gamesDir, id)
        if (!sessionDir.exists()) sessionDir.mkdirs()

        val initialMht = File(sessionDir, "index.mht")
        try {
            webView.saveWebArchive(initialMht.absolutePath, false) { path ->
                Log.d(TAG, "Initial archive saved to $path")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving initial MHTML for game: ${e.message}")
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
     * Stops the current offline recording session and seals the package.
     */
    fun stopRecording(context: Context): SavedWebItem? {
        val session = currentSession ?: return null
        isRecording = false

        saveManifest(session, isFinal = true)

        val entryFile = File(session.dir, "index.mht")
        val item = SavedWebItem(
            id = session.id,
            title = session.title,
            originalUrl = session.initialUrl,
            date = session.startTime,
            resourceCount = session.capturedCount.get(),
            entryFile = entryFile,
            dir = session.dir,
            isGamePackage = true
        )

        currentSession = null
        return item
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
                    conn.connectTimeout = 5000
                    conn.readTimeout = 8000
                    conn.instanceFollowRedirects = true

                    request.requestHeaders?.forEach { (k, v) ->
                        try { conn.setRequestProperty(k, v) } catch (_: Exception) {}
                    }

                    val code = conn.responseCode
                    if (code in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.isNotEmpty() && bytes.size < 40 * 1024 * 1024) { // Up to 40MB per asset
                            val cleanPath = urlStr.substringBefore('?').substringBefore('#')
                            var ext = MimeTypeMap.getFileExtensionFromUrl(cleanPath)
                            if (ext.isNullOrBlank()) {
                                ext = when (conn.contentType?.substringBefore(';')?.trim()) {
                                    "application/wasm" -> "wasm"
                                    "application/json" -> "json"
                                    "application/javascript" -> "js"
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
                            val encoding = conn.contentEncoding ?: "UTF-8"
                            val headers = mutableMapOf<String, String>()
                            conn.headerFields?.forEach { (k, list) ->
                                if (k != null && list.isNotEmpty()) {
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
     * Returns all saved offline web pages and game packages.
     */
    fun getSavedItems(context: Context): List<SavedWebItem> {
        val items = mutableListOf<SavedWebItem>()

        // 1. Single snapshots (.mht in saved_pages)
        val pagesDir = File(context.getExternalFilesDir(null), "saved_pages")
        if (pagesDir.exists()) {
            pagesDir.listFiles { _, name -> name.endsWith(".mht", ignoreCase = true) }?.forEach { f ->
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

        // 2. Full offline game/web packages in saved_offline_webs
        val gamesDir = File(context.getExternalFilesDir(null), "saved_offline_webs")
        if (gamesDir.exists()) {
            gamesDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
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

                val entryFile = File(dir, "index.mht")
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

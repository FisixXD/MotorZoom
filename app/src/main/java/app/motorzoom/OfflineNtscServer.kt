package app.motorzoom

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.FileNotFoundException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Serves the bundled official ntsc-rs web build only on the device loopback interface. */
object OfflineNtscServer {
    private const val ASSET_ROOT = "ntsc-web"
    private const val PORT = 18765
    private var server: AssetServer? = null

    @Synchronized
    fun url(context: Context): String {
        // Fail clearly if someone builds without running the download step.
        context.assets.open("$ASSET_ROOT/index.html").close()
        val active = server
        if (active == null || !active.wasStarted()) {
            server = AssetServer(context.applicationContext).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
        }
        return "http://127.0.0.1:$PORT/"
    }

    private class AssetServer(context: Context) : NanoHTTPD("127.0.0.1", PORT) {
        private val assets = context.assets

        override fun serve(session: IHTTPSession): Response {
            val decoded = URLDecoder.decode(session.uri.substringBefore('?'), StandardCharsets.UTF_8.name())
            var relative = decoded.trimStart('/')
            if (relative.isBlank()) relative = "index.html"
            if (relative.contains("..")) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
            }

            return try {
                val stream = assets.open("$ASSET_ROOT/$relative")
                newChunkedResponse(Response.Status.OK, mimeType(relative), stream).apply {
                    addHeader("Cache-Control", "public, max-age=31536000, immutable")
                    addHeader("Access-Control-Allow-Origin", "*")
                    addHeader("Cross-Origin-Opener-Policy", "same-origin")
                    addHeader("Cross-Origin-Embedder-Policy", "require-corp")
                    addHeader("Cross-Origin-Resource-Policy", "same-origin")
                }
            } catch (_: FileNotFoundException) {
                // Support client-side routes used by the web application.
                try {
                    newChunkedResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        assets.open("$ASSET_ROOT/index.html")
                    )
                } catch (_: Exception) {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
            }
        }

        private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "webmanifest" -> "application/json; charset=utf-8"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}

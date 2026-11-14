package app.tapvault

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import app.tapvault.core.Markets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Coin logos from CoinGecko ([Markets.isIconUrl]: its image hosts only,
 * never a URL a token's creator chose), kept in memory and on disk so each
 * is downloaded once. Anything else falls back to the letter mark.
 */
private object IconCache {
    val mem = android.util.LruCache<String, ImageBitmap>(200)
    val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private const val MAX_BYTES = 400_000
    private const val TARGET_PX = 144

    fun load(ctx: Context, url: String): ImageBitmap? {
        mem.get(url)?.let { return it }
        if (url in failed || !Markets.isIconUrl(url)) return null
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(File(ctx.cacheDir, "coin-icons").apply { mkdirs() }, name)
        val bytes = try {
            if (file.isFile) file.readBytes() else download(url)?.also { file.writeBytes(it) }
        } catch (e: Exception) { null } ?: run { failed += url; return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: run { failed += url; file.delete(); return null }
        return bmp.asImageBitmap().also { mem.put(url, it) }
    }

    private fun download(url: String): ByteArray? {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            if (c.responseCode != 200 || c.contentType?.startsWith("image/") != true) return null
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16_384)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }
}

/** A coin's logo in a circle, or its letter while loading or when it has none. */
@Composable
fun CoinIcon(url: String?, symbol: String, size: Dp) {
    if (url == null) { LetterMark(symbol, size); return }
    val ctx = LocalContext.current
    val bmp by produceState(IconCache.mem.get(url), url) {
        if (value == null) value = withContext(Dispatchers.IO) { IconCache.load(ctx.applicationContext, url) }
    }
    val b = bmp
    if (b == null) LetterMark(symbol, size)
    else Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape).background(Tv.GlassStrong))
}

package org.futo.inputmethod.latin.uix.actions.reactions

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

val KlipyApiKey = SettingsKey(stringPreferencesKey("klipy_api_key"), "")
private val KlipyCustomerId = SettingsKey(stringPreferencesKey("klipy_customer_id"), "")

@Serializable
data class KlipyMedia(val url: String, val width: Int = 0)

@Serializable
data class KlipySize(val png: KlipyMedia? = null, val webp: KlipyMedia? = null)

@Serializable
data class KlipyFiles(
    val hd: KlipySize? = null,
    val md: KlipySize? = null,
    val sm: KlipySize? = null,
    val xs: KlipySize? = null,
)

@Serializable
data class KlipyMeme(
    val slug: String,
    val title: String = "",
    val type: String = "",
    val file: KlipyFiles? = null,
) {
    val preview get() = file?.sm?.webp ?: file?.sm?.png ?: file?.md?.webp ?: file?.md?.png
    val full get() = listOfNotNull(file?.hd?.png, file?.md?.png, file?.sm?.png).maxByOrNull { it.width }
}

@Serializable
private data class KlipyPage(val data: List<KlipyMeme> = emptyList())

@Serializable
private data class KlipyResponse(val result: Boolean = false, val data: KlipyPage? = null)

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

object Klipy {
    private val thumbnails = LruCache<String, ImageBitmap>(96)

    private suspend fun customerId(context: Context): String {
        val existing = context.getSetting(KlipyCustomerId)
        if(existing.isNotEmpty()) return existing
        return UUID.randomUUID().toString().also { context.setSetting(KlipyCustomerId, it) }
    }

    private fun endpoint(key: String, path: String, params: Map<String, String>): URL {
        val builder = Uri.parse("https://api.klipy.com/api/v1").buildUpon()
            .appendPath(key)
            .appendEncodedPath(path)
        params.forEach { (k, v) -> builder.appendQueryParameter(k, v) }
        return URL(builder.build().toString())
    }

    private fun connect(url: URL, method: String = "GET"): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
        }

    suspend fun memes(context: Context, key: String, query: String): List<KlipyMeme> {
        val params = mapOf(
            "customer_id" to customerId(context),
            "per_page" to "30",
        ) + if(query.isBlank()) emptyMap() else mapOf("q" to query.trim())
        val path = if(query.isBlank()) "static-memes/trending" else "static-memes/search"

        return withContext(Dispatchers.IO) {
            try {
                val connection = connect(endpoint(key, path, params))
                try {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    json.decodeFromString<KlipyResponse>(body).data?.data.orEmpty()
                        .filter { it.type != "ad" && it.preview != null && it.full != null }
                } finally {
                    connection.disconnect()
                }
            } catch(e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun thumbnail(url: String): ImageBitmap? {
        thumbnails.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val connection = connect(URL(url))
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                } finally {
                    connection.disconnect()
                }
            } catch(e: Exception) {
                null
            }
        }?.also { thumbnails.put(url, it) }
    }

    suspend fun download(context: Context, meme: KlipyMeme): File? {
        val media = meme.full ?: return null
        val target = File(File(context.cacheDir, "klipy").also { it.mkdirs() }, "${meme.slug}.png")
        if(target.isFile) return target
        return withContext(Dispatchers.IO) {
            try {
                val temp = File(target.parentFile, "${meme.slug}.tmp")
                val connection = connect(URL(media.url))
                try {
                    connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                } finally {
                    connection.disconnect()
                }
                if(temp.renameTo(target)) target else null
            } catch(e: Exception) {
                null
            }
        }
    }

    suspend fun share(context: Context, key: String, meme: KlipyMeme, query: String) {
        val params = mapOf("customer_id" to customerId(context)) +
                if(query.isBlank()) emptyMap() else mapOf("q" to query.trim())
        withContext(Dispatchers.IO) {
            try {
                val connection = connect(endpoint(key, "static-memes/share/${Uri.encode(meme.slug)}", params), "POST")
                try {
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            } catch(_: Exception) { }
        }
    }
}

package com.elonn.androidxr

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val remoteImageHttp: OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

/**
 * A compact card's own remote preview image -- the realized form of an image-kind Resource a
 * `media` node in content.body named (dev.elonn canonical/object.md, Content body;
 * decision.thumbnail_is_a_media_node_not_a_canonical_field_20261024). A failed fetch leaves
 * nothing rendered; it must never throw out of the render path (a broken image URL is not a
 * reason to break the card it lives on) -- the same discipline unity.elonn.app's
 * LoadRemoteImageInto already applies.
 */
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier.fillMaxWidth().height(160.dp)) {
    val bitmapState = produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                remoteImageHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = modifier)
    }
}

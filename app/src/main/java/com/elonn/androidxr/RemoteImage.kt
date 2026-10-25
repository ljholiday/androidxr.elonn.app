package com.elonn.androidxr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
 *
 * One generic compact-card thumbnail policy (matches web.elonn.local's .object-preview and
 * unity.elonn.app's CreateRemoteImageBox, decision on unifying presentation across Runtimes,
 * 2026-10-25): aspect ratio always preserved, never cropped, never upscaled past the image's
 * own natural size, only shrunk if it exceeds the available width (read live from
 * BoxWithConstraints, the real parent width -- not a guessed constant, the same thing Web reads
 * from its container's actual rect and Unity reads from its wrapper's actual rect.width) or
 * [maxHeight] (the one per-call-site number, how much vertical room this context allows --
 * mirrors Web's differing .object-preview vs .world-sequence-entry__preview-image caps; not a
 * branch on Collection/Object/Service type).
 */
@Composable
fun RemoteImage(url: String, maxHeight: Dp, modifier: Modifier = Modifier.fillMaxWidth()) {
    val bitmapState = produceState<Bitmap?>(initialValue = null, url) {
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
    val bitmap = bitmapState.value ?: return
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val naturalWidth = with(density) { bitmap.width.toDp() }
        val naturalHeight = with(density) { bitmap.height.toDp() }
        val scale = minOf(1f, maxWidth / naturalWidth, maxHeight / naturalHeight)
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(naturalWidth * scale, naturalHeight * scale),
        )
    }
}

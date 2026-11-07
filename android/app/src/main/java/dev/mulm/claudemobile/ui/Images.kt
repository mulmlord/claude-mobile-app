package dev.mulm.claudemobile.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded images Claude read, by session/item, so scrolling back doesn't fetch them again. */
private object ImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, b: ImageBitmap) { cache.put(key, b) }
}

/** Decoded no wider than [maxWidth] (powers of two), enough to zoom in on a phone. */
private fun decode(bytes: ByteArray, maxWidth: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private sealed interface Img {
    data object Loading : Img
    data class Ready(val bitmap: ImageBitmap) : Img
    data class Failed(val message: String) : Img
}

/** The image a Read tool row looked at, as a picture; tap for full screen. */
@Composable
internal fun ToolImage(sid: String, item: Item) {
    val c = Claude.colors
    val key = "$sid/${item.id}"
    val state by produceState<Img>(ImageCache.get(key)?.let { Img.Ready(it) } ?: Img.Loading, key) {
        if (value is Img.Ready) return@produceState
        value = try {
            val bytes = App.instance.bridge.itemImage(sid, item.id)
            withContext(Dispatchers.Default) { decode(bytes, 1440) }?.let { ImageCache.put(key, it); Img.Ready(it) }
                ?: Img.Failed("Can't show this image")
        } catch (e: Exception) {
            Img.Failed(e.message ?: "Couldn't load the image")
        }
    }
    var full by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().background(c.soft), contentAlignment = Alignment.Center) {
        when (val st = state) {
            Img.Loading -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.8.dp, color = c.accent)
            }
            is Img.Ready -> Image(
                st.bitmap, contentDescription = "Image Claude looked at", contentScale = ContentScale.Fit,
                modifier = Modifier.padding(8.dp).heightIn(max = 300.dp)
                    .aspectRatio(st.bitmap.width.toFloat() / st.bitmap.height.coerceAtLeast(1))
                    .clickable { full = true },
            )
            is Img.Failed -> Text(st.message, fontSize = 13.sp, color = c.faint, modifier = Modifier.padding(12.dp))
        }
    }
    (state as? Img.Ready)?.let { if (full) ImageViewer(it.bitmap) { full = false } }
}

/** Full screen on black: pinch to zoom, drag to pan, tap to close. */
@Composable
private fun ImageViewer(bitmap: ImageBitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val zoom = rememberTransformableState { z, pan, _ ->
            scale = (scale * z).coerceIn(1f, 6f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose).transformable(zoom),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap, contentDescription = "Image Claude looked at", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

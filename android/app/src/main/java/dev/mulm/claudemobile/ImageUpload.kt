package dev.mulm.claudemobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val MAX_SIDE = 2000

/**
 * Picked photo -> JPEG of at most 2000 px on the long side, upright (ImageDecoder applies
 * EXIF rotation). Keeps uploads small over a phone connection; Claude doesn't need more.
 */
suspend fun prepareImage(ctx: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val cr = ctx.contentResolver
    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val m = maxOf(w, h)
            if (m > MAX_SIDE) dec.setTargetSize(w * MAX_SIDE / m, h * MAX_SIDE / m)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Couldn't read the image")
    }
    ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
}

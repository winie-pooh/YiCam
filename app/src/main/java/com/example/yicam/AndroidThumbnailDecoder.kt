/**
 * Responsible for: decoding thumbnail JPEG bytes from EXIF (first 64 KB of a photo), downscaling a full JPEG, and extracting a video frame at ~1 s via MediaMetadataRetriever over HTTP.
 * Not responsible for: fetching the bytes (HttpThumbnailFetcher.kt), caching or concurrency (ThumbnailLoader.kt).
 */
package com.example.yicam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AndroidThumbnailDecoder : ThumbnailDecoder {
    override fun exifThumbnail(jpegPrefix: ByteArray): ByteArray? {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(jpegPrefix))
            val thumb = exif.thumbnail
            if (thumb != null && thumb.isNotEmpty()) thumb else null
        } catch (_: Exception) {
            null
        }
    }

    override fun downscaleJpeg(fullJpeg: ByteArray, maxEdge: Int): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(fullJpeg, 0, fullJpeg.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longer = maxOf(bounds.outWidth, bounds.outHeight)
            while (longer / sample > maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(fullJpeg, 0, fullJpeg.size, opts) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun videoFrameAt(url: String, timeMs: Long): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, HashMap())
            val bmp = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}

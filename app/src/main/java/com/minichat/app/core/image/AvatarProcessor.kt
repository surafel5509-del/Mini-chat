package com.minichat.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns whatever the photo picker returns into a small, square JPEG.
 *
 * ## Why this exists
 *
 * Avatar uploads were failing for three separate reasons, all of them here:
 *
 *  1. **Size.** A modern phone camera produces 4–12 MB files, but the `avatars`
 *     bucket accepts 5 MB. The upload was rejected by Storage with an opaque
 *     error and the UI showed nothing.
 *  2. **Format.** `ActivityResultContracts.GetContent` happily returns HEIC on
 *     Pixel and Samsung devices. HEIC is not in the bucket's allowed MIME list,
 *     so those uploads failed too.
 *  3. **Orientation.** Camera photos carry an EXIF rotation flag that a plain
 *     re-encode discards, so portraits arrived sideways.
 *
 * Normalising everything to JPEG fixes all three at once and also keeps the
 * download small for everyone who renders it in a chat list.
 */
@Singleton
class AvatarProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Processed(
        val bytes: ByteArray,
        val extension: String,
    )

    /**
     * Decodes [uri], rotates per EXIF, centre-crops to a square and re-encodes as
     * JPEG at [TARGET_PX]. Returns null when the image cannot be read.
     */
    fun process(uri: Uri, targetPx: Int = TARGET_PX): Processed? {
        val bitmap = decodeSampled(uri, targetPx) ?: return null

        return try {
            val oriented = applyExifRotation(uri, bitmap)
            val square = centreCropToSquare(oriented)
            val scaled = if (square.width > targetPx) {
                Bitmap.createScaledBitmap(square, targetPx, targetPx, true)
            } else {
                square
            }

            val out = ByteArrayOutputStream()
            // 88 keeps a 512px avatar well under 200 KB while staying crisp.
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                return null
            }

            // Only recycle what we actually created; the caller may still hold the
            // original reference otherwise.
            listOf(oriented, square, scaled)
                .distinct()
                .forEach { if (it !== bitmap) runCatching { it.recycle() } }
            runCatching { bitmap.recycle() }

            Processed(bytes = out.toByteArray(), extension = "jpg")
        } catch (error: Exception) {
            Log.w(TAG, "Could not process avatar", error)
            runCatching { bitmap.recycle() }
            null
        } catch (oom: OutOfMemoryError) {
            // A very large image can still blow the heap even when sampled; failing
            // gracefully beats crashing the app mid-edit.
            Log.w(TAG, "Avatar too large to decode", oom)
            null
        }
    }

    /**
     * Reads the dimensions first so a 50 MP photo is decoded at 1/8 scale rather
     * than allocating a 200 MB bitmap that would OOM on most devices.
     */
    private fun decodeSampled(uri: Uri, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val shortest = minOf(bounds.outWidth, bounds.outHeight)
        while (shortest / sample > targetPx * 2) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }

        return runCatching {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(degrees) },
                true,
            )
        }.getOrDefault(bitmap)
    }

    private fun centreCropToSquare(bitmap: Bitmap): Bitmap {
        if (bitmap.width == bitmap.height) return bitmap
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return runCatching {
            Bitmap.createBitmap(bitmap, left, top, side, side)
        }.getOrDefault(bitmap)
    }

    private companion object {
        const val TAG = "AvatarProcessor"

        /** Rendered at up to ~200 dp; 512 px covers xhdpi comfortably. */
        const val TARGET_PX = 512
        const val QUALITY = 88
    }
}

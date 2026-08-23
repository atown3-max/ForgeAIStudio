package com.forgeai.studio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object MediaUtils {
    suspend fun uriToJpegDataUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val maxDim = maxOf(w, h)
            if (maxDim > 1280) {
                val scale = 1280f / maxDim.toFloat()
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        bitmapToDataUri(bitmap)
    }

    suspend fun fileToJpegDataUri(file: File): String = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val maxDim = maxOf(w, h)
            if (maxDim > 1280) {
                val scale = 1280f / maxDim.toFloat()
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        bitmapToDataUri(bitmap)
    }

    private fun bitmapToDataUri(bitmap: Bitmap): String {
        var quality = 88
        var bytes: ByteArray
        while (true) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bytes = output.toByteArray()
            if (bytes.size <= 180_000 || quality <= 40) break
            quality = (quality - 6).coerceAtLeast(40)
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    suspend fun extractLastFrame(context: Context, videoFile: File): File = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val targetUs = ((durationMs - 120L).coerceAtLeast(0L)) * 1000L
            val frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Could not extract the final video frame")
            val dir = File(context.filesDir, "continuation_frames").apply { mkdirs() }
            val file = File(dir, "continue_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { output ->
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 92, output)) error("Could not save continuation frame")
            }
            frame.recycle()
            file
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun downloadToInternal(context: Context, url: String, kind: CreationKind): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "creations").apply { mkdirs() }
        val ext = if (kind == CreationKind.IMAGE) ".png" else ".mp4"
        val file = File(dir, "${System.currentTimeMillis()}_${UUID.randomUUID()}$ext")
        val response = OkHttpClient().newCall(Request.Builder().url(url).get().build()).execute()
        if (!response.isSuccessful) {
            response.close()
            error("Could not download generated file (${response.code})")
        }
        response.body?.byteStream()?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: error("Empty generated file")
        response.close()
        file
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    suspend fun saveToGallery(context: Context, file: File, kind: CreationKind): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val isImage = kind == CreationKind.IMAGE
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (isImage) "image/png" else "video/mp4")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/Forge AI Studio"
            )
        }
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: error("Could not create gallery item")
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: error("Could not write gallery item")
        uri
    }
}

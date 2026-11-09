package com.forgeai.studio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Serverless has a 10 MB JSON limit. Trim and encode the selected section before uploading it. */
@androidx.annotation.OptIn(UnstableApi::class)
suspend fun prepareRunpodRecast(context: Context, job: RecastJob) {
    val folder = File(job.video).parentFile!!
    val output = File(folder, "runpod-input.mp4")
    val dimensions = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(job.video)
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) { val swap = width; width = height; height = swap }
            require(width > 0 && height > 0) { "Could not read this video's dimensions." }
            val scale = minOf((if (width >= height) 832.0 else 480.0) / width,
                (if (width >= height) 480.0 else 832.0) / height, 1.0)
            Pair(((width * scale / 16).roundToInt() * 16).coerceAtLeast(16),
                ((height * scale / 16).roundToInt() * 16).coerceAtLeast(16))
        } finally { retriever.release() }
    }
    withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val media = MediaItem.Builder().setUri(Uri.fromFile(File(job.video)))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((job.start * 1000).toLong())
                    .setEndPositionMs(((job.start + job.duration) * 1000).toLong()).build()).build()
            val edited = EditedMediaItem.Builder(media).setRemoveAudio(!job.keepAudio)
                .setEffects(Effects(emptyList(), listOf(
                    Presentation.createForWidthAndHeight(dimensions.first, dimensions.second, Presentation.LAYOUT_SCALE_TO_FIT),
                    FrameDropEffect.createDefaultFrameDropEffect(16f)
                ))).build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(2_000_000).build()).build())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("Could not prepare this video on the phone. Try an SDR MP4 clip.", exportException))
                    }
                }).build()
            continuation.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) transformer.cancel()
                else Handler(Looper.getMainLooper()).post { transformer.cancel() }
            }
            transformer.start(edited, output.absolutePath)
        }
    }
    withContext(Dispatchers.IO) {
        require(output.isFile && output.length() > 0) { "Video preparation produced no file." }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            require(kotlin.math.abs(durationMs - job.duration * 1000L) <= 250L) {
                "The phone could not prepare the requested clip length. Try a different source video."
            }
        } finally { retriever.release() }
        File(folder, "runpod-media.json").writeText(JSONObject().put("width", dimensions.first)
            .put("height", dimensions.second).toString())
        RunpodRecastClient.payload(job) // Reject oversized inputs before any paid request is made.
    }
}

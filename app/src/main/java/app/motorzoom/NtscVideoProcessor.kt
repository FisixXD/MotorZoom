package app.motorzoom

import android.content.ContentResolver
import android.content.ContentValues
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Offline 480p pipeline: MediaCodec -> official ntsc-rs core -> MediaCodec. */
class NtscVideoProcessor(private val resolver: ContentResolver) {
    companion object {
        private const val WIDTH = 640
        private const val HEIGHT = 480
        private const val TIMEOUT_US = 10_000L
    }

    fun process(input: Uri, preset: String, progress: (Int) -> Unit): Uri {
        check(NativeNtsc.configure(preset)) { "Preset incompatível com esta versão do NTSC-RS" }

        val extractor = MediaExtractor()
        resolver.openFileDescriptor(input, "r")!!.use { extractor.setDataSource(it.fileDescriptor) }
        val videoTrack = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("O arquivo não contém vídeo")
        val sourceFormat = extractor.getTrackFormat(videoTrack)
        val audioTrack = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        val durationUs = sourceFormat.getLong(MediaFormat.KEY_DURATION)
        val rotation = sourceFormat.getInteger(MediaFormat.KEY_ROTATION, 0)
        val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)!!
        extractor.selectTrack(videoTrack)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MotorZoom_NTSC_${System.currentTimeMillis()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotorZoom")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val output = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Não foi possível criar o vídeo de saída")

        try {
            resolver.openFileDescriptor(output, "rw")!!.use { descriptor ->
                val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                if (rotation != 0) muxer.setOrientationHint(rotation)
                transcode(extractor, videoTrack, audioTrack, sourceMime, sourceFormat, muxer, durationUs, progress)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(output, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            return output
        } catch (error: Throwable) {
            resolver.delete(output, null, null)
            throw error
        } finally {
            extractor.release()
        }
    }

    private fun transcode(
        extractor: MediaExtractor,
        videoSourceTrack: Int,
        audioSourceTrack: Int?,
        sourceMime: String,
        sourceFormat: MediaFormat,
        muxer: MediaMuxer,
        durationUs: Long,
        progress: (Int) -> Unit
    ) {
        val decoder = MediaCodec.createDecoderByType(sourceMime)
        val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE, 30))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        decoder.configure(sourceFormat, null, null, 0)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        decoder.start()
        encoder.start()

        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        var frame = 0
        var muxerStarted = false
        var videoMuxTrack = -1
        var audioMuxTrack = -1

        try {
            while (!encoderDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val index = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)
                    if (index >= 0) {
                        if (decoderInfo.size > 0) {
                            val image = decoder.getOutputImage(index)
                                ?: error("O decodificador do aparelho não forneceu quadros YUV")
                            val rgba = imageToRgba(image, WIDTH, HEIGHT)
                            image.close()
                            check(NativeNtsc.processRgba(rgba, WIDTH, HEIGHT, frame++)) {
                                "Falha no núcleo NTSC-RS"
                            }
                            queueEncoder(encoder, rgba, decoderInfo.presentationTimeUs)
                            progress(((decoderInfo.presentationTimeUs * 100L) / durationUs.coerceAtLeast(1)).toInt().coerceIn(0, 99))
                        }
                        decoderDone = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                        if (decoderDone) queueEncoderEnd(encoder, decoderInfo.presentationTimeUs)
                    }
                }

                var drain = true
                while (drain) {
                    when (val index = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> drain = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted)
                            videoMuxTrack = muxer.addTrack(encoder.outputFormat)
                            if (audioSourceTrack != null) {
                                audioMuxTrack = muxer.addTrack(extractor.getTrackFormat(audioSourceTrack))
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (index >= 0) {
                            val data = encoder.getOutputBuffer(index)!!
                            if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encoderInfo.size > 0) {
                                check(muxerStarted)
                                data.position(encoderInfo.offset)
                                data.limit(encoderInfo.offset + encoderInfo.size)
                                muxer.writeSampleData(videoMuxTrack, data, encoderInfo)
                            }
                            encoderDone = encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            if (audioSourceTrack != null && audioMuxTrack >= 0) {
                copyAudio(extractor, videoSourceTrack, audioSourceTrack, muxer, audioMuxTrack)
            }
            progress(100)
        } finally {
            decoder.stop(); decoder.release()
            encoder.stop(); encoder.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }

    private fun copyAudio(
        extractor: MediaExtractor,
        videoTrack: Int,
        audioTrack: Int,
        muxer: MediaMuxer,
        muxTrack: Int
    ) {
        extractor.unselectTrack(videoTrack)
        extractor.selectTrack(audioTrack)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            muxer.writeSampleData(muxTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun queueEncoder(codec: MediaCodec, rgba: ByteArray, pts: Long) {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val output = codec.getInputBuffer(index)!!
                output.clear()
                rgbaToI420(rgba, output, WIDTH, HEIGHT)
                codec.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2, pts, 0)
                return
            }
        }
    }

    private fun queueEncoderEnd(codec: MediaCodec, pts: Long) {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
    }

    private fun imageToRgba(image: Image, outWidth: Int, outHeight: Int): ByteArray {
        val crop = image.cropRect
        val planes = image.planes
        val output = ByteArray(outWidth * outHeight * 4)
        for (dy in 0 until outHeight) {
            val sy = crop.top + (dy * crop.height() / outHeight)
            for (dx in 0 until outWidth) {
                val sx = crop.left + (dx * crop.width() / outWidth)
                val y = sample(planes[0], sx, sy)
                val u = sample(planes[1], sx / 2, sy / 2) - 128
                val v = sample(planes[2], sx / 2, sy / 2) - 128
                val r = (y + 1.402f * v).roundToInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).roundToInt().coerceIn(0, 255)
                val at = (dy * outWidth + dx) * 4
                output[at] = r.toByte(); output[at + 1] = g.toByte()
                output[at + 2] = b.toByte(); output[at + 3] = 0xff.toByte()
            }
        }
        return output
    }

    private fun sample(plane: Image.Plane, x: Int, y: Int): Int {
        val index = y * plane.rowStride + x * plane.pixelStride
        return plane.buffer.get(index).toInt() and 0xff
    }

    private fun rgbaToI420(rgba: ByteArray, output: ByteBuffer, width: Int, height: Int) {
        val frameSize = width * height
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            val r = rgba[i].toInt() and 255
            val g = rgba[i + 1].toInt() and 255
            val b = rgba[i + 2].toInt() and 255
            output.put(y * width + x, ((77 * r + 150 * g + 29 * b) shr 8).coerceIn(0, 255).toByte())
        }
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            var r = 0; var g = 0; var b = 0
            for (yy in 0..1) for (xx in 0..1) {
                val i = ((y + yy) * width + x + xx) * 4
                r += rgba[i].toInt() and 255; g += rgba[i + 1].toInt() and 255; b += rgba[i + 2].toInt() and 255
            }
            r /= 4; g /= 4; b /= 4
            val chroma = (y / 2) * (width / 2) + x / 2
            output.put(frameSize + chroma, (((-43 * r - 85 * g + 128 * b) shr 8) + 128).coerceIn(0, 255).toByte())
            output.put(frameSize + frameSize / 4 + chroma, (((128 * r - 107 * g - 21 * b) shr 8) + 128).coerceIn(0, 255).toByte())
        }
    }
}

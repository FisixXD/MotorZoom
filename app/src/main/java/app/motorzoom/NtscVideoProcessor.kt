package app.motorzoom

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
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
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Offline 480p pipeline: MediaCodec -> official ntsc-rs core -> MediaCodec. */
class NtscVideoProcessor(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    data class MotorZoomSettings(
        val enabled: Boolean = false,
        val startUs: Long = 0L,
        val durationUs: Long = 1L,
        val startZoom: Float = 1f,
        val endZoom: Float = 1f
    )

    data class VisualSettings(
        val colorEnabled: Boolean = false,
        val temperature: Float = 0f,
        val saturation: Float = 1f,
        val contrast: Float = 1f,
        val brightness: Float = 0f,
        val tint: Float = 0f,
        val fishEyeEnabled: Boolean = false,
        val fishEyeStrength: Float = 0.35f,
        val overlayEnabled: Boolean = false,
        val overlayStartEpochMs: Long = 0L
    )

    companion object {
        private const val WIDTH = 640
        private const val HEIGHT = 480
        private const val INTERLACED_WIDTH = 720
        private const val INTERLACED_HEIGHT = 480
        private const val TIMEOUT_US = 10_000L
    }

    fun process(
        input: Uri,
        preset: String,
        motorZoom: MotorZoomSettings,
        visual: VisualSettings,
        trueInterlaced: Boolean,
        progress: (Int) -> Unit
    ): Uri {
        check(NativeNtsc.configure(preset)) { "Preset incompatível com esta versão do NTSC-RS" }

        if (trueInterlaced) {
            return processTrueInterlaced(input, motorZoom, visual, progress)
        }

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
                transcode(
                    extractor, videoTrack, audioTrack, sourceMime, sourceFormat,
                    muxer, durationUs, motorZoom, visual, progress
                )
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

    /**
     * Creates a standards-shaped NTSC file: 720x480 MPEG-2, 29.97 coded frames and
     * 59.94 temporal fields per second. Two consecutive 60p source frames are woven
     * into one top-field-first frame, so the fields contain different moments.
     */
    private fun processTrueInterlaced(
        input: Uri,
        motorZoom: MotorZoomSettings,
        visual: VisualSettings,
        progress: (Int) -> Unit
    ): Uri {
        val extractor = MediaExtractor()
        resolver.openFileDescriptor(input, "r")!!.use { extractor.setDataSource(it.fileDescriptor) }
        val videoTrack = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("O arquivo não contém vídeo")
        val sourceFormat = extractor.getTrackFormat(videoTrack)
        val sourceFps = sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE, 0)
        check(sourceFps >= 50) {
            "480i real precisa de vídeo 59,94/60 fps; este arquivo anuncia $sourceFps fps"
        }
        val durationUs = sourceFormat.getLong(MediaFormat.KEY_DURATION)
        val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)!!
        extractor.selectTrack(videoTrack)

        val sourceCopy = File.createTempFile("motorzoom_source_", ".mp4", context.cacheDir)
        val encoded = File.createTempFile("motorzoom_480i_", ".mpg", context.cacheDir)
        try {
            resolver.openInputStream(input)!!.use { source ->
                sourceCopy.outputStream().use { target -> source.copyTo(target) }
            }
            val ffmpeg = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            check(ffmpeg.canExecute()) { "Exportador 480i não foi incluído nesta compilação" }
            val command = listOf(
                ffmpeg.absolutePath,
                "-hide_banner", "-loglevel", "warning", "-y",
                "-thread_queue_size", "64",
                "-f", "rawvideo", "-pixel_format", "rgba",
                "-video_size", "${INTERLACED_WIDTH}x${INTERLACED_HEIGHT}",
                "-framerate", "30000/1001", "-i", "pipe:0",
                "-i", sourceCopy.absolutePath,
                "-map", "0:v:0", "-map", "1:a:0?",
                "-c:v", "mpeg2video", "-pix_fmt", "yuv420p",
                "-flags", "+ildct+ilme", "-top", "1",
                "-aspect", "4:3", "-r", "30000/1001",
                "-b:v", "8000k", "-maxrate", "9000k", "-bufsize", "1835008",
                "-g", "15",
                "-c:a", "mp2", "-b:a", "192k", "-ar", "48000",
                "-f", "mpeg", encoded.absolutePath
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val log = StringBuilder()
            val logReader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (log.length < 8_000) log.appendLine(line)
                    }
                }
            }.apply { start() }

            var pipelineError: Throwable? = null
            try {
                process.outputStream.buffered(1 shl 20).use { pipe ->
                    decodeAndWeave(
                        extractor, sourceMime, sourceFormat, durationUs,
                        motorZoom, visual, pipe, progress
                    )
                }
            } catch (error: Throwable) {
                pipelineError = error
            }
            val exit = process.waitFor()
            logReader.join()
            if (exit != 0 || encoded.length() == 0L) {
                val detail = log.toString().takeLast(1600).ifBlank {
                    pipelineError?.message ?: "sem detalhes"
                }
                error("FFmpeg 480i encerrou com código $exit: $detail")
            }
            if (pipelineError != null) throw pipelineError

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "MotorZoom_NTSC_480i_${System.currentTimeMillis()}.mpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MotorZoom")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val output = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Não foi possível criar o vídeo 480i")
            try {
                resolver.openOutputStream(output, "w")!!.use { destination ->
                    encoded.inputStream().use { source -> source.copyTo(destination) }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.update(output, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
                progress(100)
                return output
            } catch (error: Throwable) {
                resolver.delete(output, null, null)
                throw error
            }
        } finally {
            extractor.release()
            sourceCopy.delete()
            encoded.delete()
        }
    }

    private fun decodeAndWeave(
        extractor: MediaExtractor,
        sourceMime: String,
        sourceFormat: MediaFormat,
        durationUs: Long,
        motorZoom: MotorZoomSettings,
        visual: VisualSettings,
        pipe: OutputStream,
        progress: (Int) -> Unit
    ) {
        val decoder = MediaCodec.createDecoderByType(sourceMime)
        decoder.configure(sourceFormat, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var frameNumber = 0
        var firstField: ByteArray? = null
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val image = decoder.getOutputImage(outputIndex)
                            ?: error("O decodificador do aparelho não forneceu quadros YUV")
                        val rgba = imageToRgba(
                            image, INTERLACED_WIDTH, INTERLACED_HEIGHT,
                            zoomAt(info.presentationTimeUs, motorZoom),
                            if (visual.fishEyeEnabled) visual.fishEyeStrength else 0f
                        )
                        image.close()
                        applyVisualEffects(
                            rgba, INTERLACED_WIDTH, INTERLACED_HEIGHT,
                            info.presentationTimeUs, visual
                        )
                        check(NativeNtsc.processRgba(
                            rgba, INTERLACED_WIDTH, INTERLACED_HEIGHT, frameNumber++
                        )) { "Falha no núcleo NTSC-RS" }
                        val earlier = firstField
                        if (earlier == null) {
                            firstField = rgba
                        } else {
                            pipe.write(weaveTopFieldFirst(earlier, rgba))
                            firstField = null
                        }
                        progress(((info.presentationTimeUs * 99L) /
                            durationUs.coerceAtLeast(1)).toInt().coerceIn(0, 99))
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
        }
    }

    private fun weaveTopFieldFirst(earlier: ByteArray, later: ByteArray): ByteArray {
        val rowBytes = INTERLACED_WIDTH * 4
        val output = ByteArray(INTERLACED_WIDTH * INTERLACED_HEIGHT * 4)
        for (row in 0 until INTERLACED_HEIGHT) {
            val source = if (row and 1 == 0) earlier else later
            val offset = row * rowBytes
            source.copyInto(output, offset, offset, offset + rowBytes)
        }
        return output
    }

    private fun transcode(
        extractor: MediaExtractor,
        videoSourceTrack: Int,
        audioSourceTrack: Int?,
        sourceMime: String,
        sourceFormat: MediaFormat,
        muxer: MediaMuxer,
        durationUs: Long,
        motorZoom: MotorZoomSettings,
        visual: VisualSettings,
        progress: (Int) -> Unit
    ) {
        val decoder = MediaCodec.createDecoderByType(sourceMime)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val supportedColors = encoder.codecInfo
            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats
        val encoderColor = when {
            supportedColors.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            supportedColors.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
        val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderColor)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE, 30))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
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
                            val zoom = zoomAt(decoderInfo.presentationTimeUs, motorZoom)
                            val rgba = imageToRgba(
                                image, WIDTH, HEIGHT, zoom,
                                if (visual.fishEyeEnabled) visual.fishEyeStrength else 0f
                            )
                            image.close()
                            applyVisualEffects(
                                rgba, WIDTH, HEIGHT, decoderInfo.presentationTimeUs, visual
                            )
                            check(NativeNtsc.processRgba(rgba, WIDTH, HEIGHT, frame++)) {
                                "Falha no núcleo NTSC-RS"
                            }
                            queueEncoder(encoder, rgba, decoderInfo.presentationTimeUs, encoderColor)
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

    private fun queueEncoder(codec: MediaCodec, rgba: ByteArray, pts: Long, colorFormat: Int) {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val output = codec.getInputBuffer(index)!!
                output.clear()
                rgbaToYuv420(rgba, output, WIDTH, HEIGHT, colorFormat)
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

    /** Trapezoidal motor curve: 120 ms acceleration, constant speed, 120 ms braking. */
    private fun zoomAt(timeUs: Long, settings: MotorZoomSettings): Float {
        if (!settings.enabled) return 1f
        val raw = ((timeUs - settings.startUs).toDouble() / settings.durationUs.coerceAtLeast(1))
            .coerceIn(0.0, 1.0)
        val ramp = min(0.25, 120_000.0 / settings.durationUs.coerceAtLeast(1))
        val position = when {
            ramp <= 0.0 -> raw
            raw < ramp -> raw * raw / (2.0 * ramp * (1.0 - ramp))
            raw > 1.0 - ramp -> {
                1.0 - (1.0 - raw) * (1.0 - raw) / (2.0 * ramp * (1.0 - ramp))
            }
            else -> (raw - ramp / 2.0) / (1.0 - ramp)
        }
        return (settings.startZoom + (settings.endZoom - settings.startZoom) * position.toFloat())
            .coerceIn(1f, 4f)
    }

    private fun imageToRgba(
        image: Image,
        outWidth: Int,
        outHeight: Int,
        zoom: Float,
        fishEyeStrength: Float
    ): ByteArray {
        val crop = image.cropRect
        val planes = image.planes
        val output = ByteArray(outWidth * outHeight * 4)
        val targetRatio = outWidth.toFloat() / outHeight
        val sourceRatio = crop.width().toFloat() / crop.height()
        val baseWidth = if (sourceRatio > targetRatio) {
            (crop.height() * targetRatio).toInt()
        } else crop.width()
        val baseHeight = if (sourceRatio < targetRatio) {
            (crop.width() / targetRatio).toInt()
        } else crop.height()
        val baseLeft = crop.left + (crop.width() - baseWidth) / 2
        val baseTop = crop.top + (crop.height() - baseHeight) / 2
        val sampleWidth = (baseWidth / zoom).roundToInt().coerceAtLeast(2)
        val sampleHeight = (baseHeight / zoom).roundToInt().coerceAtLeast(2)
        val sampleLeft = baseLeft + (baseWidth - sampleWidth) / 2
        val sampleTop = baseTop + (baseHeight - sampleHeight) / 2
        for (dy in 0 until outHeight) {
            for (dx in 0 until outWidth) {
                val normalizedX = ((dx + 0.5f) / outWidth) * 2f - 1f
                val normalizedY = ((dy + 0.5f) / outHeight) * 2f - 1f
                val radiusSquared = normalizedX * normalizedX + normalizedY * normalizedY
                val radial = if (fishEyeStrength > 0f) {
                    (1f + fishEyeStrength * radiusSquared) / (1f + fishEyeStrength)
                } else 1f
                val sourceX = normalizedX * radial
                val sourceY = normalizedY * radial
                val at = (dy * outWidth + dx) * 4
                if (sourceX !in -1f..1f || sourceY !in -1f..1f) {
                    output[at] = 0
                    output[at + 1] = 0
                    output[at + 2] = 0
                    output[at + 3] = 0xff.toByte()
                    continue
                }
                val sx = (sampleLeft + (sourceX + 1f) * 0.5f * (sampleWidth - 1))
                    .roundToInt().coerceIn(sampleLeft, sampleLeft + sampleWidth - 1)
                val sy = (sampleTop + (sourceY + 1f) * 0.5f * (sampleHeight - 1))
                    .roundToInt().coerceIn(sampleTop, sampleTop + sampleHeight - 1)
                val y = sample(planes[0], sx, sy)
                val u = sample(planes[1], sx / 2, sy / 2) - 128
                val v = sample(planes[2], sx / 2, sy / 2) - 128
                val r = (y + 1.402f * v).roundToInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).roundToInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).roundToInt().coerceIn(0, 255)
                output[at] = r.toByte(); output[at + 1] = g.toByte()
                output[at + 2] = b.toByte(); output[at + 3] = 0xff.toByte()
            }
        }
        return output
    }

    private fun applyVisualEffects(
        rgba: ByteArray,
        width: Int,
        height: Int,
        timeUs: Long,
        settings: VisualSettings
    ) {
        if (settings.colorEnabled) applyColorCorrection(rgba, settings)
        if (settings.overlayEnabled) {
            val instant = settings.overlayStartEpochMs + timeUs / 1_000L
            val text = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.US).format(Date(instant))
            drawTimestamp(rgba, width, height, text)
        }
    }

    private fun applyColorCorrection(rgba: ByteArray, settings: VisualSettings) {
        val temperature = settings.temperature.coerceIn(-1f, 1f)
        val tint = settings.tint.coerceIn(-1f, 1f)
        val saturation = settings.saturation.coerceIn(0f, 2f)
        val contrast = settings.contrast.coerceIn(0.5f, 1.5f)
        val brightness = settings.brightness.coerceIn(-0.5f, 0.5f) * 255f
        var index = 0
        while (index < rgba.size) {
            var red = (rgba[index].toInt() and 255).toFloat()
            var green = (rgba[index + 1].toInt() and 255).toFloat()
            var blue = (rgba[index + 2].toInt() and 255).toFloat()

            red += temperature * 28f - tint * 7f
            green += tint * 18f
            blue -= temperature * 28f + tint * 7f
            val luma = 0.299f * red + 0.587f * green + 0.114f * blue
            red = luma + (red - luma) * saturation
            green = luma + (green - luma) * saturation
            blue = luma + (blue - luma) * saturation
            red = (red - 127.5f) * contrast + 127.5f + brightness
            green = (green - 127.5f) * contrast + 127.5f + brightness
            blue = (blue - 127.5f) * contrast + 127.5f + brightness

            rgba[index] = red.roundToInt().coerceIn(0, 255).toByte()
            rgba[index + 1] = green.roundToInt().coerceIn(0, 255).toByte()
            rgba[index + 2] = blue.roundToInt().coerceIn(0, 255).toByte()
            index += 4
        }
    }

    private fun drawTimestamp(
        rgba: ByteArray,
        width: Int,
        height: Int,
        text: String
    ) {
        val scale = if (width >= 700) 3 else 2
        val characterWidth = 6 * scale
        val textWidth = text.length * characterWidth
        val startX = (width - textWidth - 18).coerceAtLeast(4)
        val startY = (height - 7 * scale - 18).coerceAtLeast(4)
        drawPixelText(rgba, width, height, text, startX + 2, startY + 2, scale, 0, 0, 0)
        drawPixelText(rgba, width, height, text, startX, startY, scale, 245, 238, 205)
    }

    private fun drawPixelText(
        rgba: ByteArray,
        width: Int,
        height: Int,
        text: String,
        startX: Int,
        startY: Int,
        scale: Int,
        red: Int,
        green: Int,
        blue: Int
    ) {
        text.forEachIndexed { characterIndex, character ->
            val rows = glyph(character)
            for (row in rows.indices) {
                for (column in 0 until 5) {
                    if (rows[row] and (1 shl (4 - column)) == 0) continue
                    val left = startX + characterIndex * 6 * scale + column * scale
                    val top = startY + row * scale
                    for (py in top until top + scale) for (px in left until left + scale) {
                        if (px !in 0 until width || py !in 0 until height) continue
                        val at = (py * width + px) * 4
                        rgba[at] = red.toByte()
                        rgba[at + 1] = green.toByte()
                        rgba[at + 2] = blue.toByte()
                        rgba[at + 3] = 0xff.toByte()
                    }
                }
            }
        }
    }

    private fun glyph(character: Char): IntArray = when (character) {
        '0' -> intArrayOf(14, 17, 19, 21, 25, 17, 14)
        '1' -> intArrayOf(4, 12, 4, 4, 4, 4, 14)
        '2' -> intArrayOf(14, 17, 1, 2, 4, 8, 31)
        '3' -> intArrayOf(30, 1, 1, 14, 1, 1, 30)
        '4' -> intArrayOf(2, 6, 10, 18, 31, 2, 2)
        '5' -> intArrayOf(31, 16, 16, 30, 1, 1, 30)
        '6' -> intArrayOf(14, 16, 16, 30, 17, 17, 14)
        '7' -> intArrayOf(31, 1, 2, 4, 8, 8, 8)
        '8' -> intArrayOf(14, 17, 17, 14, 17, 17, 14)
        '9' -> intArrayOf(14, 17, 17, 15, 1, 1, 14)
        '/' -> intArrayOf(1, 2, 2, 4, 8, 8, 16)
        ':' -> intArrayOf(0, 4, 4, 0, 4, 4, 0)
        '-' -> intArrayOf(0, 0, 0, 31, 0, 0, 0)
        else -> intArrayOf(0, 0, 0, 0, 0, 0, 0)
    }

    private fun sample(plane: Image.Plane, x: Int, y: Int): Int {
        val index = y * plane.rowStride + x * plane.pixelStride
        return plane.buffer.get(index).toInt() and 0xff
    }

    private fun rgbaToYuv420(
        rgba: ByteArray,
        output: ByteBuffer,
        width: Int,
        height: Int,
        colorFormat: Int
    ) {
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
            val u = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).coerceIn(0, 255).toByte()
            val v = (((128 * r - 107 * g - 21 * b) shr 8) + 128).coerceIn(0, 255).toByte()
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                output.put(frameSize + chroma * 2, u)
                output.put(frameSize + chroma * 2 + 1, v)
            } else {
                output.put(frameSize + chroma, u)
                output.put(frameSize + frameSize / 4 + chroma, v)
            }
        }
    }
}

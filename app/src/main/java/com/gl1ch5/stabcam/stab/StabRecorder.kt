package com.gl1ch5.stabcam.stab

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.view.Surface
import com.gl1ch5.stabcam.util.Logger
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Video (Surface input) + AAC audio encoders feeding one MP4 muxer.
 * Video PTS = camera frame timestamp - first frame timestamp; audio is placed on the same boot-time axis.
 */
class StabRecorder(
    fd: FileDescriptor,
    width: Int, height: Int, fps: Int, bitrate: Int, hevc: Boolean, orientationHint: Int,
    private val audio: Audio?,
) {
    class Audio(val sampleRate: Int, val channels: Int, val bitrate: Int)

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val videoEnc: MediaCodec
    val inputSurface: Surface
    private val expectedTracks = if (audio != null) 2 else 1
    private var tracksAdded = 0
    private val started = CountDownLatch(1)
    private var muxerRunning = false
    @Volatile private var failed = false
    @Volatile var baseTs = -1L
        private set
    @Volatile private var stopping = false
    private val videoThread: Thread
    private var audioThread: Thread? = null
    var videoFrames = 0L
        private set

    init {
        muxer.setOrientationHint(orientationHint)
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        // B-frames give ~10-15% better quality per bit; not every encoder accepts them, so fall back.
        var enc = MediaCodec.createEncoderByType(mime)
        try {
            val withB = MediaFormat(fmt).apply { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 2) }
            enc.configure(withB, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Logger.i(TAG, "B-кадры включены")
        } catch (e: Exception) {
            Logger.w(TAG, "B-кадры не поддерживаются, без них", e)
            runCatching { enc.release() }
            enc = MediaCodec.createEncoderByType(mime)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        videoEnc = enc
        inputSurface = videoEnc.createInputSurface()
        videoEnc.start()
        Logger.i(TAG, "Видеоэнкодер ${videoEnc.name} ${width}x$height@$fps ${bitrate / 1_000_000} Мбит/с")
        videoThread = Thread({ drain(videoEnc, true) }, "venc-drain").also { it.start() }
        if (audio != null) audioThread = Thread({ audioLoop(audio) }, "audio").also { it.start() }
    }

    fun onFrame(tsNs: Long) {
        if (baseTs < 0) baseTs = tsNs
        videoFrames++
    }

    fun relative(tsNs: Long) = tsNs - baseTs

    /** Blocks until everything is flushed and the file is closed. Call after the last frame has been rendered. */
    fun finish(): Boolean {
        stopping = true
        runCatching { videoEnc.signalEndOfInputStream() }
        videoThread.join(5000)
        audioThread?.join(3000)
        runCatching { videoEnc.stop() }
        runCatching { videoEnc.release() }
        var ok = !failed && muxerRunning
        if (muxerRunning) runCatching { muxer.stop() }.onFailure { ok = false; Logger.e(TAG, "muxer.stop", it) }
        runCatching { muxer.release() }
        Logger.i(TAG, "Запись завершена: кадров=$videoFrames, ok=$ok")
        return ok
    }

    private fun addTrack(f: MediaFormat): Int = synchronized(this) {
        val idx = muxer.addTrack(f)
        if (++tracksAdded == expectedTracks) {
            muxer.start()
            muxerRunning = true
            started.countDown()
        }
        idx
    }

    private fun write(track: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(this) { if (muxerRunning) muxer.writeSampleData(track, buf, info) }
    }

    private fun drain(enc: MediaCodec, video: Boolean) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        try {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = addTrack(enc.outputFormat)
                        if (!started.await(4, TimeUnit.SECONDS)) { failed = true; Logger.e(TAG, "Муксер не стартовал"); return }
                    }
                    i >= 0 -> {
                        val buf = enc.getOutputBuffer(i)
                        if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && track >= 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            write(track, buf, info)
                        }
                        enc.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!video && stopping && i == MediaCodec.INFO_TRY_AGAIN_LATER && audioDone) return
                }
            }
        } catch (e: Exception) {
            failed = true
            Logger.e(TAG, "drain ${if (video) "video" else "audio"}", e)
        }
    }

    @Volatile private var audioDone = false

    @Suppress("MissingPermission")
    private fun audioLoop(a: Audio) {
        val chMask = if (a.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(a.sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, a.sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, a.sampleRate, a.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, a.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val drainer = Thread({ drain(enc, false) }, "aenc-drain").also { it.start() }
        val bytesPerFrame = 2 * a.channels
        val pcm = ByteArray(4096 * bytesPerFrame)
        var anchorBoot = -1L
        var frames = 0L
        try {
            rec.startRecording()
            while (!stopping) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val nowBoot = SystemClock.elapsedRealtimeNanos()
                val nFrames = n / bytesPerFrame
                if (anchorBoot < 0) {
                    val ts = AudioTimestamp()
                    anchorBoot = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS)
                        ts.nanoTime - ts.framePosition * 1_000_000_000L / a.sampleRate
                    else nowBoot - nFrames * 1_000_000_000L / a.sampleRate
                    Logger.i(TAG, "Аудио: якорь времени получен")
                }
                val ptsBoot = anchorBoot + frames * 1_000_000_000L / a.sampleRate
                frames += nFrames
                val base = baseTs
                if (base < 0) continue // video has not started: drop
                val rel = ptsBoot - base
                if (rel < 0) continue
                var off = 0
                while (off < n) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx < 0) { if (stopping) break else continue }
                    val ib = enc.getInputBuffer(idx)!!
                    val len = minOf(ib.capacity(), n - off)
                    ib.clear(); ib.put(pcm, off, len)
                    val chunkPts = rel + (off / bytesPerFrame) * 1_000_000_000L / a.sampleRate
                    enc.queueInputBuffer(idx, 0, len, chunkPts / 1000, 0)
                    off += len
                }
            }
            val idx = enc.dequeueInputBuffer(100_000)
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        } catch (e: Exception) {
            failed = true
            Logger.e(TAG, "audio", e)
        } finally {
            audioDone = true
            runCatching { rec.stop() }
            rec.release()
            drainer.join(3000)
            runCatching { enc.stop() }
            runCatching { enc.release() }
        }
    }

    companion object { const val TAG = "Rec" }
}

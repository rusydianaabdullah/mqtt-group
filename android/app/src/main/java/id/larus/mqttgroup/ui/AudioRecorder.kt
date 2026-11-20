package id.larus.mqttgroup.ui

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * One voice note at a time, recorded to a scratch file rather than memory because
 * [MediaRecorder] only ever writes to a file descriptor. The file is this app's own cache, never
 * a shared one, so nothing else on the phone can see it mid-recording.
 */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /** Starts recording, or does nothing if a recording is already running. */
    fun start() {
        if (recorder != null) return
        val target = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val created = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(target.absolutePath)
            prepare()
            start()
        }
        recorder = created
        file = target
    }

    /**
     * Stops and returns what was recorded, or null if there was nothing worth keeping — either
     * nothing was recording, or the file never grew past its header, which happens when a tap is
     * released before the recorder has really started.
     */
    fun stop(): ByteArray? {
        val current = recorder ?: return null
        val target = file
        recorder = null
        file = null
        return try {
            current.stop()
            current.release()
            target?.let { f -> f.readBytes().also { f.delete() } }?.takeIf { it.size > MIN_VIABLE_BYTES }
        } catch (_: RuntimeException) {
            // stop() throws when start() never really got going — too short a tap to have
            // produced a valid recording.
            target?.delete()
            null
        }
    }

    /** Discards whatever has been recorded so far, for a cancelled press. */
    fun cancel() {
        val current = recorder ?: return
        recorder = null
        runCatching { current.stop() }
        current.release()
        file?.delete()
        file = null
    }

    private companion object {
        const val MIN_VIABLE_BYTES = 1_000
    }
}

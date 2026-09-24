package com.moonmicrophone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Socket
import java.io.OutputStream
import kotlin.concurrent.thread

class MicStreamService : Service() {

    companion object {
        const val EXTRA_HOST   = "host"
        const val EXTRA_PORT   = "port"
        const val DEFAULT_PORT = 7777
        private const val TAG        = "MoonMic"
        private const val CHANNEL_ID = "moon_mic_channel"
        private const val NOTIF_ID   = 1
        private const val SAMPLE_RATE  = 44100
        private const val CHANNEL_CFG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES  = 4096
    }

    inner class LocalBinder : Binder() {
        fun getService(): MicStreamService = this@MicStreamService
    }

    private val binder = LocalBinder()
    private var statusCallback: ((String, Boolean) -> Unit)? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_HOST) ?: run { stopSelf(); return START_NOT_STICKY }
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Подключение..."))
        running = true
        thread(isDaemon = true, name = "MoonMicStream") {
            streamAudio(host, port)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    fun setStatusCallback(cb: (String, Boolean) -> Unit) { statusCallback = cb }
    fun stopStreaming() { running = false; stopSelf() }

    private fun streamAudio(host: String, port: Int) {
        notifyStatus("Подключение к $host...", false)
        try {
            Socket(host, port).use { socket ->
                socket.tcpNoDelay = true
                val out: OutputStream = socket.getOutputStream()
                val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT)
                val bufSize = maxOf(minBuf * 4, CHUNK_BYTES * 4)
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT, bufSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    notifyStatus("Ошибка микрофона", false)
                    stopSelf()
                    return
                }
                recorder.startRecording()
                notifyStatus("Передача аудио", true)
                updateNotification("Подключён — передаю аудио")
                val buffer = ByteArray(CHUNK_BYTES)
                try {
                    while (running) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            out.write(buffer, 0, read)
                            out.flush()
                        }
                    }
                } finally {
                    recorder.stop()
                    recorder.release()
                }
            }
        } catch (e: Exception) {
            notifyStatus("Ошибка: ${e.message}", false)
        } finally {
            notifyStatus("Отключён", false)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Moon Microphone",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Moon Microphone")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun notifyStatus(status: String, connected: Boolean) {
        statusCallback?.invoke(status, connected)
    }
}

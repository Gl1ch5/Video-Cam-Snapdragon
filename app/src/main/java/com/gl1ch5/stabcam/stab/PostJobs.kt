package com.gl1ch5.stabcam.stab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import com.gl1ch5.stabcam.config.ConfigRepository
import com.gl1ch5.stabcam.config.Quality
import com.gl1ch5.stabcam.lut.Luts
import com.gl1ch5.stabcam.util.Logger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Queue of POST clips to process; the work runs in a foreground service so it survives leaving the app. */
object PostJobs {
    class Job(val metaFile: File, val sigma: Double) {
        @Volatile var state = "ожидает" // ожидает | идёт | готово | ошибка | отменено
        @Volatile var fraction = 0f
        @Volatile var text = "В очереди"
        @Volatile var output: Uri? = null
        val name: String get() = metaFile.name.removeSuffix(".meta.json")
    }

    val jobs = CopyOnWriteArrayList<Job>()
    @Volatile var listener: ((Job) -> Unit)? = null
    @Volatile var current: OfflineProcessor? = null
    @Volatile var running = false

    fun find(f: File) = jobs.lastOrNull { it.metaFile == f }

    fun enqueue(ctx: Context, job: Job) {
        jobs.removeAll { it.metaFile == job.metaFile && it.state != "идёт" }
        jobs += job
        listener?.invoke(job)
        ctx.startForegroundService(Intent(ctx, ProcessingService::class.java))
    }

    fun cancelAll() {
        jobs.filter { it.state == "ожидает" }.forEach { it.state = "отменено"; it.text = "Отменено"; listener?.invoke(it) }
        current?.cancelled = true
    }

    /** Options from the current config for one clip. */
    fun buildOptions(ctx: Context, metaFile: File, meta: PostMeta, sigma: Double): OfflineProcessor.Options {
        val cfg = ConfigRepository(ctx).load()
        val q = Quality.entries.firstOrNull { it.width == meta.width && it.height == meta.height && it.fps == meta.fps }
        val base = metaFile.name.removeSuffix(".meta.json")
        return OfflineProcessor.Options(
            sigmaSec = sigma, maxAngleDeg = cfg.stabMaxAngle + 2, minCrop = cfg.stabMinCrop.toDouble(), maxCrop = (cfg.stabCrop + 0.10).toDouble(),
            denoise = if (cfg.stabDenoise > 0f) minOf(1f, cfg.stabDenoise + 0.15f) else 0f, denoiseSigma = cfg.stabDenoiseSigma,
            sharpen = cfg.stabSharpen, bicubic = cfg.stabBicubic,
            lut = Luts.resolve(ctx, cfg.lutId), lutStrength = cfg.lutStrength,
            bitrate = if (q != null) cfg.bitrateFor(q) else 50_000_000, hevc = cfg.codec.equals("hevc", true),
            timeOffsetMs = cfg.stabTimeOffsetMs, gyroAxes = cfg.gyroAxes,
            horizonDeg = cfg.stabHorizonDeg, gravCsv = File(metaFile.parentFile, "$base.grav.csv").takeIf { it.exists() }?.readText(),
        )
    }
}

class ProcessingService : Service() {
    private val channel = "post"
    private fun nm() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(title: String, text: String, fraction: Float?, ongoing: Boolean = true): Notification {
        nm().createNotificationChannel(NotificationChannel(channel, "Обработка видео", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(text)
            .setOngoing(ongoing).setOnlyAlertOnce(true)
            .apply { if (fraction != null) setProgress(1000, (fraction * 1000).toInt(), false) }.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("StabCam", "Готовлю обработку…", 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!PostJobs.running) {
            PostJobs.running = true
            thread(name = "post-jobs") {
                try {
                    while (true) {
                        val job = PostJobs.jobs.firstOrNull { it.state == "ожидает" } ?: break
                        runJob(job)
                    }
                } finally {
                    PostJobs.running = false
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runJob(job: PostJobs.Job) {
        job.state = "идёт"; job.text = "Запуск…"; PostJobs.listener?.invoke(job)
        try {
            val meta = PostMeta(JSONObject(job.metaFile.readText()))
            val gcsv = File(job.metaFile.parentFile, job.name + ".gcsv").takeIf { it.exists() }?.readText() ?: error("нет гиро-лога (.gcsv)")
            val opt = PostJobs.buildOptions(this, job.metaFile, meta, job.sigma)
            val p = OfflineProcessor(this, meta, gcsv, opt) { f, t ->
                job.fraction = f; job.text = t
                nm().notify(1, notification("Обработка: ${meta.video}", t, f))
                PostJobs.listener?.invoke(job)
            }
            PostJobs.current = p
            val out = p.run()
            PostJobs.current = null
            when {
                out != null -> { job.state = "готово"; job.output = out; job.fraction = 1f; job.text = "Готово" }
                p.cancelled -> { job.state = "отменено"; job.text = "Отменено" }
                else -> { job.state = "ошибка"; if (job.text.isEmpty() || job.text.startsWith("Кадр")) job.text = "Ошибка (см. лог)" }
            }
            nm().notify(2, notification("Готово: ${meta.video}", if (out != null) "Файл сохранён в Movies/StabCam" else job.text, null, ongoing = false))
        } catch (e: Exception) {
            Logger.e("PostJobs", "Задача не выполнена", e)
            job.state = "ошибка"; job.text = "Ошибка: ${e.message}"
        }
        PostJobs.listener?.invoke(job)
    }
}

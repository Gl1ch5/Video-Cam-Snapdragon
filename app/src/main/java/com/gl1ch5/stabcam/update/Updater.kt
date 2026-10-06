package com.gl1ch5.stabcam.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.gl1ch5.stabcam.BuildConfig
import com.gl1ch5.stabcam.util.Logger
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks the rolling "nightly" GitHub release and installs the APK through PackageInstaller. */
class Updater(private val ctx: Context, private val repo: String, private val tag: String) {

    data class Release(val id: String, val assetName: String, val url: String, val size: Long, val published: String)

    /** Blocking; call off the main thread. Returns null when the release cannot be read. */
    fun fetchLatest(): Release? = try {
        val c = URL("https://api.github.com/repos/$repo/releases/tags/$tag").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        if (c.responseCode != 200) {
            Logger.w(TAG, "GitHub API ответил ${c.responseCode} (репозиторий приватный или релиза ещё нет?)")
            null
        } else {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            var found: Release? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.getString("name")
                if (n.endsWith(".apk")) {
                    found = Release(n.removePrefix("StabCam-").removeSuffix(".apk"), n, a.getString("browser_download_url"), a.optLong("size"), j.optString("published_at"))
                    break
                }
            }
            if (found == null) Logger.w(TAG, "В релизе $tag нет APK")
            found
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Проверка обновления не удалась", e)
        null
    }

    fun currentId(): String = BuildConfig.VERSION_NAME.substringAfter('-')

    fun isNewer(r: Release) = r.id != currentId()

    fun download(r: Release, onProgress: (Int) -> Unit): File? = try {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "update.apk")
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    val p = if (total > 0) (done * 100 / total).toInt() else 0
                    if (p != last) { last = p; onProgress(p) }
                }
            }
        }
        Logger.i(TAG, "Скачано ${out.length() / 1024} КБ")
        out
    } catch (e: Exception) {
        Logger.e(TAG, "Скачивание не удалось", e)
        null
    }

    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite("update", 0, apk.length()).use { o -> input.copyTo(o); s.fsync(o) } }
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            s.commit(pi.intentSender)
        }
        Logger.i(TAG, "Установка отправлена (сессия $id)")
    }

    companion object { const val TAG = "Update" }
}

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Logger.i(Updater.TAG, "Нужно подтверждение установки")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Logger.i(Updater.TAG, "Установлено")
            else -> Logger.e(Updater.TAG, "Установка не удалась: status=$status $msg")
        }
    }
}

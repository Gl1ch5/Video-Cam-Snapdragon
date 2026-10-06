package com.gl1ch5.stabcam.ui

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.gl1ch5.stabcam.R
import com.gl1ch5.stabcam.camera.CameraCaps
import com.gl1ch5.stabcam.camera.VideoCamera
import com.gl1ch5.stabcam.config.AppConfig
import com.gl1ch5.stabcam.config.ConfigRepository
import com.gl1ch5.stabcam.config.Quality
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), VideoCamera.Listener {

    private lateinit var repo: ConfigRepository
    private lateinit var cfg: AppConfig
    private lateinit var camera: VideoCamera

    private lateinit var preview: SurfaceView
    private lateinit var previewFrame: AspectFrameLayout
    private lateinit var btnOis: TextView
    private lateinit var btnEis: TextView
    private lateinit var btnEv: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnRecord: RecordButton
    private lateinit var btnFlip: ImageButton
    private lateinit var thumb: ImageView
    private lateinit var zoomRow: LinearLayout
    private lateinit var evPanel: View
    private lateinit var evSeek: SeekBar
    private lateinit var evValue: TextView
    private lateinit var recIndicator: View
    private lateinit var recDot: View
    private lateinit var recTime: TextView
    private lateinit var info: TextView
    private lateinit var topBar: View

    private val main = Handler(Looper.getMainLooper())
    private var caps: CameraCaps? = null
    private var quality = Quality.UHD60
    private var back = true
    private var controls = VideoCamera.Controls(ois = true, stockEis = false)
    private var surfaceReady = false
    private var pendingPreviewSize: Size? = null
    private var deviceOrientation = 0
    private var recordStart = 0L
    private var lastVideo: Uri? = null

    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = ConfigRepository(this)
        camera = VideoCamera(this, this)
        bindViews()
        loadConfig()

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val want = pendingPreviewSize
                // Wait until the buffer size we asked for is applied, then (re)open.
                if (want == null || (width == want.width && height == want.height) || (width == want.height && height == want.width)) {
                    surfaceReady = true
                    openCamera()
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                camera.close()
            }
        })

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val snapped = ((o + 45) / 90 * 90) % 360
                if (snapped != deviceOrientation) {
                    deviceOrientation = snapped
                    rotateIcons(-snapped.toFloat())
                }
            }
        }
    }

    private fun bindViews() {
        preview = findViewById(R.id.preview)
        previewFrame = findViewById(R.id.previewFrame)
        btnOis = findViewById(R.id.btnOis)
        btnEis = findViewById(R.id.btnEis)
        btnEv = findViewById(R.id.btnEv)
        btnQuality = findViewById(R.id.btnQuality)
        btnSettings = findViewById(R.id.btnSettings)
        btnRecord = findViewById(R.id.btnRecord)
        btnFlip = findViewById(R.id.btnFlip)
        thumb = findViewById(R.id.thumb)
        zoomRow = findViewById(R.id.zoomRow)
        evPanel = findViewById(R.id.evPanel)
        evSeek = findViewById(R.id.evSeek)
        evValue = findViewById(R.id.evValue)
        recIndicator = findViewById(R.id.recIndicator)
        recDot = findViewById(R.id.recDot)
        recTime = findViewById(R.id.recTime)
        info = findViewById(R.id.info)
        topBar = findViewById(R.id.topBar)

        btnRecord.setOnClickListener { toggleRecording() }
        btnOis.setOnClickListener {
            if (caps?.hasOis != true) {
                toast("OIS на этой камере недоступен")
                return@setOnClickListener
            }
            setControls(controls.copy(ois = !controls.ois))
            repo.set("camera.ois", controls.ois)
            toast(if (controls.ois) "Аппаратный OIS: вкл" else "Аппаратный OIS: выкл")
        }
        btnEis.setOnClickListener {
            if (caps?.hasStockEis != true) {
                toast("Стоковый EIS недоступен")
                return@setOnClickListener
            }
            setControls(controls.copy(stockEis = !controls.stockEis))
            repo.set("camera.stockEis", controls.stockEis)
            toast(if (controls.stockEis) "Стоковый EIS: вкл (кроп + мыло)" else "Стоковый EIS: выкл")
        }
        btnEv.setOnClickListener {
            evPanel.visibility = if (evPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        evSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val c = caps ?: return
                setControls(controls.copy(evIndex = p + c.evRange.lower))
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        btnQuality.setOnClickListener { cycleQuality() }
        btnSettings.setOnClickListener {
            if (!camera.isRecording) startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnFlip.setOnClickListener {
            if (camera.isRecording) return@setOnClickListener
            back = !back
            controls = controls.copy(zoom = 1f, evIndex = 0)
            btnFlip.animate().rotationBy(180f).setDuration(250).start()
            openCamera()
        }
        thumb.setOnClickListener {
            lastVideo?.let { uri ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        }
    }

    private fun loadConfig() {
        cfg = repo.load()
        quality = cfg.quality
        back = cfg.lensFacingBack
        controls = controls.copy(ois = cfg.ois, stockEis = cfg.stockEis)
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed the config.
        val wasBack = back
        loadConfig()
        back = wasBack
        orientationListener.enable()
        if (!hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        } else {
            openCamera()
        }
        refreshThumb()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
        if (camera.isRecording) camera.stopRecording()
        camera.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        camera.release()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            toast("Без доступа к камере приложение не работает")
        }
    }

    private fun hasPermissions() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val c = CameraCaps.find(this, back) ?: CameraCaps.find(this, !back) ?: run {
            toast("Камера не найдена")
            return
        }
        back = c.facingBack
        caps = c
        val supported = c.supportedQualities
        if (quality !in supported) quality = supported.firstOrNull() ?: Quality.FHD30
        if (!c.hasOis) controls = controls.copy(ois = false)

        val size = c.previewSize()
        if (pendingPreviewSize != size) {
            pendingPreviewSize = size
            surfaceReady = false
            preview.holder.setFixedSize(size.width, size.height)
            return // surfaceChanged will call back into openCamera()
        }
        if (!surfaceReady) return

        camera.open(c, preview.holder.surface, cfg, quality, controls)
        updateUi()
    }

    private fun setControls(c: VideoCamera.Controls) {
        controls = c
        camera.updateControls(c)
        updateUi()
    }

    private fun cycleQuality() {
        val c = caps ?: return
        if (camera.isRecording) return
        val list = c.supportedQualities
        if (list.isEmpty()) return
        quality = list[(list.indexOf(quality) + 1) % list.size]
        repo.set("video.quality", quality.id)
        camera.open(c, preview.holder.surface, cfg, quality, controls)
        updateUi()
    }

    private fun toggleRecording() {
        if (camera.isRecording) {
            btnRecord.isEnabled = false
            camera.stopRecording()
        } else {
            val c = caps ?: return
            btnRecord.isEnabled = false
            val hint = if (c.facingBack) (c.sensorOrientation + deviceOrientation) % 360
            else (c.sensorOrientation - deviceOrientation + 360) % 360
            camera.startRecording(hint)
        }
    }

    // --- VideoCamera.Listener (camera thread) ---

    override fun onRecordingStarted() = runOnUiThread {
        btnRecord.isEnabled = true
        btnRecord.recording = true
        recordStart = SystemClock.elapsedRealtime()
        setRecordingUi(true)
        tickTimer()
    }

    override fun onRecordingStopped(uri: Uri?) = runOnUiThread {
        btnRecord.isEnabled = true
        btnRecord.recording = false
        setRecordingUi(false)
        if (uri != null) {
            lastVideo = uri
            loadThumb(uri)
            toast("Сохранено в Movies/StabCam")
        } else {
            toast("Запись не сохранена")
        }
    }

    override fun onError(message: String) = runOnUiThread {
        btnRecord.isEnabled = true
        toast(message)
    }

    // --- UI ---

    private fun setRecordingUi(rec: Boolean) {
        val v = if (rec) View.INVISIBLE else View.VISIBLE
        topBar.visibility = v
        btnFlip.visibility = v
        thumb.visibility = v
        info.visibility = if (rec || !cfg.showInfo) View.GONE else View.VISIBLE
        evPanel.visibility = View.GONE
        recIndicator.visibility = if (rec) View.VISIBLE else View.GONE
    }

    private fun tickTimer() {
        if (!camera.isRecording) return
        val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
        recTime.text = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
        recDot.alpha = if (s % 2 == 0L) 1f else 0.25f
        main.postDelayed({ tickTimer() }, 500)
    }

    private fun updateUi() {
        val c = caps ?: return
        styleToggle(btnOis, controls.ois && c.hasOis, c.hasOis)
        styleToggle(btnEis, controls.stockEis && c.hasStockEis, c.hasStockEis)
        val ev = controls.evIndex * c.evStep
        btnEv.text = String.format(Locale.US, "EV %+.1f", ev).replace("+0.0", "0.0")
        evValue.text = String.format(Locale.US, "%+.1f", ev)
        evSeek.max = c.evRange.upper - c.evRange.lower
        evSeek.progress = controls.evIndex - c.evRange.lower
        btnQuality.text = quality.label
        buildZoomChips(c)

        val codec = if (cfg.codec.equals("hevc", true)) "HEVC" else "H.264"
        val preset = repo.activePreset?.name ?: "default"
        info.text = "${quality.width}×${quality.height}@${quality.fps} · $codec ${cfg.bitrateFor(quality) / 1_000_000} Мбит/с · " +
            "OIS ${if (controls.ois && c.hasOis) "вкл" else "выкл"} · EIS ${if (controls.stockEis) "вкл" else "выкл"} · $preset"
        info.visibility = if (cfg.showInfo && !camera.isRecording) View.VISIBLE else View.GONE
    }

    private fun styleToggle(v: TextView, on: Boolean, available: Boolean) {
        v.setTextColor(getColor(if (on) R.color.accent else R.color.text_dim))
        v.paintFlags = if (on) v.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        else v.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        v.alpha = if (available) 1f else 0.4f
    }

    private fun buildZoomChips(c: CameraCaps) {
        zoomRow.removeAllViews()
        val stops = buildList {
            if (c.zoomRange.lower < 0.95f) add(c.zoomRange.lower)
            add(1f)
            if (c.zoomRange.upper >= 2f) add(2f)
            if (c.zoomRange.upper >= 5f) add(5f)
        }
        if (stops.size < 2) {
            zoomRow.visibility = View.GONE
            return
        }
        zoomRow.visibility = View.VISIBLE
        val size = dp(40)
        for (z in stops) {
            val selected = kotlin.math.abs(controls.zoom - z) < 0.01f
            val label = when {
                z < 1f -> String.format(Locale.US, "%.1f", z).replace('.', ',')
                selected -> "${z.toInt()}×"
                else -> "${z.toInt()}"
            }
            val chip = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (selected) 15f else 13f)
                setTextColor(getColor(if (selected) R.color.accent else R.color.text))
                if (selected) setBackgroundResource(R.drawable.bg_circle)
                rotation = -deviceOrientation.toFloat()
                setOnClickListener { setControls(controls.copy(zoom = z)) }
            }
            zoomRow.addView(chip, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
    }

    private fun rotateIcons(deg: Float) {
        val views = listOf<View>(btnFlip, thumb, btnSettings, btnQuality, btnOis, btnEis, btnEv) +
            (0 until zoomRow.childCount).map { zoomRow.getChildAt(it) }
        views.forEach { it.animate().rotation(deg).setDuration(200).start() }
    }

    private fun refreshThumb() {
        thread {
            val uri = queryLastVideo() ?: return@thread
            runOnUiThread {
                lastVideo = uri
                loadThumb(uri)
            }
        }
    }

    private fun queryLastVideo(): Uri? {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return runCatching {
            contentResolver.query(
                collection, arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/StabCam%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        }.getOrNull()
    }

    private fun loadThumb(uri: Uri) {
        thread {
            val bmp: Bitmap? = runCatching { contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
            runOnUiThread { bmp?.let { thumb.setImageBitmap(it) } }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

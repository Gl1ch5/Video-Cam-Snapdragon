package com.gl1ch5.stabcam.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
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
import com.gl1ch5.stabcam.update.Updater
import com.gl1ch5.stabcam.lut.Luts
import com.gl1ch5.stabcam.config.Presets
import com.gl1ch5.stabcam.stab.GyroTracker
import org.json.JSONArray
import com.gl1ch5.stabcam.util.Logger
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), VideoCamera.Listener {
    companion object { private const val REQ_LUT = 41 }


    private lateinit var repo: ConfigRepository
    private lateinit var cfg: AppConfig
    private lateinit var camera: VideoCamera

    private lateinit var preview: SurfaceView
    private lateinit var previewFrame: AspectFrameLayout
    private lateinit var btnOis: TextView
    private lateinit var btnEis: TextView
    private lateinit var btnStab: TextView
    private lateinit var btnHdr: TextView
    private lateinit var btnLut: TextView
    private lateinit var lutPicker: LutPicker
    private lateinit var quickMenu: QuickMenu
    private var needReopen = false
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

    private var diagText: String? = null
    private var prevZoom = 1f
    private lateinit var shutterFlash: View
    private var probed = false

    private lateinit var orientationListener: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        repo = ConfigRepository(this)
        // The previous run crashed: start in safe mode (no GL stabilisation, no HDR) and show why.
        val crash = Logger.previousCrash
        if (crash != null) {
            Logger.previousCrash = null
            runCatching { repo.set("stab.enabled", false); repo.set("video.hdr", "off") }
        }
        setContentView(R.layout.activity_main)
        camera = VideoCamera(this, this)
        bindViews()
        loadConfig()
        if (crash != null) main.post { showCrash(crash) }

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
        btnStab = findViewById(R.id.btnStab)
        btnHdr = findViewById(R.id.btnHdr)
        btnLut = findViewById(R.id.btnLut)
        lutPicker = LutPicker(this, { id -> selectLut(id) }, {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_LUT)
        })
        btnLut.setOnClickListener { if (!camera.isRecording) lutPicker.show(btnLut, cfg.lutId) }
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
        shutterFlash = findViewById(R.id.shutterFlash)
        listOf<View>(btnLut, btnOis, btnEis, btnStab, btnHdr, btnEv, btnQuality, btnSettings, btnFlip, thumb).forEach { pressable(it) }

        btnRecord.setOnClickListener { toggleRecording() }
        btnOis.setOnClickListener {
            setControls(controls.copy(ois = !controls.ois))
            repo.set("camera.ois", controls.ois)
            toast(if (controls.ois) "Аппаратный OIS: вкл" else "Аппаратный OIS: выкл")
        }
        btnOis.setOnLongClickListener { runProbe(); true }
        findViewById<View>(R.id.modeLabel).setOnLongClickListener {
            val next = cfg.stabPreviewRot % 4 + 1
            repo.set("stab.previewRot", next)
            cfg = repo.load()
            camera.setPreviewRot(next)
            Logger.i("App", "Ориентация превью (STAB): вариант $next/4")
            toast("Поворот превью: $next/4")
            true
        }
        btnHdr.setOnClickListener {
            if (camera.isRecording) return@setOnClickListener
            if (caps?.supportsHlg10 != true) { toast("10-бит HLG на этой камере недоступен"); return@setOnClickListener }
            repo.set("video.hdr", if (cfg.hdr) "off" else "hlg10")
            cfg = repo.load()
            toast(if (cfg.hdr) "HLG 10-бит: вкл (нужен STAB; на экранах без HDR картинка блёклая)" else "HLG 10-бит: выкл")
            openCamera()
        }
        btnStab.setOnClickListener {
            if (camera.isRecording) return@setOnClickListener
            if (caps?.facingBack != true) { toast("Стабилизация по гиро: только основная камера"); return@setOnClickListener }
            controls = controls.copy(stab = !controls.stab)
            repo.set("stab.enabled", controls.stab)
            cfg = repo.load()
            toast(if (controls.stab) "Гиро-стабилизация: вкл" else "Гиро-стабилизация: выкл")
            openCamera()
        }
        btnStab.setOnLongClickListener {
            if (camera.isRecording) return@setOnLongClickListener true
            val cur = cfg.gyroAxes
            val idx = GyroTracker.CANDIDATES.indexOf(cur).let { if (it < 0) -1 else it }
            val next = GyroTracker.CANDIDATES[(idx + 1) % GyroTracker.CANDIDATES.size]
            repo.set("stab.gyroAxes", JSONArray(next))
            cfg = repo.load()
            val label = next.joinToString(",")
            Logger.i("App", "Оси гироскопа: $label (вариант ${(idx + 1) % GyroTracker.CANDIDATES.size + 1}/${GyroTracker.CANDIDATES.size})")
            toast("Оси гиро: $label (${(idx + 1) % GyroTracker.CANDIDATES.size + 1}/${GyroTracker.CANDIDATES.size}). Потрясите телефон: картинка должна стоять")
            openCamera()
            true
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
            fade(evPanel, evPanel.visibility != View.VISIBLE, View.GONE, rise = true)
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
        btnQuality.setOnLongClickListener { startActivity(Intent(this, LogActivity::class.java)); true }
        quickMenu = QuickMenu(this)
        btnSettings.setOnClickListener { if (!camera.isRecording) showQuickMenu() }
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
        Logger.i("App", "Конфиг: ${cfg.quality.id} ${cfg.codec} OIS=${cfg.ois} stockEis=${cfg.stockEis} force=${cfg.forceAllQualities} vendorTags=${cfg.vendorTags.size}")
        back = cfg.lensFacingBack
        controls = controls.copy(ois = cfg.ois, stockEis = cfg.stockEis, stab = cfg.stabEnabled)
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
        maybeCheckUpdate()
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
        Logger.i("App", "Устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, SoC ${android.os.Build.SOC_MODEL}, preset ${repo.activePreset?.name}")
        Logger.i("App", "Камера ${c.id}: OIS(заявлен)=${c.hasOis} EIS=${c.hasStockEis} качества=${c.qualities(cfg.forceAllQualities).joinToString { it.label }}")
        val supported = c.qualities(cfg.forceAllQualities)
        if (quality !in supported) quality = supported.firstOrNull() ?: Quality.FHD30

        val base = c.previewSize()
        val useStab = (controls.stab || cfg.postMode) && c.facingBack && !quality.highSpeed
        // With GL stabilisation the preview buffer is rendered by us, already upright (portrait).
        val size = if (useStab) Size(base.height, base.width) else base
        if (pendingPreviewSize != size) {
            pendingPreviewSize = size
            surfaceReady = false
            preview.holder.setFixedSize(size.width, size.height)
            return // surfaceChanged will call back into openCamera()
        }
        if (!surfaceReady) return

        Logger.i("App", "Открываю камеру: ${quality.label}")
        camera.open(c, preview.holder.surface, cfg, quality, controls)
        updateUi()
    }

    private fun setControls(c: VideoCamera.Controls) {
        controls = c
        Logger.i("App", "Управление: OIS=${c.ois} EIS=${c.stockEis} EV=${c.evIndex} zoom=${c.zoom}")
        camera.updateControls(c)
        updateUi()
    }

    private fun cycleQuality() {
        val c = caps ?: return
        if (camera.isRecording) return
        val list = c.qualities(cfg.forceAllQualities)
        if (list.isEmpty()) return
        quality = list[(list.indexOf(quality) + 1) % list.size]
        repo.set("video.quality", quality.id)
        openCamera()
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

    private fun runCalibration() {
        showDiag("Калибровка осей: потрясите телефон 4 секунды влево-вправо и вверх-вниз, направив на сцену с деталями…")
        camera.calibrateAxes { r, err ->
            runOnUiThread {
                if (r == null) { showDiag(err, 9000); return@runOnUiThread }
                val pct = (r.best.score * 100).toInt()
                val curOk = r.current != null && r.current.axes == r.best.axes
                if (!r.confident) showDiag("Не удалось уверенно определить оси (лучший вариант ${r.best.axes.joinToString(",")} — $pct%). Потрясите сильнее.", 10000)
                else if (curOk) showDiag("Оси гироскопа верные: ${r.best.axes.joinToString(",")} (совпадение $pct%)", 8000)
                else {
                    repo.set("stab.gyroAxes", JSONArray(r.best.axes)); cfg = repo.load()
                    showDiag("Оси изменены на ${r.best.axes.joinToString(",")} (совпадение $pct%). Перезапускаю камеру…", 6000)
                    main.postDelayed({ openCamera() }, 600)
                }
            }
        }
    }

    override fun onSessionReady() = runOnUiThread {
        val calib = getSharedPreferences("calib", MODE_PRIVATE)
        if (calib.getBoolean("pending", false)) {
            calib.edit().putBoolean("pending", false).apply()
            if ((controls.stab || cfg.postMode) && caps?.facingBack == true) main.postDelayed({ runCalibration() }, 1200) else toast("Для калибровки включите STAB")
        }
        applyLut()
        if (!probed && cfg.probeOnStart) {
            probed = true
            runProbe()
        }
    }

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
        Logger.e("App", message)
        btnRecord.isEnabled = true
        toast(message)
    }

    // --- UI ---

    private fun setRecordingUi(rec: Boolean) {
        fade(topBar, !rec)
        fade(btnLut, !rec)
        fade(btnFlip, !rec)
        fade(thumb, !rec)
        fade(evPanel, false, View.GONE)
        fade(info, !rec && (cfg.showInfo || diagText != null), View.GONE)
        fade(recIndicator, rec, View.GONE, rise = true)
        shutterFlash.alpha = 0.45f
        shutterFlash.animate().alpha(0f).setDuration(240).start()
    }

    /** Fade (and optionally slight rise) instead of an instant visibility flip. */
    private fun fade(v: View, show: Boolean, hidden: Int = View.INVISIBLE, rise: Boolean = false) {
        v.animate().cancel()
        if (show) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                if (rise) v.translationY = dp(8).toFloat()
                v.visibility = View.VISIBLE
            }
            v.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(140).withEndAction { v.visibility = hidden }.start()
        }
    }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    private fun setTextFade(tv: TextView, text: String) {
        if (tv.text.toString() == text) return
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(90).withEndAction {
            tv.text = text
            tv.animate().alpha(1f).setDuration(140).start()
        }.start()
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
        val simple = cfg.simpleMode
        listOf(btnOis, btnEis, btnHdr, btnEv).forEach { it.visibility = if (simple) View.GONE else View.VISIBLE }
        setTextFade(btnStab, if (simple) "Стаб" else "STAB")
        styleToggle(btnOis, controls.ois, true)
        styleToggle(btnEis, controls.stockEis && c.hasStockEis, c.hasStockEis)
        styleToggle(btnStab, controls.stab && c.facingBack && !cfg.postMode, c.facingBack && !cfg.postMode)
        styleToggle(btnHdr, cfg.hdr && c.supportsHlg10 && controls.stab, c.supportsHlg10)
        val ev = controls.evIndex * c.evStep
        setTextFade(btnEv, String.format(Locale.US, "EV %+.1f", ev).replace("+0.0", "0.0"))
        btnEv.alpha = if (controls.evIndex == 0) 0.6f else 1f
        evValue.text = String.format(Locale.US, "%+.1f", ev)
        evSeek.max = c.evRange.upper - c.evRange.lower
        evSeek.progress = controls.evIndex - c.evRange.lower
        setTextFade(btnQuality, quality.label)
        buildZoomChips(c)

        val codec = if (cfg.codec.equals("hevc", true)) "HEVC" else "H.264"
        val preset = repo.activePreset?.name ?: "default"
        info.text = diagText ?: "${quality.width}×${quality.height}@${quality.fps} · $codec ${cfg.bitrateFor(quality) / 1_000_000} Мбит/с · " +
            "OIS ${if (controls.ois) "вкл" else "выкл"} · EIS ${if (controls.stockEis) "вкл" else "выкл"} · $preset"
        fade(info, (cfg.showInfo || diagText != null) && !camera.isRecording, View.GONE)
    }

    private fun styleToggle(v: TextView, on: Boolean, available: Boolean) {
        val to = getColor(if (on) R.color.accent else R.color.text_dim)
        val from = v.currentTextColor
        if (from != to) ValueAnimator.ofArgb(from, to).apply {
            duration = 180
            addUpdateListener { v.setTextColor(it.animatedValue as Int) }
            start()
        }
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
        val size = dp(32)
        val changed = kotlin.math.abs(prevZoom - controls.zoom) > 0.01f
        prevZoom = controls.zoom
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (selected) 13f else 12f)
                setTextColor(getColor(if (selected) R.color.accent else R.color.text))
                if (selected) setBackgroundResource(R.drawable.bg_circle)
                rotation = -deviceOrientation.toFloat()
                setOnClickListener { setControls(controls.copy(zoom = z)) }
                pressable(this)
                if (selected && changed) {
                    scaleX = 0.7f; scaleY = 0.7f
                    animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.5f)).start()
                }
            }
            zoomRow.addView(chip, LinearLayout.LayoutParams(size, size).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
    }

    private fun rotateIcons(deg: Float) {
        val views = listOf<View>(btnFlip, thumb, btnSettings, btnQuality, btnLut, btnOis, btnEis, btnStab, btnHdr, btnEv) +
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

    private fun showQuickMenu() {
        fun cycle(levels: List<Presets.Level>) {
            val next = (Presets.indexOf(levels, repo.effectiveJson()) + 1) % levels.size
            Presets.apply(repo, levels[next])
            cfg = repo.load()
            needReopen = true
        }
        fun label(levels: List<Presets.Level>) = levels[Presets.indexOf(levels, repo.effectiveJson())].label
        val items = if (cfg.simpleMode) listOf(
            QuickMenu.Item("Качество", { quality.label }, {
                val c = caps ?: return@Item
                val list = c.qualities(cfg.forceAllQualities)
                if (list.isNotEmpty()) { quality = list[(list.indexOf(quality) + 1) % list.size]; repo.set("video.quality", quality.id); cfg = repo.load(); needReopen = true }
            }),
            QuickMenu.Item("Стабилизация", { label(Presets.strength) }, { cycle(Presets.strength) }),
            QuickMenu.Item("LUT", { if (cfg.lutId.isEmpty()) "нет" else lutTitle(cfg.lutId) }, { main.postDelayed({ lutPicker.show(btnLut, cfg.lutId) }, 120) }, closeOnTap = true),
            QuickMenu.Item("Режим", { "Простой" }, { repo.set("ui.mode", "pro"); cfg = repo.load(); needReopen = true; updateUi() }),
            QuickMenu.Item("Все настройки  ›", { "" }, { startActivity(Intent(this, SettingsActivity::class.java)) }, closeOnTap = true, accent = true),
        ) else listOf(
            QuickMenu.Item("Качество", { quality.label }, {
                val c = caps ?: return@Item
                val list = c.qualities(cfg.forceAllQualities)
                if (list.isNotEmpty()) {
                    quality = list[(list.indexOf(quality) + 1) % list.size]
                    repo.set("video.quality", quality.id)
                    cfg = repo.load()
                    needReopen = true
                }
            }),
            QuickMenu.Item("Стабилизация", { label(Presets.strength) }, { cycle(Presets.strength) }),
            QuickMenu.Item("Горизонт", { label(Presets.horizon) }, { cycle(Presets.horizon) }),
            QuickMenu.Item("Шумоподавление", { label(Presets.denoise) }, { cycle(Presets.denoise) }),
            QuickMenu.Item("Резкость", { label(Presets.sharpen) }, { cycle(Presets.sharpen) }),
            QuickMenu.Item("LUT", { if (cfg.lutId.isEmpty()) "нет" else lutTitle(cfg.lutId) }, {
                main.postDelayed({ lutPicker.show(btnLut, cfg.lutId) }, 120)
            }, closeOnTap = true),
            QuickMenu.Item("Режим", { "Про" }, { repo.set("ui.mode", "simple"); cfg = repo.load(); updateUi() }),
            QuickMenu.Item("Все настройки  ›", { "" }, { startActivity(Intent(this, SettingsActivity::class.java)) }, closeOnTap = true, accent = true),
        )
        needReopen = false
        quickMenu.show(btnSettings, items) { if (needReopen) { needReopen = false; openCamera() } }
    }

    private fun selectLut(id: String) {
        repo.set("video.lut", id)
        cfg = repo.load()
        applyLut()
        toast(if (id.isEmpty()) "LUT выключен" else "LUT: ${lutTitle(id)}" + if (controls.stab) "" else " (нужен STAB)")
    }

    private fun lutTitle(id: String) = Luts.builtin.firstOrNull { it.id == id }?.name ?: id.removePrefix("file:").removeSuffix(".cube")

    private fun applyLut() {
        thread {
            val l = Luts.resolve(this, cfg.lutId)
            camera.setLut(l, cfg.lutStrength)
            runOnUiThread {
                setTextFade(btnLut, if (cfg.lutId.isEmpty()) "LUT" else lutTitle(cfg.lutId).take(10))
                btnLut.setTextColor(getColor(if (cfg.lutId.isEmpty()) R.color.text else R.color.accent))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LUT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        thread {
            val id = Luts.importCube(this, uri)
            runOnUiThread { if (id == null) toast("Не удалось прочитать .cube (нужен 3D LUT)") else selectLut(id) }
        }
    }

    private fun showCrash(trace: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Прошлый запуск завершился ошибкой")
            .setMessage("Включён безопасный режим: STAB и HLG выключены (включаются кнопками). Текст ошибки:\n\n$trace")
            .setPositiveButton("Копировать") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("crash", trace))
                toast("Скопировано")
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun runProbe() {
        if (camera.isRecording) return
        showDiag("Проверяю стабилизацию…")
        camera.probe { lines ->
            runOnUiThread {
                val ois = lines.firstOrNull { it.contains("OIS вкл (стандарт)") }
                val sum = (if (ois?.startsWith("✔") == true) "OIS: камера подтвердила включение" else "OIS: камера НЕ подтвердила (см. лог)") +
                    "\n" + lines.count { it.startsWith("✔") } + " принято, " + lines.count { it.startsWith("✖") } + " отклонено · подробности: меню → Лог"
                showDiag(sum, 12000)
            }
        }
    }

    private fun showDiag(text: String, hideAfter: Long = 0) {
        diagText = text
        info.text = text
        fade(info, true, View.GONE)
        if (hideAfter > 0) main.postDelayed({ if (diagText == text) { diagText = null; updateUi() } }, hideAfter)
    }

    /** Every return to the app (at most once per 10 minutes), independent of the camera session. */
    private fun maybeCheckUpdate() {
        if (!cfg.updateAuto) return
        val prefs = getSharedPreferences("upd", MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last_check", 0) < 10 * 60_000L) return
        checkUpdate(manual = false)
    }

    private fun checkUpdate(manual: Boolean) {
        val u = Updater(this, cfg.updateRepo, cfg.updateTag)
        val prefs = getSharedPreferences("upd", MODE_PRIVATE)
        thread {
            val r = u.fetchLatest()
            when {
                r == null -> { Logger.w(Updater.TAG, "Проверка обновления: ${u.lastStatus}"); if (manual) runOnUiThread { toast("Не удалось проверить обновление: ${u.lastStatus}") } }
                !u.isNewer(r) -> {
                    Logger.i(Updater.TAG, "Версия актуальна (${u.currentId()}), ${u.lastStatus}")
                    if (manual) runOnUiThread { toast("Установлена последняя версия") }
                }
                else -> {
                    Logger.i(Updater.TAG, "Найдена новая версия ${r.id} (сейчас ${u.currentId()})")
                    if (cfg.updateSilent && u.canInstall() && !manual) {
                        runOnUiThread { showDiag("Обновление ${r.id}: загрузка…") }
                        val f = u.download(r) { p -> runOnUiThread { showDiag("Обновление ${r.id}: $p%") } }
                        if (f != null) { runOnUiThread { showDiag("Устанавливаю ${r.id}…", 8000) }; runCatching { u.install(f) }.onFailure { Logger.e(Updater.TAG, "Установка", it) } }
                        return@thread
                    }
                    // "Позже" is remembered per version so the card does not nag on every launch
                    if (!manual && prefs.getString("skip", "") == r.id) return@thread
                    runOnUiThread {
                        if (!isFinishing && !camera.isRecording) UpdateDialog(this).show(u, r) { prefs.edit().putString("skip", r.id).apply() }
                    }
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

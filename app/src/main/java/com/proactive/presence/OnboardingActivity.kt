package com.proactive.presence

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * First-run setup in the tide voice (cobalt ground, poster type).
 * 1. Welcome. 2. Engine: detected chip, Auto/NPU/GPU/CPU, one-tap benchmark.
 * 3. Guided 30s capture with prompts. 4. On-device profile build. 5. Done.
 */
class OnboardingActivity : AppCompatActivity() {

    private val prompts = listOf(
        "Glasses on. Stare it down.",
        "Glasses off. There you are.",
        "Closer. Fill the frame.",
        "Back up. Slow look left.",
        "Slow look right. Hold it.",
        "Chin up, chin down. Home straight."
    )
    private val moveIds = listOf(R.id.move0, R.id.move1, R.id.move2, R.id.move3, R.id.move4, R.id.move5)
    private var lastMove = -1

    private fun tickHaptic() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private lateinit var verifier: FaceVerifier
    private val bg = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var ticking = false
    private var secondsLeft = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        verifier = FaceVerifier(this)

        showOnly(R.id.stepWelcome)
        findViewById<Button>(R.id.btnWelcomeNext).setOnClickListener {
            if (!hasCamera()) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 7)
                return@setOnClickListener
            }
            showEngine()
        }
        findViewById<Button>(R.id.btnEngineNext).setOnClickListener { persistEngine(); showCapture() }
        findViewById<Button>(R.id.btnBench).setOnClickListener { benchmark() }
        findViewById<Button>(R.id.btnRecord).setOnClickListener { startCapture() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }

        if (hasCamera() && intent.getBooleanExtra("skip_welcome", false)) showEngine()
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showOnly(id: Int) {
        for (s in listOf(R.id.stepWelcome, R.id.stepEngine, R.id.stepCapture, R.id.stepBuilding, R.id.stepDone)) {
            findViewById<View>(s).visibility = if (s == id) View.VISIBLE else View.GONE
        }
    }

    // ---------- step 2: engine ----------

    private fun showEngine() {
        showOnly(R.id.stepEngine)
        val chip = SocInfo.name()
        findViewById<TextView>(R.id.socText).text =
            "Detected: $chip. " +
                (if (SocInfo.hasNpuLikelihood()) "An NPU is likely — NNAPI will find it."
                else "No NPU spotted — GPU or CPU will carry it.")
        when (AccelStore.get(this)) {
            Accel.AUTO -> R.id.rbAuto
            Accel.NPU -> R.id.rbNpu
            Accel.GPU -> R.id.rbGpu
            Accel.CPU -> R.id.rbCpu
        }.let { findViewById<RadioGroup>(R.id.engineGroup).check(it) }
        if (!verifier.modelPresent()) {
            findViewById<TextView>(R.id.benchText).text =
                "Face model not bundled yet — the guard will run presence-only until it is."
            findViewById<Button>(R.id.btnBench).isEnabled = false
        } else {
            // Proactive footprint: measure the real chip the moment you arrive.
            findViewById<TextView>(R.id.benchText).text = "Feeling your chip…"
            bg.execute {
                val times = timeAll()
                runOnUiThread { showVerdict(times) }
            }
        }
    }

    private fun picked(): Accel =
        when (findViewById<RadioGroup>(R.id.engineGroup).checkedRadioButtonId) {
            R.id.rbNpu -> Accel.NPU
            R.id.rbGpu -> Accel.GPU
            R.id.rbCpu -> Accel.CPU
            else -> Accel.AUTO
        }

    private fun persistEngine() {
        val pick = picked()
        if (pick == Accel.AUTO && verifier.modelPresent()) {
            // Resolve Auto now so the guard never benchmarks on your battery.
            bg.execute {
                val winner = fastest()
                runOnUiThread {
                    AccelStore.set(this, winner ?: Accel.AUTO)
                    Toast.makeText(this, "Engine: ${winner?.title ?: "auto"}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            AccelStore.set(this, pick)
            verifier.configure(pick)
        }
    }

    /** Milliseconds per inference per engine, measured live on this phone. */
    private fun timeAll(): Map<Accel, Double?> {
        val out = LinkedHashMap<Accel, Double?>()
        for (a in listOf(Accel.NPU, Accel.GPU, Accel.CPU)) {
            verifier.configure(a, silent = true)
            out[a] = verifier.benchmark()
        }
        return out
    }

    private fun fastest(): Accel? =
        listOf(Accel.NPU, Accel.GPU, Accel.CPU).minByOrNull {
            verifier.configure(it, silent = true)
            verifier.benchmark() ?: Double.MAX_VALUE
        }

    /** The footprint moment: your chip, your numbers, one recommendation. */
    private fun showVerdict(times: Map<Accel, Double?>) {
        val fmt = { ms: Double? -> if (ms == null) "n/a" else String.format("%.1f ms", ms) }
        findViewById<TextView>(R.id.benchText).text =
            "NPU ${fmt(times[Accel.NPU])} · GPU ${fmt(times[Accel.GPU])} · CPU ${fmt(times[Accel.CPU])}"
        val chip = SocInfo.name()
        val npu = times[Accel.NPU]
        val gpu = times[Accel.GPU]
        val cpu = times[Accel.CPU]
        val winner = times.filterValues { it != null }.minByOrNull { it.value!! }?.key

        val verdict = findViewById<TextView>(R.id.verdictText)
        val take = findViewById<Button>(R.id.btnRecommend)
        verdict.visibility = android.view.View.VISIBLE
        take.visibility = android.view.View.VISIBLE

        val footprint = footprintLine()
        when {
            winner == Accel.NPU && cpu != null && npu != null -> {
                val x = String.format("%.1f", cpu / npu)
                verdict.text = "Your $chip NPU does this in ${fmt(npu)} — ${x}x faster " +
                    "than CPU, and it sits idle all day. This workload is exactly what it is for.\n$footprint"
                take.text = "Use my NPU"
                take.setOnClickListener { choose(Accel.NPU) }
            }
            winner == Accel.GPU && npu != null && gpu != null -> {
                verdict.text = "Plot twist, measured just now: your GPU (${fmt(gpu)}) beats " +
                    "your NPU (${fmt(npu)}) on this workload. Take the faster lane.\n$footprint"
                take.text = "Use my GPU"
                take.setOnClickListener { choose(Accel.GPU) }
            }
            winner == Accel.CPU && cpu != null -> {
                verdict.text = "No accelerator answered, so your CPU carries it at ${fmt(cpu)}. " +
                    "Still fine — one small frame every 5 seconds.\n$footprint"
                take.text = "Use CPU"
                take.setOnClickListener { choose(Accel.CPU) }
            }
            else -> {
                verdict.text = "Could not measure cleanly. Auto will pick a working engine.\n$footprint"
                take.text = "Stay on Auto"
                take.setOnClickListener { choose(Accel.AUTO) }
            }
        }
    }

    private fun footprintLine(): String {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / (1024 * 1024 * 1024)
        return "${SocInfo.name()} · Android ${android.os.Build.VERSION.RELEASE} · " +
            "${gb}GB RAM · measured on your phone, just now."
    }

    private fun choose(a: Accel) {
        when (a) {
            Accel.AUTO -> findViewById<RadioGroup>(R.id.engineGroup).check(R.id.rbAuto)
            Accel.NPU -> findViewById<RadioGroup>(R.id.engineGroup).check(R.id.rbNpu)
            Accel.GPU -> findViewById<RadioGroup>(R.id.engineGroup).check(R.id.rbGpu)
            Accel.CPU -> findViewById<RadioGroup>(R.id.engineGroup).check(R.id.rbCpu)
        }
        persistEngine()
    }

    private fun benchmark() {
        findViewById<TextView>(R.id.benchText).text = "Feeling your chip…"
        bg.execute {
            val times = timeAll()
            runOnUiThread { showVerdict(times) }
        }
    }

    // ---------- step 3: capture ----------

    private fun showCapture() {
        showOnly(R.id.stepCapture)
        bindPreview()
    }

    private fun bindPreview() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val provider = fut.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(findViewById<PreviewView>(R.id.preview).surfaceProvider)
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, videoCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera busy: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCapture() {
        val vc = videoCapture ?: return
        val file = File(filesDir, "enroll.mp4")
        if (file.exists()) file.delete()
        findViewById<Button>(R.id.btnRecord).isEnabled = false
        secondsLeft = 30
        ticking = true
        recording = vc.output
            .prepareRecording(this, FileOutputOptions.Builder(file).build())
            // Audio stays off: enrollment needs pictures, not sound.
            .start(ContextCompat.getMainExecutor(this)) { event: VideoRecordEvent ->
                if (event is VideoRecordEvent.Finalize) {
                    ticking = false
                    if (!event.hasError()) buildProfile(file.absolutePath)
                    else {
                        Toast.makeText(this, "Recording failed. Try again.", Toast.LENGTH_LONG).show()
                        findViewById<Button>(R.id.btnRecord).isEnabled = true
                    }
                }
            }
        tick()
    }

    private fun tick() {
        if (!ticking) return
        val move = ((30 - secondsLeft) / 5).coerceIn(0, prompts.size - 1)
        findViewById<TextView>(R.id.capturePrompt).text = prompts[move]
        findViewById<TextView>(R.id.captureTimer).text = "0:${"%02d".format(secondsLeft)} left"
        findViewById<ProgressBar>(R.id.captureProgress).progress = 30 - secondsLeft
        if (move != lastMove) {
            lastMove = move
            tickHaptic()
            for (i in moveIds.indices) {
                val row = findViewById<TextView>(moveIds[i])
                when {
                    i < move -> {
                        row.setTextColor(0xFF9DB4E8.toInt())
                        if (!row.text.startsWith("✓")) row.text = "✓ ${row.text}"
                    }
                    i == move -> row.setTextColor(0xFFFFFFFF.toInt())
                    else -> row.setTextColor(0xFF7E93C9.toInt())
                }
            }
        }
        if (secondsLeft <= 0) {
            try { recording?.stop() } catch (_: Exception) {}
            recording = null
            return
        }
        secondsLeft--
        handler.postDelayed({ tick() }, 1000)
    }

    // ---------- step 4: build ----------

    private fun buildProfile(path: String) {
        showOnly(R.id.stepBuilding)
        bg.execute {
            try {
                val r = EnrollHelper.buildProfile(this, verifier, path) { done, total ->
                    runOnUiThread {
                        findViewById<ProgressBar>(R.id.buildingProgress).progress = done
                        findViewById<TextView>(R.id.buildingText).text = "Finding faces… $done of $total"
                    }
                }
                runOnUiThread {
                    showOnly(R.id.stepDone)
                    findViewById<TextView>(R.id.doneText).text =
                        "${r.faces} faces learned · tightness ${"%.2f".format(r.meanSim)} " +
                            "(closer to 1.00 is better). Your video never left this phone."
                    getSharedPreferences("presence_ota", MODE_PRIVATE)
                        .edit().putBoolean("onboarded", true).apply()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showOnly(R.id.stepCapture)
                    findViewById<Button>(R.id.btnRecord).isEnabled = true
                    findViewById<TextView>(R.id.capturePrompt).text = e.message ?: "Try again."
                }
            }
        }
    }

    override fun onDestroy() {
        try { recording?.stop() } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        verifier.close()
        bg.shutdownNow()
        super.onDestroy()
    }
}

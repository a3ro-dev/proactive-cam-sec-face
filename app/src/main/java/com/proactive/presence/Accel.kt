package com.proactive.presence

import android.content.Context
import android.os.Build

/**
 * Device-agnostic compute path. One API (NNAPI) reaches the NPU on both
 * Snapdragon (Hexagon/HTP) and MediaTek (NeuroPilot APU) — no vendor SDKs.
 * GPU-only devices use the GPU delegate, everything else falls back to CPU.
 * The user picks once in onboarding; AUTO benchmarks and keeps the fastest.
 */
enum class Accel(val title: String, val blurb: String) {
    AUTO("Auto (recommended)", "Tests each engine once and keeps the fastest."),
    NPU("NPU", "NNAPI: Hexagon on Snapdragon, APU on MediaTek."),
    GPU("GPU", "For phones without an NPU or with a weak one."),
    CPU("CPU", "Slowest, but works on every Android phone."),
}

object AccelStore {
    private const val PREF = "presence_accel"
    private const val KEY = "accel"

    fun get(ctx: Context): Accel =
        try {
            Accel.valueOf(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, Accel.AUTO.name) ?: Accel.AUTO.name)
        } catch (_: Exception) {
            Accel.AUTO
        }

    fun set(ctx: Context, accel: Accel) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, accel.name).apply()
    }
}

object SocInfo {
    /** e.g. "MediaTek MT6897", "Snapdragon 8 Gen 3", or "Unknown chip". */
    fun name(): String {
        val hw = (Build.HARDWARE ?: "").lowercase()
        val board = (Build.BOARD ?: "").lowercase()
        val soc = (getProp("ro.soc.model") + " " + getProp("ro.hardware")).lowercase()
        val blob = "$hw $board $soc"
        return when {
            "mt6897" in blob || "dimensity 8350" in blob -> "MediaTek Dimensity 8350"
            blob.contains(Regex("mt\\d{4}")) ->
                "MediaTek " + Regex("mt\\d{4}").find(blob)!!.value.uppercase()
            blob.contains(Regex("sm\\d{4}")) ->
                "Snapdragon " + Regex("sm\\d{4}").find(blob)!!.value.uppercase()
            "qcom" in blob || "snapdragon" in blob -> "Snapdragon"
            "mediatek" in blob || blob.contains(Regex("\\bmt\\b")) -> "MediaTek"
            "tensor" in blob -> "Google Tensor"
            "exynos" in blob -> "Exynos"
            else -> "Unknown chip"
        }
    }

    fun hasNpuLikelihood(): Boolean {
        val blob = ((Build.HARDWARE ?: "") + " " + Build.BOARD).lowercase()
        // Every big Android SoC since ~2019 ships an NPU reachable via NNAPI.
        return blob.isNotEmpty()
    }

    fun recommended(): Accel = Accel.NPU

    private fun getProp(key: String): String =
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, key) as? String ?: ""
        } catch (_: Exception) {
            ""
        }
}

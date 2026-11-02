package com.proactive.presence

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Over-the-air updates from GitHub Releases — no Play Store, no laptop.
 *
 * Release convention (see .github/workflows/android.yml):
 *   tag   v<code>          e.g. v3  (code = Android versionCode)
 *   asset app-release.apk  signed with the stable upload key, so updates
 *                          install over the old build and keep owner.json.
 *
 * Flow: MainActivity asks once a day. Newer code found -> dialog shows the
 * release notes -> DownloadManager fetches the APK -> tap installs it via
 * FileProvider. On Android 8+ the user grants "install unknown apps" once;
 * we deep-link them straight to that toggle.
 */
object UpdateChecker {
    private const val REPO = "a3ro-dev/proactive-cam-sec-face"
    private const val PREF = "presence_ota"
    private const val KEY_LAST = "last_check"
    private const val DAY = 24L * 3600 * 1000
    private val io = Executors.newSingleThreadExecutor()

    data class Update(val code: Int, val name: String, val notes: String, val apkUrl: String)

    fun autoCheck(activity: androidx.appcompat.app.AppCompatActivity) {
        val prefs = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0) < DAY) return
        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        check(activity, silent = true)
    }

    fun check(activity: androidx.appcompat.app.AppCompatActivity, silent: Boolean) {
        io.execute {
            val update = latest() ?: run {
                if (!silent) toast(activity, "You are on the latest tide.")
                return@execute
            }
            if (update.code <= versionCode(activity)) {
                if (!silent) toast(activity, "You are on the latest tide.")
                return@execute
            }
            activity.runOnUiThread { offer(activity, update) }
        }
    }

    private fun latest(): Update? {
        return try {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = o.optString("tag_name", "")
            val code = tag.removePrefix("v").substringBefore('-').toIntOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val u = assets.getJSONObject(i).optString("browser_download_url", "")
                if (u.endsWith(".apk")) apk = u
            }
            apk ?: return null
            Update(code, o.optString("name", tag), o.optString("body", ""), apk)
        } catch (_: Exception) {
            null
        }
    }

    private fun offer(activity: androidx.appcompat.app.AppCompatActivity, u: Update) {
        AlertDialog.Builder(activity)
            .setTitle("Update ${u.name} is here")
            .setMessage(
                (u.notes.take(400).ifBlank { "A new tide, same quiet guard." }) +
                    "\n\nIt downloads from GitHub and installs over this app. " +
                    "Your face profile stays put."
            )
            .setPositiveButton("Update now") { _, _ -> download(activity, u) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(activity: androidx.appcompat.app.AppCompatActivity, u: Update) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            toast(activity, "Allow installs, then check for updates again.")
            return
        }
        val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "presence-update.apk")
        if (file.exists()) file.delete()
        val req = DownloadManager.Request(Uri.parse(u.apkUrl))
            .setTitle("Presence Guard update")
            .setDestinationUri(Uri.fromFile(file))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val dm = activity.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(req)
        val done = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { activity.unregisterReceiver(this) } catch (_: Exception) {}
                install(activity, file)
            }
        }
        ContextCompat.registerReceiver(
            activity, done,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun install(activity: androidx.appcompat.app.AppCompatActivity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun versionCode(ctx: Context): Int =
        if (Build.VERSION.SDK_INT >= 28) {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        }

    private fun toast(activity: androidx.appcompat.app.AppCompatActivity, msg: String) {
        activity.runOnUiThread {
            android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

package com.proactive.presence

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var eventText: TextView
    private var guarding = false

    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                "com.proactive.presence.OWNER_SEEN" -> {
                    statusText.text = "You, verified just now"
                    detailText.text = "Guard running. Leave for 15s and it locks."
                }
                "com.proactive.presence.LOCKED" -> {
                    val r = i.getStringExtra("reason") ?: "locked"
                    eventText.text = "Locked: $r at ${java.util.Date()}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        eventText = findViewById(R.id.eventText)

        findViewById<Button>(R.id.adminButton).setOnClickListener { requestAdmin() }
        findViewById<Button>(R.id.enrollButton).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<Button>(R.id.toggleButton).setOnClickListener { toggle() }
        findViewById<Button>(R.id.updateButton).setOnClickListener {
            UpdateChecker.check(this, silent = false)
        }
        findViewById<Button>(R.id.aboutButton).setOnClickListener { about() }

        ContextCompat.registerReceiver(
            this, events,
            IntentFilter().apply {
                addAction("com.proactive.presence.OWNER_SEEN")
                addAction("com.proactive.presence.LOCKED")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (!onboarded()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        refreshStatus()
        UpdateChecker.autoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun onboarded(): Boolean =
        getSharedPreferences("presence_ota", MODE_PRIVATE).getBoolean("onboarded", false)

    private fun refreshStatus() {
        val hasOwner = OwnerStore.exists(this)
        detailText.text = if (hasOwner) {
            val engine = try {
                FaceVerifier(this).also { it.close() }
                AccelStore.get(this).name
            } catch (_: Exception) {
                "auto"
            }
            "Owner profile loaded · engine $engine. Grant camera + lock, then Start."
        } else {
            "No face profile yet. Run setup — 30 seconds, on this phone."
        }
    }

    private fun requestAdmin() {
        val admin = ComponentName(this, GuardAdminReceiver::class.java)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(admin)) {
            Toast.makeText(this, "Lock permission already on", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lets the guard lock the phone when you are away or a stranger picks it up. Nothing else."
                )
            }
        )
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("Presence Guard")
            .setMessage(
                "A quiet on-device guard. Stays awake for you, locks for anyone else.\n\n" +
                    "Made by Akshat Kushwaha — AI systems engineer.\n" +
                    "a3ro.dev · github.com/a3ro-dev\n\n" +
                    "MIT licensed. Source on GitHub."
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun toggle() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), 7
            )
            return
        }
        guarding = !guarding
        if (guarding) {
            ContextCompat.startForegroundService(this, Intent(this, GuardService::class.java))
            findViewById<Button>(R.id.toggleButton).text = "Stop guard"
            statusText.text = "Watching quietly"
        } else {
            stopService(Intent(this, GuardService::class.java))
            findViewById<Button>(R.id.toggleButton).text = "Start guard"
            statusText.text = "Paused"
        }
    }
}

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

    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                "com.proactive.presence.OWNER_SEEN" -> {
                    statusText.text = "You."
                    detailText.text = "Verified just now. Leave for 15s and the tide goes out."
                }
                "com.proactive.presence.LOCKED" -> {
                    val r = i.getStringExtra("reason") ?: "locked"
                    eventText.text = "Locked: $r at ${java.util.Date()}"
                }
            }
        }
    }

    // ColorOS/AOSP DeviceAdminAdd needs a calling package, so it must be
    // started for a result, not with a bare startActivity.
    private val adminResult = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        val on = isAdminOn()
        Toast.makeText(
            this,
            if (on) "Lock permission on" else "Lock permission still off",
            Toast.LENGTH_SHORT
        ).show()
        refreshStatus()
    }

    private fun isAdminOn(): Boolean =
        getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(ComponentName(this, GuardAdminReceiver::class.java))

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
        findViewById<Button>(R.id.toggleButton).text =
            if (GuardService.running) "Stop guard" else "Start guard"
        findViewById<Button>(R.id.adminButton).text =
            if (isAdminOn()) "Lock permission on ✓" else "Enable lock permission"
        statusText.text = if (GuardService.running) "Watching." else "Ready."
        detailText.text = if (hasOwner) {
            val engine = AccelStore.get(this).name
            "Owner profile loaded · engine $engine." +
                if (isAdminOn()) "" else " Enable lock permission, then Start."
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
        adminResult.launch(
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
        if (GuardService.running) {
            stopService(Intent(this, GuardService::class.java))
            GuardService.running = false
            statusText.text = "Paused."
        } else {
            if (!isAdminOn()) {
                // A guard that cannot lock is a camera with extra steps.
                Toast.makeText(this, "Enable lock permission first", Toast.LENGTH_SHORT).show()
                requestAdmin()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, GuardService::class.java))
            GuardService.running = true
            statusText.text = "Watching."
        }
        findViewById<Button>(R.id.toggleButton).text =
            if (GuardService.running) "Stop guard" else "Start guard"
    }
}

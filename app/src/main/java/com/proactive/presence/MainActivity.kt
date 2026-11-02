package com.proactive.presence

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

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
        findViewById<Button>(R.id.enrollButton).setOnClickListener { pickOwnerJson() }
        findViewById<Button>(R.id.toggleButton).setOnClickListener { toggle() }

        ContextCompat.registerReceiver(
            this, events,
            IntentFilter().apply {
                addAction("com.proactive.presence.OWNER_SEEN")
                addAction("com.proactive.presence.LOCKED")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasOwner = File(filesDir, "owner.json").exists()
        detailText.text = if (hasOwner) {
            "Owner profile loaded. Grant camera + lock, then Start."
        } else {
            "No owner profile yet. Run tools/enroll.py on your video, then Import owner.json."
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

    private fun pickOwnerJson() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 42
        )
    }

    @Deprecated("saf")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42 && res == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    File(filesDir, "owner.json").outputStream().use { ins.copyTo(it) }
                }
                Toast.makeText(this, "Owner profile imported", Toast.LENGTH_SHORT).show()
                refreshStatus()
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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

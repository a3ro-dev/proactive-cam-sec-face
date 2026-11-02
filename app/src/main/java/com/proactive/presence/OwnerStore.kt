package com.proactive.presence

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * The face profile (your embedding) never sits in plaintext.
 * EncryptedFile = AES-256-GCM, key in the Android Keystore, hardware-backed
 * on modern phones (StrongBox where present). allowBackup=false in the
 * manifest means it never leaves the device wholesale either.
 */
object OwnerStore {
    private const val NAME = "owner.json"

    private fun file(context: Context): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            File(context.filesDir, NAME),
            context,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    fun exists(context: Context): Boolean =
        File(context.filesDir, NAME).exists()

    fun read(context: Context): String? {
        if (!exists(context)) return null
        return try {
            file(context).openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w("OwnerStore", "profile unreadable", e)
            null
        }
    }

    fun write(context: Context, json: String) {
        if (exists(context)) File(context.filesDir, NAME).delete()
        file(context).openFileOutput().use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }
}

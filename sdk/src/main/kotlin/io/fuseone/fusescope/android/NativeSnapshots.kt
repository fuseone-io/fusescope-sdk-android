package io.fuseone.fusescope.android

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Small durable snapshots keyed by the opaque token stored in ApplicationExitInfo.
 * Explicit commits retain their success result; snapshots must survive an immediate process death.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
internal class NativeSnapshots(
    context: Context,
    process: String,
) : NativeHistoryStore {
    private val namespace = MessageDigest.getInstance("SHA-256").digest(process.toByteArray()).joinToString("") { "%02x".format(it) }
    private val prefs = context.getSharedPreferences("fusescope-native-$namespace", Context.MODE_PRIVATE)

    @Synchronized
    fun save(
        token: String,
        snapshot: NativeExitContext,
    ): Boolean {
        val json =
            JSONObject()
                .put("application", snapshot.applicationId)
                .put("session", snapshot.sessionId)
                .put("sampled", snapshot.sampled)
                .put("release", snapshot.release)
                .put("service", snapshot.service)
                .put("environment", snapshot.environment)
                .put("written", System.currentTimeMillis())
        val editor = prefs.edit().putString(token, json.toString())
        prefs.all.entries
            .filter { it.key.startsWith("fsn1:") && it.key != token }
            .sortedByDescending { entry ->
                runCatching { JSONObject(entry.value as String).optLong("written") }.getOrDefault(0L)
            }.drop(15)
            .forEach { editor.remove(it.key) }
        // Commit before installing the process summary: an immediate crash must retain its session.
        return editor.commit()
    }

    @Synchronized
    override fun context(summary: String): NativeExitContext? {
        if (!Regex("fsn1:[0-9a-f]{32}").matches(summary)) return null
        return runCatching {
            val json = JSONObject(prefs.getString(summary, null) ?: return null)
            NativeExitContext(
                applicationId = json.getString("application"),
                sessionId = json.getString("session"),
                sampled = json.getBoolean("sampled"),
                release = json.optString("release").takeIf { it.isNotEmpty() },
                service = json.optString("service").takeIf { it.isNotEmpty() },
                environment = json.optString("environment").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    @Synchronized
    override fun reported(id: String): Boolean = prefs.getStringSet("reported", emptySet()).orEmpty().contains(id)

    @Synchronized
    override fun markReported(id: String) {
        val ids = prefs.getStringSet("reported", emptySet()).orEmpty() + id
        val retained = ids.sortedByDescending { it.substringBefore(':').toLongOrNull() ?: 0 }.take(128).toSet()
        prefs.edit().putStringSet("reported", retained).commit()
    }
}

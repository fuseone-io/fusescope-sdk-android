package io.fusescope.sample

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import io.fusescope.android.FuseScope
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.concurrent.thread

/**
 * Buttons for each signal. `adb shell am start -n io.fusescope.sample/.MainActivity --es action <name>` runs
 * one without a tap: request, error, crash, anr, details, compose, flush.
 */
class MainActivity : FragmentActivity() {
    private val http by lazy { FuseScope.callFactory(OkHttpClient()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions =
            linkedMapOf<String, () -> Unit>(
                "request" to ::request,
                "error" to { FuseScope.addError(IllegalStateException("Checkout failed for https://shop.example/cart?token=secret")) },
                "crash" to { Checkout().pay() },
                "anr" to { Thread.sleep(ANR_MILLIS) },
                "details" to ::details,
                "compose" to { startActivity(Intent(this, ComposeActivity::class.java)) },
                "flush" to { thread { FuseScope.flush() } },
            )
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((name, run) in actions) {
            column.addView(
                Button(this).apply {
                    text = name
                    setOnClickListener { run() }
                },
            )
        }
        column.addView(FrameLayout(this).apply { id = CONTAINER })
        setContentView(column)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(CONTAINER, HomeFragment()).commit()
        }
        this.actions = actions
        intent.getStringExtra("action")?.let { actions[it]?.invoke() }
    }

    private var actions: Map<String, () -> Unit> = emptyMap()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("action")?.let { actions[it]?.invoke() }
    }

    private fun request() {
        thread {
            runCatching {
                http.newCall(Request.Builder().url(BuildConfig.API_URL + "?q=secret").build()).execute().close()
            }
        }
    }

    private fun details() {
        supportFragmentManager
            .beginTransaction()
            .replace(CONTAINER, DetailsFragment())
            .addToBackStack(null)
            .commit()
    }

    class HomeFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View = TextView(inflater.context).apply { text = "Home" }
    }

    class DetailsFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View = TextView(inflater.context).apply { text = "Details" }
    }

    private companion object {
        const val CONTAINER = 0x7f0b0001
        const val ANR_MILLIS = 12_000L
    }
}

/** Crashes from a class R8 renames, so the uploaded mapping is needed to read the stack trace. */
class Checkout {
    fun pay(): Nothing = throw IllegalStateException("Payment provider unavailable")
}

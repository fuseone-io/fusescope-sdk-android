package io.fusescope.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import io.fusescope.android.compose.FuseScopeView

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FuseScopeView("orders/{id}")
            Text("Order 42")
        }
    }
}

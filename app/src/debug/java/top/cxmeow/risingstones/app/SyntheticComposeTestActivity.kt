package top.cxmeow.risingstones.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Debug-only host in the target process; it never initializes the application's service runtime. */
class SyntheticComposeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyntheticComposeTestContent.content?.invoke() }
    }
}

/** Instrumentation owns and clears the callback; fixtures and services stay in androidTest. */
internal object SyntheticComposeTestContent {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
}

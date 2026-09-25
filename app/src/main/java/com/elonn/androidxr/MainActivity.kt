package com.elonn.androidxr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * First milestone of the native Android XR runtime spike
 * (see decision.native_android_xr_candidate_runtime_20260924 on agents.elonn.com).
 *
 * This is deliberately an ordinary 2D Android/Compose screen, not an XR scene --
 * per the target architecture, Android is the application platform and XR is an
 * additional presentation capability layered on top, not the foundation. This
 * screen is the "Android application layer" box; the Elonn Field / Jetpack XR
 * work comes after this loop is proven against a real World Dataset.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ElonnSpikeScreen()
        }
    }
}

@Composable
private fun ElonnSpikeScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Elonn",
                    style = MaterialTheme.typography.displayMedium
                )
                Text(
                    text = "Native Android XR runtime spike",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

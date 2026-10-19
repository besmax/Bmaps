/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.toColorInt

class MainActivity : ComponentActivity() {
    private var openActiveActivity by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark("#66000000".toColorInt())
        )
        super.onCreate(savedInstanceState)
        openActiveActivity = savedInstanceState?.getBoolean("open-active-activity") ?: intent.opensActiveActivity()

        setContent {
            App(fixtureMap = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                intent.getBooleanExtra("fixture-map", false), openActiveActivity = openActiveActivity,
                onActiveActivityOpened = { openActiveActivity = false })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.opensActiveActivity()) openActiveActivity = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("open-active-activity", openActiveActivity)
        super.onSaveInstanceState(outState)
    }
}

/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

package bes.max.bmaps

import android.app.Application
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ActivityNotificationEntryTest {
    @Test fun notificationOpenSurvivesRecreationBeforeNavigationConsumesIt() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, notificationIntent()).create()
        val saved = Bundle()
        controller.saveInstanceState(saved)
        assertTrue(saved.getBoolean("open-active-activity"))
        controller.destroy()
        val recreated = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create(saved)
        val restored = Bundle()
        recreated.saveInstanceState(restored)
        assertTrue(restored.getBoolean("open-active-activity"))
        recreated.destroy()
    }

    @Test fun existingActivityAcceptsNotificationIntentAndLauncherDoesNotRequestTracking() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        val saved = Bundle()
        controller.saveInstanceState(saved)
        assertFalse(saved.getBoolean("open-active-activity"))
        controller.newIntent(notificationIntent())
        val pending = Bundle()
        controller.saveInstanceState(pending)
        assertTrue(pending.getBoolean("open-active-activity"))
        controller.destroy()
    }

    private fun notificationIntent() = Intent("bes.max.bmaps.activity.OPEN")
}

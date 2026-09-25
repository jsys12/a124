package io.github.jsys12.bastion

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.browser.Screen
import io.github.jsys12.bastion.browser.Sheet
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders key screens to PNG (build/screenshots) when run with -PscreenshotDir=... */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        val dir = System.getProperty("screenshotDir") ?: return
        // Spinners animate forever, so step the clock instead of waiting for idle.
        rule.mainClock.advanceTimeBy(800)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun screens() {
        assumeTrue(System.getProperty("screenshotDir") != null)
        AdBlocker.awaitEngine(120_000)
        val c = rule.activity.controller
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(2000)
        shot("1_home")
        rule.runOnUiThread { c.sheet = Sheet.MENU }
        shot("2_menu")
        rule.runOnUiThread { c.sheet = null; c.openScreen(Screen.SETTINGS) }
        shot("3_settings")
        rule.runOnUiThread { c.openScreen(Screen.FILTERS) }
        shot("4_filters")
        rule.runOnUiThread { c.screens.clear(); c.openScreen(Screen.STATS) }
        shot("5_stats")
        rule.runOnUiThread { c.screens.clear(); c.newTab("https://example.org/"); c.newTab(incognito = false); c.sheet = Sheet.TABS }
        shot("6_tabs")
        rule.runOnUiThread { c.sheet = null; c.settings.theme.value = "dark" }
        shot("7_home_dark")
        rule.runOnUiThread { c.settings.theme.value = "system" }
    }
}

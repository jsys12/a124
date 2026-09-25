package io.github.jsys12.bastion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jsys12.bastion.adblock.AdBlocker
import io.github.jsys12.bastion.browser.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Launches the real activity under Robolectric and walks through the main screens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BrowserSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val c get() = rule.activity.controller

    @Test
    fun homeMenuSettingsAndFilters() {
        rule.onNodeWithText("Поиск или адрес сайта").assertIsDisplayed()
        rule.onNodeWithContentDescription("Меню").performClick()
        rule.onNodeWithText("Настройки").performClick()
        rule.waitForIdle()
        assertEquals(Screen.SETTINGS, c.screens.lastOrNull())
        rule.onNodeWithText("Списки фильтров").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("EasyList").performScrollTo().assertIsDisplayed()
        rule.runOnUiThread { c.onBack() }
        rule.runOnUiThread { c.onBack() }
        rule.waitForIdle()
        assertTrue(c.screens.isEmpty())
        for (screen in Screen.entries) {
            rule.runOnUiThread { c.openScreen(screen) }
            rule.waitForIdle()
            rule.runOnUiThread { c.onBack() }
        }
    }

    @Test
    fun typingAnAddressLoadsIt() {
        rule.onNodeWithText("Поиск или адрес сайта").performClick()
        rule.waitForIdle()
        assertTrue(c.editingAddress)
        rule.onNode(hasSetTextAction()).performTextReplacement("example.com")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
        val tab = c.current!!
        assertFalse(c.editingAddress)
        assertFalse(tab.homeVisible)
        assertEquals("https://example.com", tab.url)
    }

    @Test
    fun tabsAndSheets() {
        rule.runOnUiThread { c.newTab("https://example.org/") }
        rule.runOnUiThread { c.newTab(incognito = true) }
        rule.waitForIdle()
        assertEquals(3, c.tabs.size)
        rule.onNodeWithContentDescription("Защита").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Защита включена", substring = true)
        rule.runOnUiThread { c.onBack() }
        rule.runOnUiThread { c.sheet = io.github.jsys12.bastion.browser.Sheet.TABS }
        rule.waitForIdle()
        rule.onNodeWithText("Закрыть все").assertIsDisplayed()
        rule.runOnUiThread { c.closeAll(incognito = true) }
        rule.waitForIdle()
        assertEquals(2, c.tabs.size)
        rule.runOnUiThread { c.onBack() }
    }

    @Test
    fun overlaysAndDialogsRender() {
        rule.runOnUiThread { c.load(c.current!!, "https://example.com/") }
        rule.waitForIdle()
        rule.runOnUiThread { c.startFind(); c.findQuery("test") }
        rule.waitForIdle()
        rule.onNodeWithText("test").assertIsDisplayed()
        rule.runOnUiThread { c.closeFind() }
        rule.runOnUiThread { c.picker = io.github.jsys12.bastion.browser.PickerState(true, "div.ad-slot", 3, "div 300×250") }
        rule.waitForIdle()
        rule.onNodeWithText("div.ad-slot").assertIsDisplayed()
        rule.runOnUiThread { c.stopPicker() }
        rule.runOnUiThread {
            c.contextMenu = io.github.jsys12.bastion.browser.ContextMenuState(c.current!!, "https://example.com/link", "https://example.com/a.png")
        }
        rule.waitForIdle()
        rule.onNodeWithText("Открыть в новой вкладке").assertIsDisplayed()
        rule.runOnUiThread { c.contextMenu = null }
        rule.runOnUiThread { c.sheet = io.github.jsys12.bastion.browser.Sheet.MENU }
        rule.waitForIdle()
        rule.onNodeWithText("Скрыть элемент").assertIsDisplayed()
        rule.runOnUiThread { c.sheet = io.github.jsys12.bastion.browser.Sheet.SHIELD }
        rule.waitForIdle()
        rule.runOnUiThread { c.sheet = null }
        var answered: Boolean? = null
        rule.runOnUiThread { c.confirm("Заголовок", "Текст", "ОК") { answered = it } }
        rule.waitForIdle()
        rule.onNodeWithText("ОК").performClick()
        rule.waitForIdle()
        assertEquals(true, answered)
        rule.runOnUiThread {
            c.dialog = io.github.jsys12.bastion.browser.DialogState.HttpAuth("example.com", "realm") { c.dialog = null }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Вход на example.com").assertIsDisplayed()
        rule.runOnUiThread { c.dialog = null }
        rule.runOnUiThread { c.settings.bottomBar.value = false }
        rule.waitForIdle()
        rule.runOnUiThread { c.settings.bottomBar.value = true; c.goHome() }
        rule.waitForIdle()
        assertTrue(c.current!!.homeVisible)
        rule.runOnUiThread { assertTrue(c.goBack()) }
        assertFalse(c.current!!.homeVisible)
    }

    @Test
    fun engineCompilesBundledLists() {
        val engine = AdBlocker.awaitEngine(120_000)
        assertTrue(engine != null && engine.networkFilterCount > 1000)
        val config = AdBlocker.contentConfig("https://www.youtube.com/watch?v=1", "www.youtube.com")
        assertTrue(config.contains("\"yt\":true"))
        assertTrue(config.contains("\"on\":true"))
    }
}

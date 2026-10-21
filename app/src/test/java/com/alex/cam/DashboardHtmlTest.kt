package com.alex.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the dashboard's markup.
 *
 * The buttons are wired with `document.getElementById`, so two elements with
 * the same id silently break one of them: the button then has no click
 * handler at all and simply does nothing.
 */
class DashboardHtmlTest {

    private val html: String by lazy {
        val file = File("src/main/res/raw/dashboard.html")
        assertTrue("the dashboard must be there: ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun everyIdIsUnique() {
        val ids = Regex("""\bid="([^"]+)"""").findAll(html)
            .map { it.groupValues[1] }
            .toList()

        assertTrue("the dashboard needs elements", ids.isNotEmpty())

        val duplicates = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertEquals("these ids are used more than once: $duplicates", 0, duplicates.size)
    }

    @Test
    fun everyElementTheScriptLooksUpExists() {
        val looked = Regex("""getElementById\('([^']+)'\)""").findAll(html)
            .map { it.groupValues[1] }
            .toSet()

        val ids = Regex("""\bid="([^"]+)"""").findAll(html)
            .map { it.groupValues[1] }
            .toSet()

        val missing = looked - ids
        assertEquals("the script looks for elements that do not exist: $missing", 0, missing.size)
    }

    @Test
    fun bothRecordingButtonsAreWired() {
        // The audio and the video button each need their own handler and
        // their own route.
        assertTrue("the audio button needs a handler", html.contains("'/record/audio/start'"))
        assertTrue("the video button needs a handler", html.contains("'/record/video/start'"))
        assertTrue("the audio button needs its own element", html.contains("id=\"audioBtn\""))
        assertTrue("the video button needs its own element", html.contains("id=\"videoBtn\""))
    }
}

package io.github.romanfabio.screentime

import io.github.romanfabio.screentime.ui.licenses.reflow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class LicenseReflowTest {
    private fun words(s: String) = s.split(Regex("""\s+""")).filter { it.isNotEmpty() }

    @Test
    fun `joins hard-wrapped lines and keeps paragraphs`() {
        assertEquals("one two three\n\nfour five", reflow("one two\nthree\n\n\n  four\n   five\n"))
    }

    @Test
    fun `never changes the words of a license`() {
        for (name in listOf("license_apache_2_0.txt", "license_app_mit.txt")) {
            val original = File("src/main/res/raw/$name").readText()
            assertEquals(words(original), words(reflow(original)), name)
        }
    }
}

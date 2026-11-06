package io.github.romanfabio.screentime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The license texts shown in the app are plain files in res/raw; keep them honest and in sync with the repo. */
class LicenseFilesTest {
    // Gradle runs unit tests with the module directory (app/) as working directory.
    private val raw = File("src/main/res/raw")

    private fun text(f: File) = f.readText().replace("\r\n", "\n").trim()

    @Test
    fun `in-app MIT text equals the repository LICENSE`() {
        assertEquals(text(File("../LICENSE")), text(File(raw, "license_app_mit.txt")))
    }

    @Test
    fun `apache text is the full standard license`() {
        val apache = text(File(raw, "license_apache_2_0.txt"))
        assertTrue(apache.startsWith("Apache License"))
        assertTrue("Version 2.0, January 2004" in apache)
        assertTrue("END OF TERMS AND CONDITIONS" in apache)
        assertTrue(apache.endsWith("limitations under the License."))
    }

    @Test
    fun `library list names every bundled library family`() {
        val libs = text(File(raw, "third_party_libraries.txt"))
        listOf("AndroidX", "Compose", "Kotlin", "kotlinx.coroutines", "JSpecify").forEach { assertTrue(it in libs, "missing $it") }
    }
}

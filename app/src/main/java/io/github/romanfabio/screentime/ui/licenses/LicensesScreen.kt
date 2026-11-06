package io.github.romanfabio.screentime.ui.licenses

import android.content.Intent
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.romanfabio.screentime.R

private const val SOURCE_URL = "https://github.com/romanfabio/screen-time"

/**
 * Legal texts shown inside the app: this app's own MIT license, the list of bundled libraries and the full
 * Apache License 2.0 that covers them. The texts are plain files in `res/raw` and stay in English (legal text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun raw(@RawRes id: Int): String = context.resources.openRawResource(id).bufferedReader().use { it.readText() }
    val appLicense = remember { reflow(raw(R.raw.license_app_mit)) }
    val libraries = remember { raw(R.raw.third_party_libraries).trim() }
    val apache = remember { reflow(raw(R.raw.license_apache_2_0)) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyMedium)

                Heading(stringResource(R.string.licenses_section_app))
                Text(appLicense, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                }) { Text(stringResource(R.string.licenses_source_code)) }

                HorizontalDivider()
                Heading(stringResource(R.string.licenses_section_libraries))
                Text(libraries, style = MaterialTheme.typography.bodyMedium)

                HorizontalDivider()
                Heading(stringResource(R.string.licenses_section_apache))
                Text(apache, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * The raw files are hard-wrapped at about 78 columns, which looks broken on a phone. Join the lines of each
 * paragraph so the screen reflows them. Only whitespace changes, never a word of the license.
 */
internal fun reflow(text: String): String =
    text.trim()
        .split(PARAGRAPH_BREAK)
        .joinToString(separator = PARAGRAPH_SEPARATOR) { paragraph -> paragraph.trim().replace(WHITESPACE, " ") }

private val PARAGRAPH_BREAK = Regex("""\r?\n[ \t]*\r?\n""")
private val WHITESPACE = Regex("""\s+""")
private const val PARAGRAPH_SEPARATOR = "\n\n"

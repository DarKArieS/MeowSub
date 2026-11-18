package com.nekroz.meowsub.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekroz.meowsub.R
import com.nekroz.meowsub.data.transcribe.TranscribeSettings
import kotlin.math.roundToInt

/**
 * Speech-to-text settings: model file, language and the whisper.cpp parameters worth tuning
 * on a phone. [onPickedModel] keeps access to a picked file and returns its display name.
 */
@Composable
fun TranscribeSettingsDialog(
    initial: TranscribeSettings,
    loadedModel: String?,
    onPickedModel: (Uri) -> String,
    onConfirm: (TranscribeSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s = s.copy(modelUri = uri.toString(), modelName = onPickedModel(uri))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcribe_settings)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    Text(stringResource(R.string.transcribe_model), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.modelName ?: stringResource(R.string.transcribe_no_model),
                            modifier = Modifier.weight(1f),
                            color = if (s.modelName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Text(stringResource(R.string.transcribe_choose_model))
                        }
                    }
                    Hint(
                        if (loadedModel != null) stringResource(R.string.transcribe_model_loaded, loadedModel)
                        else stringResource(R.string.transcribe_model_hint)
                    )
                }

                LanguageMenu(s.language) { s = s.copy(language = it) }

                IntSlider(
                    label = stringResource(R.string.transcribe_threads, s.threads),
                    hint = stringResource(R.string.transcribe_threads_hint),
                    value = s.threads,
                    range = 1..TranscribeSettings.maxThreads(),
                ) { s = s.copy(threads = it) }

                IntSlider(
                    label = if (s.beamSize == 1) stringResource(R.string.transcribe_greedy)
                    else stringResource(R.string.transcribe_beam, s.beamSize),
                    hint = stringResource(R.string.transcribe_beam_hint),
                    value = s.beamSize,
                    range = 1..TranscribeSettings.MAX_BEAM,
                ) { s = s.copy(beamSize = it) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.transcribe_fit_context))
                        Hint(stringResource(R.string.transcribe_fit_context_hint))
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = s.fitContextToClip, onCheckedChange = { s = s.copy(fitContextToClip = it) })
                }

                IntSlider(
                    label = stringResource(R.string.transcribe_padding, s.paddingMs),
                    hint = stringResource(R.string.transcribe_padding_hint),
                    value = s.paddingMs / PADDING_STEP,
                    range = 0..TranscribeSettings.MAX_PADDING_MS / PADDING_STEP,
                ) { s = s.copy(paddingMs = it * PADDING_STEP) }

                Column {
                    OutlinedTextField(
                        value = s.prompt,
                        onValueChange = { s = s.copy(prompt = it) },
                        label = { Text(stringResource(R.string.transcribe_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Hint(stringResource(R.string.transcribe_prompt_hint))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(s) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val PADDING_STEP = 50

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun IntSlider(label: String, hint: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text(label)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
        Hint(hint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageMenu(language: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(languageLabel(language)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.transcribe_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranscribeSettings.LANGUAGES.forEach { code ->
                DropdownMenuItem(
                    text = { Text(stringResource(languageLabel(code))) },
                    onClick = {
                        onChange(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

@StringRes
private fun languageLabel(code: String): Int = when (code) {
    "ja" -> R.string.lang_ja
    "zh" -> R.string.lang_zh
    "en" -> R.string.lang_en
    "ko" -> R.string.lang_ko
    "yue" -> R.string.lang_yue
    "fr" -> R.string.lang_fr
    "de" -> R.string.lang_de
    "es" -> R.string.lang_es
    "ru" -> R.string.lang_ru
    "th" -> R.string.lang_th
    "vi" -> R.string.lang_vi
    else -> R.string.lang_auto
}

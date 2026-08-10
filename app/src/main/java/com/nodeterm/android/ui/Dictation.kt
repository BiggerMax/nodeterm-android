package com.nodeterm.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat

/**
 * Voice dictation — the mobile mirror of the desktop's `⌘⇧D` (hold, speak, REVIEW, then Send —
 * nothing ever auto-submits). Backed by the system [SpeechRecognizer] so there is no extra
 * dependency; a mic button pops a sheet that shows live partial results, lets the user edit the
 * transcript, and only sends when they press Send (which appends a newline, i.e. an Enter).
 */
@Composable
fun DictationButton(
    modifier: Modifier = Modifier,
    onText: (String) -> Unit
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) open = true else denied = true
    }

    IconButton(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) open = true
            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Outlined.Mic,
            contentDescription = "Dictate",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }

    if (denied) {
        Dialog(onDismissRequest = { denied = false }) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp)
            ) {
                Text("Microphone permission needed", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Allow the microphone to dictate into the terminal (like ⌘⇧D on desktop).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { denied = false }) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            denied = false
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    ) { Text("Allow") }
                }
            }
        }
    }

    if (open) {
        DictationSheet(
            onDismiss = { open = false },
            onSend = { text -> onText(text); open = false }
        )
    }
}

@Composable
private fun DictationSheet(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    val context = LocalContext.current
    // Null = recognition unavailable (no Google app / engine on this device) — show a graceful
    // error instead of a spinner forever.
    var engineError by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null
    }
    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }

    fun startListening() {
        val sr = recognizer ?: run {
            engineError = "Speech recognition is unavailable on this device."
            return
        }
        // Re-armed on every start: a recognizer that errored out must not keep the old listener.
        listening = true
        engineError = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
                engineError = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech heard — try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out — try again."
                    else -> "Recognition error ($error)."
                }
            }
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) transcript = text
                else if (transcript.isBlank()) engineError = "No speech heard."
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) transcript = text
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        sr.startListening(intent)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dictate", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (listening) "Listening… speak now" else "Review the text, then Send",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    if (listening) recognizer?.cancel() else startListening()
                }) {
                    Text(if (listening) "Stop" else "Restart", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = transcript,
                onValueChange = { transcript = it },
                placeholder = { Text("Spoken text appears here…", fontSize = 12.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .verticalScroll(rememberScrollState()),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.SansSerif),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
            )

            engineError?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(
                    err,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.size(6.dp))
                Button(
                    onClick = { onSend(transcript) },
                    enabled = transcript.isNotBlank()
                ) { Text("Send") }
            }
        }
    }
}

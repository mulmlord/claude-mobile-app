package dev.mulm.claudemobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.SessionInfo
import kotlinx.coroutines.launch

// Rename and End, shared by the chat's overflow menu and the long-press menu on the session list.

/** The bridge refuses longer names. */
private const val NameMax = 80

@Composable
fun MenuEntry(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Claude.colors
    DropdownMenuItem(
        text = { Text(label, fontSize = 14.5.sp, color = if (enabled) c.text else c.faint) },
        onClick = onClick, enabled = enabled,
    )
}

/** Types `/rename <name>` into the session; the new title arrives with the next sessions update. */
@Composable
fun RenameSessionDialog(session: SessionInfo, onDismiss: () -> Unit) {
    val c = Claude.colors
    val scope = rememberCoroutineScope()
    // Starts selected, so typing replaces the old title and a tap edits it.
    var value by remember { mutableStateOf(TextFieldValue(session.title, TextRange(0, session.title.length))) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = value.text.trim()
    val canSave = !busy && name.isNotEmpty()
    fun save() {
        if (!canSave) return
        busy = true
        error = null
        scope.launch {
            try {
                App.instance.bridge.rename(session.id, name)
                onDismiss()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't rename the session"
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, containerColor = c.surface,
        title = { Text("Rename session", fontFamily = Serif, fontSize = 21.sp, color = c.text) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> value = if (v.text.length <= NameMax) v else v.copy(text = v.text.take(NameMax)) },
                    singleLine = true, label = { Text("Name") },
                    supportingText = { Text("${value.text.length} / $NameMax") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                error?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = c.danger, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = canSave) {
                Text(if (busy) "Renaming…" else "Rename", color = if (canSave) c.accentButton else c.faint, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel", color = c.muted) } },
    )
}

/** Ends the session with /exit (interrupting it first if busy), or force-quits its claude process. */
@Composable
fun EndSessionDialog(session: SessionInfo, onDismiss: () -> Unit) {
    val c = Claude.colors
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<Boolean?>(null) }  // the force flag of the call in flight
    var error by remember { mutableStateOf<String?>(null) }
    val idle = running == null
    fun end(force: Boolean) {
        running = force
        error = null
        scope.launch {
            try {
                App.instance.bridge.endSession(session.id, force)
                onDismiss()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't end the session"
                running = null
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (idle) onDismiss() }, containerColor = c.surface,
        title = { Text("End this session?", fontFamily = Serif, fontSize = 21.sp, color = c.text) },
        text = {
            Column {
                Text("Ends the Claude session on the laptop. The conversation stays in history and can be resumed.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = c.muted)
                Text("Force quit stops it at once, for a session that doesn't respond.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = c.faint, modifier = Modifier.padding(top = 10.dp))
                error?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = c.danger, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        // One row: the secondary Force quit on the left, away from the main action.
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { end(true) }, enabled = idle) {
                    Text(if (running == true) "Quitting…" else "Force quit", color = if (idle) c.muted else c.faint)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = idle) { Text("Cancel", color = if (idle) c.muted else c.faint) }
                TextButton(onClick = { end(false) }, enabled = idle) {
                    Text(if (running == false) "Ending…" else "End", color = if (idle) c.danger else c.faint, fontWeight = FontWeight.Medium)
                }
            }
        },
    )
}

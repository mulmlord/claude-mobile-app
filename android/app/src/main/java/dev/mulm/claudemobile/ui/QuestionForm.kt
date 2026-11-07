package dev.mulm.claudemobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mulm.claudemobile.QAnswer
import dev.mulm.claudemobile.Question

/**
 * An AskUserQuestion as a form: every question with its options (one, or any with multiple
 * choice) and a field for your own answer; Submit sends them all, and the bridge enters them
 * on the laptop's question screen. "Terminal" is the way out if that screen isn't cooperating.
 */
@Composable
internal fun QuestionForm(
    qid: String, qs: List<Question>, onSubmit: (List<QAnswer>, done: () -> Unit) -> Unit, onTerminal: () -> Unit,
) {
    val c = Claude.colors
    val picked = remember(qid) { qs.map { mutableStateListOf<String>() } }
    val typed = remember(qid) { qs.map { mutableStateOf("") } }
    var sending by remember(qid) { mutableStateOf(false) }
    val complete = qs.indices.all { picked[it].isNotEmpty() || typed[it].value.isNotBlank() }
    Surface(
        color = c.surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, c.border),
        shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spark(size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (qs.size > 1) "Claude has ${qs.size} questions" else "Claude has a question",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            Column(
                Modifier.padding(top = 12.dp).heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                qs.forEachIndexed { i, q ->
                    Column {
                        if (qs.size > 1 && q.header.isNotBlank()) {
                            Text(q.header.uppercase(), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = c.faint,
                                letterSpacing = 0.6.sp, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        Text(q.question, fontFamily = Serif, fontSize = 16.5.sp, lineHeight = 23.sp, color = c.text)
                        if (q.multiSelect) Text("Choose any", fontSize = 12.5.sp, color = c.faint, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.size(6.dp))
                        q.options.forEach { o ->
                            val on = o.label in picked[i]
                            OptionRow(o.label, o.description, on, q.multiSelect) {
                                if (q.multiSelect) {
                                    if (on) picked[i].remove(o.label) else picked[i].add(o.label)
                                } else {
                                    picked[i].clear()
                                    picked[i].add(o.label)
                                    typed[i].value = ""
                                }
                            }
                        }
                        OutlinedTextField(
                            value = typed[i].value,
                            onValueChange = { v ->
                                typed[i].value = v
                                if (!q.multiSelect && v.isNotBlank()) picked[i].clear()  // one answer: yours instead
                            },
                            placeholder = { Text("Or type your own answer", color = c.faint) },
                            singleLine = true, shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTerminal) { Text("Terminal", color = c.muted) }
                Spacer(Modifier.weight(1f))
                PrimaryButton(
                    enabled = complete && !sending,
                    onClick = {
                        sending = true
                        onSubmit(qs.indices.map { QAnswer(picked[it].toList(), typed[it].value.trim()) }) { sending = false }
                    },
                ) { Text(if (sending) "Sending…" else "Submit") }
            }
        }
    }
}

/** One option: a radio (or, with multiple choice, a checkbox) and its label and description. */
@Composable
private fun OptionRow(label: String, description: String, on: Boolean, multi: Boolean, onClick: () -> Unit) {
    val c = Claude.colors
    val shape = RoundedCornerShape(12.dp)
    val mark = if (multi) RoundedCornerShape(5.dp) else CircleShape
    val fill by animateColorAsState(if (on) c.accent.copy(alpha = if (c.dark) 0.16f else 0.09f) else Color.Transparent, label = "fill")
    val edge by animateColorAsState(if (on) c.accent.copy(alpha = 0.6f) else c.border, label = "edge")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape).background(fill).border(1.dp, edge, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(19.dp).clip(mark).background(if (on) c.accentButton else Color.Transparent)
                .border(1.5.dp, if (on) c.accentButton else c.borderStrong, mark),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                if (multi) Icon(Icons.Filled.Check, null, Modifier.size(13.dp), tint = c.onAccent)
                else Box(Modifier.size(7.dp).background(c.onAccent, CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, lineHeight = 20.sp, color = c.text)
            if (description.isNotBlank()) Text(description, fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
        }
    }
}

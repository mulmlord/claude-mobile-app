package dev.mulm.claudemobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mulm.claudemobile.ConnEvent
import dev.mulm.claudemobile.ConnLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface LogRow {
    data class Day(val label: String) : LogRow
    data class Line(val e: ConnEvent) : LogRow
}

/** Settings → Connection log: connects, drops and failures, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnLogScreen() {
    val events by ConnLog.events.collectAsState()
    val ctx = LocalContext.current
    val c = Claude.colors
    var copied by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val time = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    val rows = remember(events) {
        val today = LocalDate.now(zone)
        val out = ArrayList<LogRow>()
        var day: LocalDate? = null
        for (e in events.asReversed()) {
            val d = Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate()
            if (d != day) {
                day = d
                out += LogRow.Day(when (d) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> d.format(dayFmt)
                })
            }
            out += LogRow.Line(e)
        }
        out
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                colors = claudeTopBarColors(),
                navigationIcon = { IconButton(onClick = { Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Connection log", fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp) },
                actions = {
                    if (events.isNotEmpty()) {
                        TextButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Connection log", ConnLog.asText()))
                            copied = true
                        }) { Text(if (copied) "Copied" else "Copy", color = c.muted) }
                        TextButton(onClick = { ConnLog.clear() }) { Text("Clear", color = c.muted) }
                    }
                },
            )
        },
    ) { pad ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Nothing logged yet. Connects, drops and failed attempts show up here.",
                    textAlign = TextAlign.Center, color = c.muted, fontSize = 15.sp, lineHeight = 22.sp)
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp,
            ),
        ) {
            items(rows) { row ->
                when (row) {
                    is LogRow.Day -> Text(
                        row.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.faint,
                        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                    )
                    is LogRow.Line -> {
                        val e = row.e
                        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                            val dot = when (e.kind) {
                                ConnEvent.Kind.Good -> c.success
                                ConnEvent.Kind.Bad -> c.danger
                                ConnEvent.Kind.Info -> c.faint
                            }
                            Box(Modifier.padding(top = 6.dp).size(7.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(e.text, fontSize = 14.5.sp, lineHeight = 20.sp, color = c.text)
                                val at = Instant.ofEpochMilli(e.at).atZone(zone).format(time)
                                val until = Instant.ofEpochMilli(e.lastAt).atZone(zone).format(time)
                                Text(
                                    if (e.count > 1) "$at – $until · ×${e.count}" else at,
                                    fontSize = 12.5.sp, color = c.faint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

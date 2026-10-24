package com.pulseloop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Small building blocks shared by the v0.2 screens (adaptive, labels, diary, context, zones).

@Composable
internal fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Sì / No / ? — null is "non so", never collapsed into "no". */
@Composable
internal fun TriAnswerRow(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f))
        FilterChip(selected = value == true, onClick = { onChange(if (value == true) null else true) }, label = { Text("Sì") })
        FilterChip(selected = value == false, onClick = { onChange(if (value == false) null else false) }, label = { Text("No") })
    }
}

/** 1–5 scale; tapping the selected value clears it. */
@Composable
internal fun ScaleRow(label: String, value: Int?, onChange: (Int?) -> Unit, range: IntRange = 1..5) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in range) {
                FilterChip(selected = value == v, onClick = { onChange(if (value == v) null else v) }, label = { Text("$v") })
            }
        }
    }
}

internal fun formatMinuteOfDay(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant

/** Local clock time in the user's 12/24-hour format. */
internal fun resetTime(context: Context, at: Instant): String = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date.from(at))
@Composable internal fun Price(value: Int?, fontSize: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value?.let(FuelCore::priceText) ?: "—", fontSize = fontSize.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Bold, color = Forest)
        Text(" ¢/L", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(bottom = 3.dp))
    }
}
@Composable internal fun SheetTitle(title: String, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = close) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
    }
}
@Composable internal fun OptionRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = if (selected) Pale else Color.White,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, fontSize = 12.sp, color = Muted) }
            RadioButton(selected, onClick = null)
        }
    }
}
@Composable internal fun SettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked,onCheckedChange = change)
    }
}
@Composable internal fun gradeLabel(g: Grade) = stringResource(when (g) { Grade.REGULAR -> R.string.regular; Grade.PREMIUM -> R.string.premium; Grade.DIESEL -> R.string.diesel })
@Composable internal fun sortLabel(s: SortMode) = stringResource(when (s) { SortMode.BEST -> R.string.best_price; SortMode.PRICE -> R.string.lowest_price; SortMode.NEAREST -> R.string.nearest })
@Composable internal fun sortHelp(s: SortMode) = stringResource(when (s) { SortMode.BEST -> R.string.best_help; SortMode.PRICE -> R.string.price_help; SortMode.NEAREST -> R.string.nearest_help })
@Composable internal fun ageLabel(age: Int) = when {
    age == Int.MAX_VALUE -> "No report yet"
    age < 60 -> stringResource(R.string.reported_minutes, age)
    age < 1440 -> stringResource(R.string.reported_hours, age / 60)
    // Older reports read in days and hours, such as 13 d 3 h, not 315 h.
    age % 1440 < 60 -> stringResource(R.string.reported_days, age / 1440)
    else -> stringResource(R.string.reported_days_hours, age / 1440, age % 1440 / 60)
}

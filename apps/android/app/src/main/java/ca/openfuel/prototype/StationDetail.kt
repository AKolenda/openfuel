// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A station's price and report age, with directions, a price report, saving and corrections, in the menu sheet. */
@Composable
internal fun StationDetail(s: Station, grade: Grade, filters: Filters, saved: Boolean, close: () -> Unit, go: () -> Unit, reportPrice: () -> Unit,
                           save: () -> Unit, suggestCorrection: () -> Unit) {
    SheetTitle(s.name, close)
    Text(s.address, color = Muted); Spacer(Modifier.height(18.dp))
    Price(s.price(grade, filters.members), 42)
    Text(ageLabel(s.age(grade)), fontSize = 12.sp, color = Muted)
    if (s.price(grade) != null) Text("Community report · unverified", fontSize = 12.sp, color = Muted)
    Spacer(Modifier.height(18.dp))
    Button(onClick = go, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
        Icon(Icons.Default.Directions, null); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.open_maps))
    }
    Text(stringResource(R.string.sample_handoff), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = reportPrice, Modifier.weight(1f)) { Text(stringResource(R.string.report_price)) }
        OutlinedButton(onClick = save, Modifier.weight(1f)) { Text(stringResource(if (saved) R.string.unsave else R.string.save)) }
    }
    TextButton(onClick = suggestCorrection) { Text(stringResource(R.string.suggest_correction)) }
}

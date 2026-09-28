// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StationRow(s: Station, grade: Grade, filters: Filters, cards: Boolean, wide: Boolean, best: Boolean, logo: Bitmap?, select: () -> Unit, go: () -> Unit) {
    Surface(onClick = select, modifier = Modifier.testTag("station-${s.id}"), shape = RoundedCornerShape(15.dp), color = if (best) Color(0xFFF6FAF0) else Color.White,
        border = BorderStroke(1.dp, if (best) Rule else Color(0xFFF1F3EC))) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Pale, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    if (logo != null) Image(logo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(30.dp).testTag("brand-logo-${s.id}"))
                    else Text(if (s.name == "Petro-Canada") "PC" else s.name.take(1), color = Forest, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(FuelCore.distanceText(s.distanceMetres) + (if (!cards) " · ${s.address}" else ""), fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cards) Text(s.address, fontSize = 11.sp, color = Muted)
                    if (filters.members && s.memberDiscount > 0) Text(stringResource(R.string.member_price), fontSize = 10.sp, color = Muted)
                }
                if (!cards) Column(horizontalAlignment = Alignment.End) {
                    Price(s.price(grade, filters.members), 24)
                    Text(ageLabel(s.age(grade)), fontSize = 9.sp, color = if (s.age(grade) > 60) Color(0xFF927743) else Muted)
                }
                if (!wide && !cards) GoButton(best, go)
            }
            if (cards) Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Price(s.price(grade, filters.members), 36); Text(ageLabel(s.age(grade)), fontSize = 11.sp, color = Muted) }
                if (!wide) GoButton(best, go)
            }
            if (wide) OutlinedButton(onClick = go, Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.Directions, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_maps))
            }
        }
    }
}
/** Opens directions in the chosen maps app: the standard directions sign in a round button, filled for the best price. */
@Composable private fun GoButton(best: Boolean, action: () -> Unit) {
    FilledIconButton(onClick = action, modifier = Modifier.size(48.dp).testTag("directions"),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (best) Forest else Pale, contentColor = if (best) Color.White else Forest)) {
        Icon(Icons.Default.Directions, stringResource(R.string.directions), Modifier.size(24.dp))
    }
}

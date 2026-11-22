// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The stations the map numbers, from the top of the list; the rest are plain dots on the map. */
internal const val NUMBERED_STATIONS = 9
/** The numbered stations drawn in amber, on the map and in the list, when they have a price. */
internal const val HIGHLIGHTED_STATIONS = 3

/**
 * One line of the price board: the brand logo (or initial) with the station's list number on its corner for the
 * first [NUMBERED_STATIONS], matching its map pin, the price, the name with distance and report age, and directions. The first [HIGHLIGHTED_STATIONS] with a price are
 * [highlight]ed. [wide] puts a
 * full-width directions button under the line instead.
 */
@Composable
internal fun StationRow(s: Station, rank: Int, highlight: Boolean, logo: Bitmap?, grade: Grade, filters: Filters, wide: Boolean, select: () -> Unit, go: () -> Unit) {
    val price = s.price(grade, filters.members)
    val age = s.age(grade)
    Surface(onClick = select, modifier = Modifier.testTag("station-${s.id}"), color = Color.White) {
        Column {
            HorizontalDivider(color = Color(0xFFF0F1F3))
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Brand(s, logo, rank, highlight)
                Row(Modifier.widthIn(min = 82.dp), verticalAlignment = Alignment.Bottom) {
                    Text(price?.let(FuelCore::priceText) ?: "—", fontSize = if (price != null) 21.sp else 18.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = if (price != null) Ink else Faint, maxLines = 1)
                    if (price != null) Text(" ¢/L", fontSize = 10.sp, color = Muted, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val reported = if (age == Int.MAX_VALUE) stringResource(R.string.sheet_no_report) else ageLabel(age)
                    Text(buildAnnotatedString {
                        append(FuelCore.distanceText(s.distanceMetres)); append(" · ")
                        // An hour-old report is marked, as on the station details.
                        withStyle(SpanStyle(color = if (age in 61 until Int.MAX_VALUE) Color(0xFF8A6A2F) else Muted)) { append(reported) }
                    }, fontSize = 12.sp, lineHeight = 16.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (filters.members && s.memberDiscount > 0) Text(stringResource(R.string.member_price), fontSize = 11.sp, color = Muted)
                }
                if (!wide) IconButton(onClick = go, modifier = Modifier.testTag("directions")) {
                    Box(Modifier.size(36.dp).background(Pale, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Directions, stringResource(R.string.directions), Modifier.size(20.dp), tint = Ink)
                    }
                }
            }
            if (wide) OutlinedButton(onClick = go, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp).heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.Directions, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_maps))
            }
        }
    }
}

/** The brand logo, or the name's initial, with the list number in a small circle on its corner, amber when [highlight]ed. */
@Composable private fun Brand(s: Station, logo: Bitmap?, rank: Int, highlight: Boolean) {
    Box(Modifier.size(42.dp)) {
        Box(Modifier.align(Alignment.BottomEnd).size(38.dp).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Rule, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            if (logo != null) Image(logo.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(30.dp).testTag("brand-logo-${s.id}"))
            else Text(if (s.name == "Petro-Canada") "PC" else s.name.take(1), color = Ink, fontWeight = FontWeight.Bold)
        }
        if (rank <= NUMBERED_STATIONS) Box(Modifier.size(20.dp).border(1.5.dp, Color.White, CircleShape).padding(1.dp)
            .background(if (highlight) Amber else Ink, CircleShape), contentAlignment = Alignment.Center) {
            Text("$rank", fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = if (highlight) Ink else Color.White)
        }
    }
}

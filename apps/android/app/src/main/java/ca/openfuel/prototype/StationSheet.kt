// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.roundToInt

/** The station sheet's handle: a drag up expands the sheet, a drag down hides it, and a tap toggles it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StationSheetHandle(stationSheet: SheetState, scope: CoroutineScope) {
    Box(Modifier.fillMaxWidth().height(40.dp).testTag("station-sheet-handle")
        .semantics { contentDescription = "Drag to resize station list"; stateDescription = stationSheet.currentValue.name }
        .pointerInput(stationSheet) {
            var travel = 0f
            detectVerticalDragGestures(onDragStart = { travel = 0f }, onVerticalDrag = { change, amount -> change.consume(); travel += amount },
                onDragEnd = { scope.launch {
                    if (travel > 48.dp.toPx()) stationSheet.hide()
                    else if (travel < -32.dp.toPx()) stationSheet.expand()
                } })
        }.clickable { scope.launch { if (stationSheet.currentValue == SheetValue.Expanded) stationSheet.partialExpand() else stationSheet.expand() } },
        contentAlignment = Alignment.Center) {
        Box(Modifier.width(36.dp).height(4.dp).background(Rule, CircleShape))
    }
}

/** The station list with its title, count, sort and sync status. [refreshing] shows the pull spinner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StationSheetContent(height: Dp, grade: Grade, visible: List<Station>, sort: SortMode, cards: Boolean, onCardsChange: (Boolean) -> Unit,
                                 openSort: () -> Unit, syncState: String, limitResetsAt: Instant, hasStations: Boolean, donate: (() -> Unit)?,
                                 syncing: Boolean, refreshing: Boolean, onRefresh: () -> Unit, resetFilters: () -> Unit,
                                 filters: Filters, fullWidth: Boolean, bestId: String?, brandLogos: Map<String, Bitmap>,
                                 select: (Station) -> Unit, go: (Station) -> Unit, suggestStation: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${gradeLabel(grade)} nearby", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(11.dp)).background(Color(DesignTokens.SOFT)).padding(3.dp)) {
                listOf(true, false).forEach { mode ->
                    TextButton(onClick = { onCardsChange(mode) }, modifier = Modifier.height(32.dp).semantics { this.selected = cards == mode }.testTag(if (mode) "layout-cards" else "layout-list"),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (cards == mode) Color.White else Color.Transparent)) {
                        Icon(if (mode) Icons.Default.ViewAgenda else Icons.Default.ViewList, null, Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp)); Text(stringResource(if (mode) R.string.cards else R.string.list),fontSize=11.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.station_count, visible.size), fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = openSort, modifier = Modifier.testTag("open-sort")) { Text(sortLabel(sort), fontSize = 11.sp); Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp)) }
        }
        if (syncState == "limited") LimitNotice(limitResetsAt, saved = hasStations, donate = donate)
        else if (syncState == "offline" || syncState == "cached") Text(
            if (syncState == "offline") "Offline · showing saved stations" else "Showing saved stations",
            Modifier.padding(horizontal = 18.dp, vertical = 4.dp).testTag("sync-status"), fontSize = 11.sp, color = Muted)
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh,
            state = pullState, indicator = {
                PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = Color.White, color = Forest)
            },
            modifier = Modifier.weight(1f).semantics { stateDescription = if (syncing) "Refreshing" else "Idle" }.testTag("pull-refresh")) {
            LazyColumn(Modifier.fillMaxSize().testTag("station-list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 52.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (visible.isEmpty()) item {
                    Text(stringResource(R.string.no_matches), Modifier.padding(20.dp), color = Muted)
                    TextButton(onClick = resetFilters) { Text(stringResource(R.string.reset_filters)) }
                }
                items(visible, key = { it.id }) { station ->
                    StationRow(station, grade, filters, cards, fullWidth,
                        station.id == bestId,
                        logo = brandLogos[station.brandLogoUrl],
                        select = { select(station) }, go = { go(station) })
                }
                item { TextButton(onClick = suggestStation, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.suggest_station))
                } }
            }
        }
    }
}

/** The database's free daily limit: live prices pause and saved ones stay on screen until it resets. */
@Composable private fun LimitNotice(resetsAt: Instant, saved: Boolean, donate: (() -> Unit)?) {
    val context = LocalContext.current
    Surface(color = Pale, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("limit-notice")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (saved) R.string.limit_notice_saved else R.string.limit_notice, resetTime(context, resetsAt)),
                fontSize = 12.sp, lineHeight = 16.sp, color = Ink, modifier = Modifier.weight(1f))
            if (donate != null) TextButton(onClick = donate, modifier = Modifier.testTag("limit-donate")) { Text(stringResource(R.string.donate)) }
        }
    }
}

/**
 * The map credit and the location button, which sit on the map just above the station sheet.
 *
 * Required attribution is a quiet edge label on the map itself: it sits just above the station sheet
 * and follows it as it moves, above the navigation bar when the sheet is hidden, and is not shown when
 * the sheet covers the map. Without the theme's 0.5 sp letter spacing the OpenFreeMap credit (about
 * 390 dp) stays on one line on a 412 dp screen. On narrower screens or larger text it wraps: the compact
 * line height keeps two lines below the map buttons' 28 dp inset, and the buttons, drawn after it,
 * keep their taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxWithConstraintsScope.SheetOverlays(stationSheet: SheetState, sheetInset: Dp, baseMap: String?, locating: Boolean, requestLocation: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val mapBottom = with(density) { maxHeight.toPx() } - WindowInsets.navigationBars.getBottom(density)
    var creditHeight by remember { mutableIntStateOf(0) }
    if (stationSheet.currentValue != SheetValue.Expanded || stationSheet.targetValue != SheetValue.Expanded) Text(
        stringResource(if (baseMap == "openfreemap") R.string.map_credit else R.string.map_credit_osm),
        fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.sp, color = Ink,
        modifier = Modifier.align(Alignment.TopStart).offset {
            val sheetTop = runCatching { stationSheet.requireOffset() }.getOrDefault(Float.MAX_VALUE)
            IntOffset(0, (minOf(sheetTop, mapBottom) - creditHeight).roundToInt())
        }.onSizeChanged { creditHeight = it.height }.background(Color.White.copy(alpha = .85f))
            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }.padding(horizontal = 2.dp).testTag("map-credit"))
    if (stationSheet.currentValue != SheetValue.Expanded && stationSheet.targetValue != SheetValue.Expanded) {
        FilledTonalIconButton(onClick = requestLocation, modifier = Modifier.align(Alignment.BottomEnd)
            .navigationBarsPadding().padding(end = 14.dp, bottom = sheetInset + 28.dp).size(48.dp).testTag("use-location")) {
            if (locating) CircularProgressIndicator(Modifier.size(20.dp)) else Icon(Icons.Default.MyLocation, "Use my location")
        }
    }
}

/** Brings back the station sheet after it was dragged away. */
@Composable
internal fun BoxScope.ShowStationsButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp)
        .height(48.dp).testTag("show-stations")) {
        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp)); Text("Show station list")
    }
}

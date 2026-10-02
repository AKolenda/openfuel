// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the station sheet rests and where it is while it moves. Positions are pixels from the top of native-root.
 * [top] changes on every frame of a drag: read it only while placing or drawing, never in composition.
 */
@Stable internal class StationSheetState(initial: Detent) {
    val drag = AnchoredDraggableState(initial)
    /** The sheet's top at FULL. */
    var fullTop by mutableFloatStateOf(Float.NaN)
    /** The nominal HALF top, or COLLAPSED on screens too short for HALF. */
    var restTop by mutableFloatStateOf(Float.NaN)

    /** The sheet's top; below the screen until its stops are known. */
    fun top(): Float = drag.offset.takeUnless { it.isNaN() } ?: restTop.takeUnless { it.isNaN() } ?: Float.POSITIVE_INFINITY
    /** The sheet has reached FULL and covers the map. */
    fun coversMap(): Boolean = top() <= fullTop + .5f
    /** 1 at [restTop] or lower and 0 at FULL, so what sits on the map fades as the sheet rises instead of popping. */
    fun mapAlpha(): Float = if (restTop > fullTop) ((top() - fullTop) / (restTop - fullTop)).coerceIn(0f, 1f) else 1f

    companion object {
        /** Keeps the stop across rotation. */
        val Saver = Saver<StationSheetState, Detent>(save = { it.drag.settledValue }, restore = { StationSheetState(it) })
    }
}

@Composable internal fun rememberStationSheetState() = rememberSaveable(saver = StationSheetState.Saver) { StationSheetState(Detent.HALF) }

/** HALF, unless the screen is too short for it. */
private fun AnchoredDraggableState<Detent>.half(): Detent? = Detent.HALF.takeIf { anchors.hasPositionFor(it) }

/** Where a tap on the header sends the sheet: HALF and FULL swap, and COLLAPSED opens to HALF. */
private fun AnchoredDraggableState<Detent>.tapTarget(): Detent = when (targetValue) {
    Detent.FULL -> half() ?: Detent.COLLAPSED
    Detent.HALF -> Detent.FULL
    Detent.COLLAPSED -> half() ?: Detent.FULL
}

/**
 * The station sheet. It rests at FULL just under the top controls, at HALF with its header and two rows, or at
 * COLLAPSED with only its header, and never hides. A drag on the header moves it with the finger and a fling moves
 * it one stop; on the list, a drag raises the sheet to FULL before the list scrolls and lowers it once the list is
 * at its top. The sheet keeps one height and only moves, so a drag never measures the list again or resizes the map.
 * [sheetContent] gets the handle's accessibility state and actions, the header's tap handling and the list's scroll
 * state. [body], the map and its controls, fills the screen under the sheet.
 */
@Composable
internal fun BoxWithConstraintsScope.StationSheet(sheet: StationSheetState, chrome: MapChrome, area: SearchPoint,
                                                  sheetContent: @Composable (handle: Modifier, header: Modifier, list: LazyListState) -> Unit,
                                                  body: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val drag = sheet.drag
    var headerHeight by remember { mutableIntStateOf(with(density) { 112.dp.roundToPx() }) }
    val navigationBar = WindowInsets.navigationBars.getBottom(density)
    val height = constraints.maxHeight.toFloat()
    val collapsed = height - headerHeight - navigationBar
    val full = with(density) { minOf(chrome.topControlsBottomPx + 8.dp.toPx(), collapsed) }
    val half = with(density) { height - minOf(headerHeight + 144.dp.toPx() + navigationBar, height / 2) }
    // A screen too short for HALF, such as a phone in landscape, keeps FULL and COLLAPSED.
    val hasHalf = with(density) { half - full >= 96.dp.toPx() } && half < collapsed
    val anchors = remember(full, half, collapsed, hasHalf) {
        DraggableAnchors { Detent.FULL at full; if (hasHalf) Detent.HALF at half; Detent.COLLAPSED at collapsed }
    }
    val settled = drag.settledValue
    SideEffect {
        sheet.fullTop = full; sheet.restTop = if (hasHalf) half else collapsed
        chrome.halfTopPx = sheet.restTop.roundToInt(); chrome.detent = settled
        // A stop that no longer fits hands the sheet to the nearest stop left.
        drag.updateAnchors(anchors, drag.targetValue.takeIf { anchors.hasPositionFor(it) } ?: anchors.closestAnchor(half)!!)
    }
    // Published without a read in composition, so reaching FULL during a held drag recomposes nothing here.
    LaunchedEffect(sheet, chrome) { snapshotFlow { sheet.coversMap() }.collect { chrome.mapCovered = it } }
    val threshold = remember(density) { { distance: Float -> minOf(distance * .5f, with(density) { 64.dp.toPx() }) } }
    val animation = remember { spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow) }
    val fling = AnchoredDraggableDefaults.flingBehavior(drag, positionalThreshold = threshold, animationSpec = animation)
    val handover = remember(drag, fling) { SheetHandover(drag, fling) }
    fun moveTo(stop: Detent) { scope.launch { drag.animateTo(stop, animation) } }
    BackHandler(enabled = settled == Detent.FULL) { moveTo(drag.half() ?: Detent.COLLAPSED) }
    val list = rememberLazyListState()
    // HALF and COLLAPSED always show the best stations first, as does a new area.
    LaunchedEffect(settled) { if (settled != Detent.FULL) list.scrollToItem(0) }
    val cell = area.forStorage()
    LaunchedEffect(cell.latitude, cell.longitude) { list.scrollToItem(0) }

    val listLabel = stringResource(R.string.sheet_station_list)
    val stateLabel = stringResource(when (settled) { Detent.FULL -> R.string.sheet_expanded; Detent.HALF -> R.string.sheet_half_open; Detent.COLLAPSED -> R.string.sheet_collapsed })
    val clickLabel = stringResource(if (settled == Detent.FULL) R.string.sheet_collapse else R.string.sheet_expand)
    val actions = listOf(Detent.FULL to R.string.sheet_move_full, Detent.HALF to R.string.sheet_move_half, Detent.COLLAPSED to R.string.sheet_move_collapsed)
        .filter { (stop, _) -> stop != settled && anchors.hasPositionFor(stop) }
        .map { (stop, label) -> CustomAccessibilityAction(stringResource(label)) { moveTo(stop); true } }
    val handle = Modifier.semantics {
        contentDescription = listLabel; stateDescription = stateLabel
        onClick(clickLabel) { moveTo(drag.tapTarget()); true }
        customActions = actions
    }
    // A tap anywhere on the header outside its buttons toggles the sheet. It adds no accessibility node of its own:
    // the handle carries that action, and the title and count stay readable.
    val header = Modifier.onSizeChanged { headerHeight = it.height }.pointerInput(drag) {
        awaitEachGesture {
            // Child controls consume their DOWN first. A header tap must stay within touch slop in both axes;
            // a horizontal swipe has no vertical drag consumer and must not become an accidental toggle.
            val down = awaitFirstDown()
            var tap = true
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    event.changes.any { it.id != down.id && it.pressed }) tap = false
                if (!change.pressed) {
                    if (tap) moveTo(drag.tapTarget())
                    break
                }
                if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) tap = false
            }
        }
    }

    // A stationary observer keeps physical MOVE events flowing through Compose's hit path. Without it,
    // consecutive equal positions in the moving sheet's local coordinates are treated as duplicates, losing
    // alternate deltas for both header drags and the list's hand-over. This observer consumes nothing.
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent() }
    }) {
        Box(Modifier.fillMaxSize().background(Color(MAP_BACKGROUND))) { body() }
        Surface(Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxWidth()
            .height(with(density) { (height - full).toDp() })
            .offset { IntOffset(0, sheet.top().roundToInt()) }
            .nestedScroll(handover)
            .anchoredDraggable(drag, Orientation.Vertical, flingBehavior = fling)
            .testTag("station-sheet"),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), color = Color.White, shadowElevation = 6.dp) {
            sheetContent(handle, header, list)
        }
        // Gesture navigation is transparent on Android 15. Keep the reserved inset opaque so a COLLAPSED
        // sheet shows only its header, rather than the first row painting through below it.
        Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars).background(Color.White))
    }
}

/**
 * Hands the list's drags to the sheet: an upward drag raises the sheet to FULL before the list scrolls, and a
 * downward drag the list cannot use, at its top, lowers the sheet. The list's own flings never move the sheet.
 */
private class SheetHandover(private val drag: AnchoredDraggableState<Detent>, private val fling: TargetedFlingBehavior) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0 && source == NestedScrollSource.UserInput) Offset(0f, drag.dispatchRawDelta(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, drag.dispatchRawDelta(available.y)) else Offset.Zero

    // An upward release below FULL finishes on the sheet, so the list does not fling as well.
    override suspend fun onPreFling(available: Velocity): Velocity =
        if (available.y < 0 && drag.offset > drag.anchors.minPosition() + .5f) { settle(available.y); available } else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val offset = drag.offset
        val stop = drag.anchors.closestAnchor(offset) ?: return Velocity.Zero
        // A drag that left the sheet between stops finishes there. A list fling that ends at the top leaves the sheet
        // on its stop, and a drag that carried it onto another stop only records that stop.
        if (abs(offset - drag.anchors.positionOf(stop)) > .5f) { settle(available.y); return available }
        if (stop != drag.settledValue) settle(0f)
        return Velocity.Zero
    }

    private suspend fun settle(velocity: Float) = drag.anchoredDrag { anchors ->
        val scrollScope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float {
                val before = drag.offset
                dragTo((before + pixels).coerceIn(anchors.minPosition(), anchors.maxPosition()))
                return drag.offset - before
            }
        }
        with(fling) { scrollScope.performFling(velocity) }
    }
}

/**
 * The sheet's content: the header (handle, title with Cards/List, count with refresh and sort), the sync status
 * and the station list. [handle] and [header] come from [StationSheet]; [list] is the list's scroll state.
 */
@Composable
internal fun StationSheetContent(handle: Modifier, header: Modifier, list: LazyListState, grade: Grade, savedOnly: Boolean, visible: List<Station>,
                                 sort: SortMode, changeSort: (SortMode) -> Unit, cards: Boolean, onCardsChange: (Boolean) -> Unit,
                                 syncState: String, limitResetsAt: Instant, hasStations: Boolean, donate: (() -> Unit)?,
                                 syncing: Boolean, onRefresh: () -> Unit, resetFilters: () -> Unit, showAll: () -> Unit,
                                 filters: Filters, fullWidth: Boolean, bestId: String?, brandLogos: Map<String, Bitmap>,
                                 select: (Station) -> Unit, go: (Station) -> Unit, suggestStation: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(header.fillMaxWidth().testTag("station-sheet-header")) {
            Box(handle.fillMaxWidth().height(24.dp).testTag("station-sheet-handle"), contentAlignment = Alignment.Center) {
                Box(Modifier.width(32.dp).height(4.dp).background(Rule, CircleShape))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (savedOnly) stringResource(R.string.saved) else stringResource(R.string.sheet_title, gradeLabel(grade)),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() })
                Spacer(Modifier.width(8.dp))
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
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.sheet_station_count, visible.size, visible.size), fontSize = 11.sp, color = Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                RefreshButton(syncing, onRefresh)
                SortMenu(sort, changeSort)
            }
            // Status belongs to the measured header so it cannot take space reserved for the first rows at HALF.
            if (syncState == "limited") LimitNotice(limitResetsAt, saved = hasStations, donate = donate)
            else if (syncState == "offline" || syncState == "cached") Text(
                stringResource(if (syncState == "offline") R.string.sheet_offline else R.string.sheet_cached),
                Modifier.padding(horizontal = 18.dp, vertical = 4.dp).testTag("sync-status"), fontSize = 11.sp, lineHeight = 15.sp, color = Muted)
        }
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("station-list"), state = list,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = navigationBar + 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (visible.isEmpty()) item {
                if (savedOnly) {
                    Text(stringResource(R.string.sheet_no_saved), Modifier.padding(20.dp), color = Muted)
                    TextButton(onClick = showAll, modifier = Modifier.testTag("show-all")) { Text(stringResource(R.string.sheet_show_all)) }
                } else {
                    Text(stringResource(R.string.no_matches), Modifier.padding(20.dp), color = Muted)
                    TextButton(onClick = resetFilters) { Text(stringResource(R.string.reset_filters)) }
                }
            }
            items(visible, key = { it.id }, contentType = { "station" }) { station ->
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

/** Loads the area's stations again; a spinner replaces the icon while any load runs. */
@Composable private fun RefreshButton(syncing: Boolean, onRefresh: () -> Unit) {
    val label = stringResource(R.string.sheet_refresh)
    val refreshing = stringResource(R.string.sheet_refreshing)
    IconButton(onClick = onRefresh, enabled = !syncing, modifier = Modifier.size(40.dp).testTag("refresh-stations")
        .semantics { contentDescription = label; if (syncing) stateDescription = refreshing }) {
        if (syncing) CircularProgressIndicator(Modifier.size(18.dp), color = Forest, strokeWidth = 2.dp)
        else Icon(Icons.Default.Refresh, null, Modifier.size(20.dp), tint = Forest)
    }
}

/** "Best price ▾" and the list of sort orders it opens under itself. */
@Composable internal fun SortMenu(sort: SortMode, changeSort: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.sheet_sort, sortLabel(sort))
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("open-sort").semantics { contentDescription = label }) {
            Text(sortLabel(sort), fontSize = 11.sp)
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
        // The menu is its own window, so its tags need their own resource-id flag for uiautomator.
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.width(280.dp).semantics { testTagsAsResourceId = true },
            shape = RoundedCornerShape(16.dp), containerColor = Color.White, shadowElevation = 8.dp) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Column(Modifier.padding(vertical = 6.dp)) {
                        Text(sortLabel(mode), fontWeight = FontWeight.SemiBold)
                        Text(if (mode == SortMode.NEAREST) stringResource(R.string.sheet_nearest_help) else sortHelp(mode),
                            fontSize = 12.sp, lineHeight = 16.sp, color = Muted)
                    } },
                    onClick = { changeSort(mode); open = false },
                    trailingIcon = { if (sort == mode) Icon(Icons.Default.Check, null, tint = Forest) },
                    modifier = Modifier.semantics { selected = sort == mode }.testTag("sort-${mode.name.lowercase()}"))
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
 * Covers the map above the sheet's FULL top, down under its rounded corners, with the map's background colour as
 * the sheet rises, so the map can stop drawing at FULL without a visible pop. It sits under the top controls.
 */
@Composable
internal fun SheetScrim(sheet: StationSheetState) {
    val fullTop = sheet.fullTop
    if (!fullTop.isNaN()) Spacer(Modifier.fillMaxWidth().height(with(LocalDensity.current) { fullTop.toDp() } + 26.dp)
        .graphicsLayer { alpha = 1f - sheet.mapAlpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }
        .background(Color(MAP_BACKGROUND)))
}

/**
 * The map credit, the location button and the snackbar, which travel with the station sheet. The credit and the
 * button fade out as the sheet rises and leave once it covers the map, so they never take taps meant for the top
 * controls; the snackbar then sits above the navigation bar.
 *
 * Required attribution is a quiet edge label on the map itself, just above the station sheet. Without the theme's
 * 0.5 sp letter spacing the OpenFreeMap credit (about 390 dp) stays on one line on a 412 dp screen. On narrower
 * screens or larger text it wraps: the compact line height keeps two lines below the location button's 28 dp
 * inset, and the button, drawn after it, keeps its taps.
 */
@Composable
internal fun BoxWithConstraintsScope.SheetOverlays(sheet: StationSheetState, chrome: MapChrome, snackbar: SnackbarHostState, baseMap: String?,
                                                   locating: Boolean, requestLocation: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val mapBottom = constraints.maxHeight - WindowInsets.navigationBars.getBottom(density).toFloat()
    fun sheetTop() = minOf(sheet.top(), mapBottom)
    if (!chrome.mapCovered) {
        var creditHeight by remember { mutableIntStateOf(0) }
        Text(
            stringResource(if (baseMap == "openfreemap") R.string.map_credit else R.string.map_credit_osm),
            fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.sp, color = Ink,
            modifier = Modifier.align(Alignment.TopStart).offset { IntOffset(0, (sheetTop() - creditHeight).roundToInt()) }
                .graphicsLayer { alpha = sheet.mapAlpha() }.onSizeChanged { creditHeight = it.height }.background(Color.White.copy(alpha = .85f))
                .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }.padding(horizontal = 2.dp).testTag("map-credit"))
        val label = stringResource(R.string.sheet_use_location)
        // Its bottom sits 28 dp above the sheet, leaving room for a two-line credit.
        Surface(onClick = requestLocation, shape = CircleShape, color = Color.White, shadowElevation = 3.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (sheetTop() - 76.dp.toPx()).roundToInt()) }
                .graphicsLayer { alpha = sheet.mapAlpha() }.padding(end = 16.dp).size(48.dp).semantics { contentDescription = label }.testTag("use-location")) {
            Box(contentAlignment = Alignment.Center) {
                if (locating) CircularProgressIndicator(Modifier.size(20.dp), color = Forest, strokeWidth = 2.dp)
                else Icon(Icons.Default.MyLocation, null, tint = Forest)
            }
        }
    }
    var snackbarHeight by remember { mutableIntStateOf(0) }
    // The snackbar keeps its own 12 dp margin, so 4 dp more puts it 8 dp above the sheet.
    SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).offset {
        IntOffset(0, ((if (sheet.coversMap()) mapBottom else sheetTop()) + 4.dp.toPx() - snackbarHeight).roundToInt())
    }.onSizeChanged { snackbarHeight = it.height })
}

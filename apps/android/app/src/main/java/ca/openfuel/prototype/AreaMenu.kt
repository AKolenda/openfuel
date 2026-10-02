// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** The name the area chip and its menu give [area]. */
@Composable
internal fun areaName(area: SearchPoint): String = when (area.source) {
    SearchSource.MAP -> stringResource(R.string.area_map)
    SearchSource.OVERVIEW -> stringResource(R.string.area_none)
    SearchSource.DEVICE -> stringResource(if (area.label == "Last location area") R.string.area_last_location else R.string.area_your_location)
    else -> area.shortLabel()
}

private fun areaIcon(area: SearchPoint): ImageVector = when (area.source) {
    SearchSource.DEVICE -> Icons.Default.MyLocation
    SearchSource.MAP -> Icons.Default.Map
    else -> Icons.Default.LocationOn
}

/**
 * The area chip and the list it opens under itself: the device location, the area on the map, the recent cities
 * (or a few preset ones before any was chosen) and the city search. When the list closes, focus goes back to the
 * chip, so the map's web view does not take it.
 */
@Composable
internal fun AreaMenu(area: SearchPoint, recent: List<SearchPoint>, movedAway: Boolean, locating: Boolean, chipFocus: FocusRequester,
                      useLocation: () -> Unit, searchMapArea: () -> Unit, choose: (SearchPoint) -> Unit, searchCity: () -> Unit,
                      modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    fun close(then: () -> Unit = {}) {
        expanded = false
        // A clickable takes focus only outside touch mode, so this moves it only for keyboard and D-pad users.
        runCatching { chipFocus.requestFocus() }
        then()
    }
    BackHandler(enabled = expanded) { close() }
    Box(modifier) {
        AreaChip(area, expanded, Modifier.focusRequester(chipFocus)) { expanded = !expanded }
        DropdownMenu(expanded = expanded, onDismissRequest = { close() }, offset = DpOffset(0.dp, 6.dp),
            shape = RoundedCornerShape(16.dp), containerColor = Color.White, shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 240.dp, max = 320.dp).semantics { testTagsAsResourceId = true }.testTag("area-menu")) {
            AreaRow(stringResource(R.string.area_your_location), Icons.Default.MyLocation, "area-location",
                selected = area.source == SearchSource.DEVICE, busy = locating) { close(useLocation) }
            AreaRow(stringResource(R.string.area_search_map), Icons.Default.Map, "area-map",
                selected = area.source == SearchSource.MAP && !movedAway, enabled = movedAway) { close(searchMapArea) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Rule)
            CityRows(recent, area) { city -> close { choose(city) } }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Rule)
            // The city search takes the chip's place and its focus.
            AreaRow(stringResource(R.string.area_choose_city), Icons.Default.Search, "area-choose-city") { expanded = false; searchCity() }
        }
    }
}

/** The area chip: the area's icon, its name and a chevron. With less than 72 dp left for a name that does not fit, only the icons show. */
@Composable
private fun AreaChip(area: SearchPoint, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val name = areaName(area)
    val description = stringResource(R.string.area_chip, name)
    val state = stringResource(if (expanded) R.string.area_expanded else R.string.area_collapsed)
    BoxWithConstraints(modifier) {
        // Padding, icons and gaps take 62 dp beside the name.
        val room = maxWidth - 62.dp
        val compact = room < 72.dp
        Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.White, contentColor = Ink, shadowElevation = 3.dp,
            modifier = Modifier.onFocusChanged { hasFocus = it.isFocused }.clearAndSetSemantics {
                focused = hasFocus
                role = Role.DropdownList
                contentDescription = description
                stateDescription = state
                testTag = "search-area"
                onClick { onClick(); true }
            }) {
            Row(Modifier.heightIn(min = 40.dp).padding(start = if (compact) 6.dp else 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(areaIcon(area), null, Modifier.size(18.dp), tint = Forest)
                if (!compact) {
                    Spacer(Modifier.width(6.dp))
                    Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(2.dp))
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
            }
        }
    }
}

/** The recent cities under a "Recent" heading, or before any was chosen the preset cities under "Cities". */
@Composable
private fun CityRows(recent: List<SearchPoint>, area: SearchPoint, choose: (SearchPoint) -> Unit) {
    MenuHeading(stringResource(if (recent.isEmpty()) R.string.area_cities else R.string.area_recent))
    recent.ifEmpty { PRESET_CITIES }.forEach { city ->
        AreaRow(city.shortLabel(), if (recent.isEmpty()) Icons.Default.LocationOn else Icons.Default.History, "city-${city.shortLabel()}",
            selected = area.source == SearchSource.CITY && city.sameCell(area)) { choose(city) }
    }
}

@Composable
private fun MenuHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, color = Muted, modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { heading() })
}

/** A 48 dp menu row. The current choice has a check and is marked selected; [busy] shows a spinner instead. */
@Composable
private fun AreaRow(text: String, icon: ImageVector, tag: String, selected: Boolean = false, enabled: Boolean = true, busy: Boolean = false,
                    onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onClick, enabled = enabled,
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
        trailingIcon = when {
            busy -> { { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) } }
            selected -> { { Icon(Icons.Default.Check, null, tint = Forest) } }
            else -> null
        },
        modifier = (if (selected) Modifier.semantics { this.selected = true } else Modifier).testTag(tag))
}

/**
 * The city search, in place of the chip row: a field with the matching cities listed under it. Typing searches
 * once it pauses for 350 ms, and a newer search cancels the one still running; the Search key picks the first
 * match, or searches at once when there is none yet, as Retry does. With fewer than two letters the list shows the
 * recent (or preset) cities. [close] runs for the X and for Back once the keyboard is hidden.
 *
 * In Material 3 1.3.2 the exposed menu only records an editable anchor after a tap. Automatic entry into city
 * mode therefore needs a nonfocusable popup of its own. Its bounds leave the field visible above the IME.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CitySearch(expanded: Boolean, recent: List<SearchPoint>, area: SearchPoint, search: suspend (String) -> List<SearchPoint>,
                        choose: (SearchPoint) -> Unit, close: () -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<SearchPoint>?>(null) } // The latest answer; null before the first.
    var answered by remember { mutableStateOf("") } // The text [found] or [failed] answers.
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var now by remember { mutableIntStateOf(0) } // Raised by the Search key and Retry to search without the pause.
    LaunchedEffect(Unit) {
        var handled = now
        snapshotFlow { query.trim() to now }
            .collectLatest { (text, request) ->
                val immediate = request != handled
                handled = request
                found = null
                failed = false
                answered = ""
                if (text.length < 2) {
                    searching = false
                    return@collectLatest
                }
                searching = true
                try {
                    // Keep the pause inside collectLatest so every edit, including clearing the field,
                    // cancels the previous request immediately.
                    if (!immediate) delay(350)
                    found = search(text).take(5)
                    answered = text
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { failed = true; answered = text }
                finally { searching = false }
            }
    }
    // The field takes focus and shows the keyboard once the menu or dialog that opened the search has let go of the window.
    val field = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val keyboardVisible = WindowInsets.isImeVisible
    fun leave() { keyboard?.hide(); focusManager.clearFocus(); close() }
    fun pick(city: SearchPoint) { keyboard?.hide(); focusManager.clearFocus(); choose(city) }
    LaunchedEffect(Unit) {
        snapshotFlow { window.isWindowFocused }.first { it }
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    val text = query.trim()
    val hint = stringResource(R.string.city_hint)
    fun searchKey() {
        val first = found?.firstOrNull()
        if (text.length < 2) return
        if (answered == text && !failed && first != null) pick(first) else now++
    }
    val view = LocalView.current
    val density = LocalDensity.current
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    var visibleBottom by remember { mutableIntStateOf(view.rootView.height) }
    DisposableEffect(view) {
        fun updateBottom() {
            val frame = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(frame)
            visibleBottom = frame.bottom
        }
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { updateBottom() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        updateBottom()
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    // This callback receives Back from ExposedDropdownMenuBox. The anchor itself is disabled so tapping
    // inside the field only moves the cursor; its own FocusRequester still provides autofocus.
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = {
        if (!it) { if (keyboardVisible) keyboard?.hide() else leave() }
    }, modifier = modifier.onGloballyPositioned {
        val origin = it.positionInWindow()
        anchor = IntRect(IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), it.size)
    }) {
        Surface(shape = RoundedCornerShape(50), color = Color.White, contentColor = Ink, shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable, enabled = false)) {
            Row(Modifier.height(48.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Forest)
                BasicTextField(query, { query = it.take(80) }, singleLine = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        .focusRequester(field).semantics { contentDescription = hint }.testTag("city-query"),
                    textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Forest),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchKey() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(hint, color = Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clearAndSetSemantics {})
                            inner()
                        }
                    })
                IconButton(onClick = { leave() }, modifier = Modifier.testTag("city-close")) { Icon(Icons.Default.Close, stringResource(R.string.city_close), tint = Muted) }
            }
        }
        val gap = with(density) { 6.dp.roundToPx() }
        val availableHeight = with(density) { (visibleBottom - anchor.bottom - gap * 2).coerceAtLeast(0).toDp() }
        if (expanded && anchor.width > 0 && availableHeight > 0.dp) Popup(
            popupPositionProvider = remember(anchor, gap) {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                                                   popupContentSize: IntSize) = IntOffset(
                        anchor.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), anchor.bottom + gap)
                }
            }, properties = PopupProperties(focusable = false, dismissOnBackPress = false), onDismissRequest = {}) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 8.dp,
                modifier = Modifier.width(with(density) { anchor.width.toDp() }).heightIn(max = availableHeight)
                    .semantics { testTagsAsResourceId = true }.testTag("city-results")) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    if (text.length < 2) CityRows(recent, area, ::pick)
                    else {
                        val results = found
                        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when {
                                searching || results == null && !failed -> stringResource(R.string.city_searching)
                                failed -> stringResource(R.string.city_failed)
                                results!!.isEmpty() -> stringResource(R.string.city_none)
                                else -> pluralStringResource(R.plurals.city_count, results.size, results.size)
                            }, fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag("city-status"))
                            if (failed && !searching) TextButton(onClick = { now++ }, modifier = Modifier.testTag("city-retry")) { Text(stringResource(R.string.city_retry)) }
                        }
                        if (!failed) results?.forEach { city ->
                            AreaRow(city.shortLabel(), Icons.Default.LocationOn, "city-${city.shortLabel()}",
                                selected = area.source == SearchSource.CITY && city.sameCell(area)) { pick(city) }
                        }
                    }
                }
            }
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The station search card, and under it the area chip with its menu, the "Search here" pill and the saved-stations
 * filter. "Choose a city…", or a change of [areaRequest], turns the chip row into the city search.
 *
 * [browse] is the map's view after the last pan or zoom. It is read here, in a derived state, so a pan recomposes
 * only this row, and only when the pill comes or goes.
 */
@Composable
internal fun TopControls(chrome: MapChrome, query: String, onQueryChange: (String) -> Unit, area: SearchPoint, browse: () -> MapMove?,
                         syncing: Boolean, offline: Boolean, locating: Boolean, areaRequest: Int, searchCities: suspend (String) -> List<SearchPoint>,
                         savedOnly: Boolean, onSavedOnlyChange: (Boolean) -> Unit, openSettings: () -> Unit, useLocation: () -> Unit,
                         searchArea: (SearchPoint) -> Unit, chooseCity: (SearchPoint) -> Unit) {
    val context = LocalContext.current
    val recentAreas = remember { RecentAreas(context) }
    var recent by remember { mutableStateOf(recentAreas.load()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var handledAreaRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(areaRequest) {
        if (areaRequest > handledAreaRequest) editing = true
        handledAreaRequest = areaRequest
    }
    val chipFocus = remember { FocusRequester() }
    // Leaving the city search gives focus back to the chip (outside touch mode), so the map's web view does not take it.
    var wasEditing by remember { mutableStateOf(editing) }
    LaunchedEffect(editing) {
        if (wasEditing && !editing) runCatching { chipFocus.requestFocus() }
        wasEditing = editing
    }
    val movedAway by remember(area) { derivedStateOf { mapMovedAway(area, browse()) } }
    // The area the pill last asked for; the pill stays, reading "Searching…", while it loads.
    var searched by remember { mutableStateOf<SearchPoint?>(null) }
    val pill = (movedAway || offline || syncing && area == searched) && chrome.detent != Detent.FULL && !editing
    fun searchHere() {
        val center = browse()?.center
        // Offline without a move, the pill retries the loaded area as it is.
        val next = if (movedAway && center != null) center.copy(label = "Map area", source = SearchSource.MAP) else area
        searched = next
        searchArea(next)
    }
    fun choose(city: SearchPoint) {
        editing = false
        recent = recentAreas.add(city)
        chooseCity(city)
    }
    Column(Modifier.widthIn(max = 560.dp).padding(16.dp).onGloballyPositioned { chrome.topControlsBottomPx = (it.positionInParent().y + it.size.height).roundToInt() }) {
        StationSearch(query, onQueryChange, openSettings)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).testTag("map-filter-row"), contentAlignment = Alignment.CenterStart) {
            Crossfade(editing, Modifier.fillMaxWidth(), animationSpec = tween(150), label = "area row") { cityMode ->
                if (cityMode) CitySearch(expanded = editing, recent, area, searchCities, choose = { choose(it) }, close = { editing = false },
                    modifier = Modifier.fillMaxWidth())
                else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    // The pill is measured first and the chip takes what is left. TalkBack announces the pill once.
                    Row(Modifier.padding(end = 56.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AreaMenu(area, recent, movedAway, locating, chipFocus, useLocation = useLocation, searchMapArea = { searchHere() },
                            choose = { choose(it) }, searchCity = { editing = true }, modifier = Modifier.weight(1f, fill = false))
                        AnimatedVisibility(pill, enter = fadeIn(tween(150)) + expandHorizontally(expandFrom = Alignment.Start),
                            exit = fadeOut(tween(100)) + shrinkHorizontally(shrinkTowards = Alignment.Start)) {
                            SearchHerePill(syncing, offline) { searchHere() }
                        }
                    }
                    SavedToggle(savedOnly, onSavedOnlyChange, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}

/**
 * The station search card with the settings button, which gives way to a clear button while there is a query.
 * The Search key, or hiding the keyboard, ends the search, so no cursor keeps blinking. The "openfuel" wordmark
 * shows only when there is room for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationSearch(query: String, onQueryChange: (String) -> Unit, openSettings: () -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.isImeVisible
    LaunchedEffect(keyboard) { if (!keyboard && focused) focusManager.clearFocus() }
    val label = stringResource(R.string.search)
    val wordmark = LocalConfiguration.current.screenWidthDp >= 400 && LocalDensity.current.fontScale <= 1.15f
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.openfuel_mark), null, Modifier.size(28.dp))
            if (wordmark) {
                Spacer(Modifier.width(6.dp))
                Text("openfuel", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            }
            Spacer(Modifier.width(12.dp)); VerticalDivider(Modifier.height(24.dp))
            BasicTextField(query, onQueryChange, singleLine = true,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label }.testTag("station-search"),
                textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Forest),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        // The field itself carries the label; the placeholder only shows it.
                        if (query.isEmpty()) Text(label, color = Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clearAndSetSemantics {})
                        inner()
                    }
                })
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("clear-search")) {
                Icon(Icons.Default.Close, stringResource(R.string.clear_search), tint = Muted)
            } else IconButton(onClick = openSettings, modifier = Modifier.testTag("open-settings")) {
                Icon(Icons.Default.Tune, stringResource(R.string.settings), tint = Forest)
            }
        }
    }
}

/** Loads the area on the map once it has moved away from the loaded area, or retries while offline. */
@Composable
private fun SearchHerePill(syncing: Boolean, offline: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !syncing, contentPadding = PaddingValues(start = 12.dp, end = 14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Forest, contentColor = Color.White,
            disabledContainerColor = Forest, disabledContentColor = Color.White.copy(alpha = .8f)),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 3.dp, disabledElevation = 3.dp),
        modifier = Modifier.heightIn(min = 40.dp).testTag("search-map-area")) {
        if (syncing) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(if (offline) Icons.Default.Refresh else Icons.Default.Search, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(when { syncing -> R.string.search_here_busy; offline -> R.string.search_here_retry; else -> R.string.search_here }),
            fontSize = 14.sp, maxLines = 1)
    }
}

/** Shows only saved stations. A long press explains it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedToggle(savedOnly: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val state = stringResource(if (savedOnly) R.string.saved_only_on else R.string.saved_only_off)
    // Keep parent Box alignment on our outer node; TooltipBox applies its modifier inside its anchor.
    Box(modifier) {
        TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(stringResource(R.string.saved_only_tip)) } }, state = rememberTooltipState()) {
            Surface(checked = savedOnly, onCheckedChange = onChange, shape = CircleShape, shadowElevation = 3.dp,
                color = if (savedOnly) Forest else Color.White, contentColor = if (savedOnly) Color.White else Forest,
                modifier = Modifier.size(40.dp).semantics { role = Role.Checkbox; stateDescription = state }.testTag("saved-filter")) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (savedOnly) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, stringResource(R.string.saved), Modifier.size(20.dp))
                }
            }
        }
    }
}

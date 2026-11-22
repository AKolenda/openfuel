// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.compose.animation.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The row over the map: a search button, the fuel-grade switch and the filters button. The search button opens the
 * station search in the row's place, with the area chip and the saved-stations filter under it. "Choose a city…",
 * or a change of [areaRequest], turns that second row into the city search. The search stays open while it has a
 * query, a saved-only filter or a city search; its close button clears the query and the filter.
 *
 * [browse] is the map's view after the last pan or zoom, read in a derived state so a pan recomposes only the
 * area menu's "Search this map area" row, and only when it is enabled or disabled.
 */
@Composable
internal fun TopControls(chrome: MapChrome, query: String, onQueryChange: (String) -> Unit, grade: Grade, onGradeChange: (Grade) -> Unit,
                         area: SearchPoint, browse: () -> MapMove?, locating: Boolean, areaRequest: Int, searchCities: suspend (String) -> List<SearchPoint>,
                         savedOnly: Boolean, onSavedOnlyChange: (Boolean) -> Unit, openSettings: () -> Unit, useLocation: () -> Unit,
                         searchArea: (SearchPoint) -> Unit, chooseCity: (SearchPoint) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val recentAreas = remember { RecentAreas(context) }
    var recent by remember { mutableStateOf(recentAreas.load()) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var focusRequest by remember { mutableIntStateOf(0) } // Raised by the search button to focus the field.
    var handledAreaRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(areaRequest) {
        if (areaRequest > handledAreaRequest) editing = true
        handledAreaRequest = areaRequest
    }
    val open = searching || editing || query.isNotEmpty() || savedOnly
    val chipFocus = remember { FocusRequester() }
    // Leaving the city search gives focus back to the chip (outside touch mode), so the map's web view does not take it.
    var wasEditing by remember { mutableStateOf(editing) }
    LaunchedEffect(editing) {
        if (wasEditing && !editing && open) runCatching { chipFocus.requestFocus() }
        wasEditing = editing
    }
    val movedAway by remember(area) { derivedStateOf { mapMovedAway(area, browse()) } }
    fun searchHere() {
        val center = browse()?.center ?: return
        searchArea(center.copy(label = "Map area", source = SearchSource.MAP))
    }
    fun choose(city: SearchPoint) {
        editing = false
        recent = recentAreas.add(city)
        chooseCity(city)
    }
    fun close() {
        focusManager.clearFocus()
        onQueryChange(""); onSavedOnlyChange(false)
        editing = false; searching = false; focusRequest = 0
    }
    BackHandler(enabled = open && !editing) { close() }
    var columnTop by remember { mutableIntStateOf(0) }
    var firstRowBottom by remember { mutableIntStateOf(0) }
    Column(Modifier.widthIn(max = 560.dp).padding(12.dp).onGloballyPositioned {
        columnTop = it.positionInParent().y.roundToInt()
        chrome.topControlsBottomPx = columnTop + it.size.height
        chrome.mapTopPx = columnTop + firstRowBottom
    }) {
        Row(Modifier.fillMaxWidth().onGloballyPositioned {
            firstRowBottom = (it.positionInParent().y + it.size.height).roundToInt()
            chrome.mapTopPx = columnTop + firstRowBottom
        }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (open) StationSearch(query, onQueryChange, focusRequest, close = { close() }, modifier = Modifier.weight(1f))
            else {
                RoundButton(Icons.Default.Search, stringResource(R.string.search), "open-search") { searching = true; focusRequest++ }
                GradeSwitch(grade, onGradeChange, Modifier.weight(1f))
            }
            RoundButton(Icons.Default.Tune, stringResource(R.string.settings), "open-settings", onClick = openSettings)
        }
        AnimatedVisibility(open, enter = fadeIn(tween(150)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).testTag("map-filter-row"), contentAlignment = Alignment.CenterStart) {
                Crossfade(editing, Modifier.fillMaxWidth(), animationSpec = tween(150), label = "area row") { cityMode ->
                    if (cityMode) CitySearch(expanded = editing, recent, area, searchCities, choose = { choose(it) }, close = { editing = false },
                        modifier = Modifier.fillMaxWidth())
                    else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        AreaMenu(area, recent, movedAway, locating, chipFocus, useLocation = useLocation, searchMapArea = { searchHere() },
                            choose = { choose(it) }, searchCity = { editing = true }, modifier = Modifier.padding(end = 56.dp))
                        SavedToggle(savedOnly, onSavedOnlyChange, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
}

/** A 48 dp white round button over the map. */
@Composable
private fun RoundButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.White, contentColor = Ink, shadowElevation = 3.dp,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }.testTag(tag)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}

/** Regular, Premium and Diesel as one switch; the chosen grade is ink. */
@Composable
private fun GradeSwitch(grade: Grade, onChange: (Grade) -> Unit, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(50), color = Color.White, shadowElevation = 3.dp, modifier = modifier.heightIn(min = 48.dp)) {
        Row(Modifier.padding(4.dp).selectableGroup().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Grade.entries.forEach { fuel ->
                val chosen = grade == fuel
                Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(if (chosen) Ink else Color.Transparent)
                    .selectable(chosen, role = Role.RadioButton) { onChange(fuel) }.testTag("grade-${fuel.name.lowercase()}"),
                    contentAlignment = Alignment.Center) {
                    Text(gradeLabel(fuel), fontSize = 14.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (chosen) Color.White else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
    }
}

/**
 * The station search field, with a close button that clears it. The Search key, or hiding the keyboard, ends the
 * typing, so no cursor keeps blinking. A new [focusRequest] focuses the field and shows the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationSearch(query: String, onQueryChange: (String) -> Unit, focusRequest: Int, close: () -> Unit, modifier: Modifier) {
    val focusManager = LocalFocusManager.current
    val field = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val keyboard = WindowInsets.isImeVisible
    LaunchedEffect(keyboard) { if (!keyboard && focused) focusManager.clearFocus() }
    LaunchedEffect(focusRequest) { if (focusRequest > 0) { runCatching { field.requestFocus() }; keyboardController?.show() } }
    val label = stringResource(R.string.search)
    Surface(shape = RoundedCornerShape(50), color = Color.White, shadowElevation = 3.dp, modifier = modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 14.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Muted)
            BasicTextField(query, onQueryChange, singleLine = true,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp).focusRequester(field).onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label }.testTag("station-search"),
                textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Ink),
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
            IconButton(onClick = close, modifier = Modifier.testTag("clear-search")) {
                Icon(Icons.Default.Close, stringResource(R.string.clear_search), tint = Muted)
            }
        }
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
                color = if (savedOnly) Ink else Color.White, contentColor = if (savedOnly) Color.White else Ink,
                modifier = Modifier.size(40.dp).semantics { role = Role.Checkbox; stateDescription = state }.testTag("saved-filter")) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (savedOnly) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, stringResource(R.string.saved), Modifier.size(20.dp))
                }
            }
        }
    }
}

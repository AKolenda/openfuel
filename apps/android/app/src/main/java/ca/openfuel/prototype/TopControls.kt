// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The station search card with the settings button, and under it the area chip and the saved-stations filter. */
@Composable
internal fun TopControls(chrome: MapChrome, query: String, onQueryChange: (String) -> Unit, area: SearchPoint, savedOnly: Boolean, onSavedOnlyChange: (Boolean) -> Unit,
                         openSettings: () -> Unit, chooseArea: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(16.dp).onGloballyPositioned { chrome.topControlsBottomPx = (it.positionInParent().y + it.size.height).roundToInt() }) {
        Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 5.dp) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 15.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.openfuel_mark), null, Modifier.size(30.dp))
                Spacer(Modifier.width(6.dp))
                Text("openfuel", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                Spacer(Modifier.width(12.dp)); VerticalDivider(Modifier.height(24.dp))
                BasicTextField(query, onQueryChange, singleLine = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = context.getString(R.string.search) },
                    textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 13.sp),
                    decorationBox = { inner -> if (query.isEmpty()) Text(stringResource(R.string.search), color = Muted, fontSize = 13.sp); inner() })
                IconButton(onClick = openSettings, modifier = Modifier.testTag("open-settings")) { Icon(Icons.Default.Tune, stringResource(R.string.settings), tint = Forest) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("map-filter-row"), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                TextButton(onClick = chooseArea, contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(36.dp).widthIn(max = 210.dp).testTag("search-area")
                        .semantics { contentDescription = "Choose area: ${area.label}" }) {
                    Icon(Icons.Default.LocationOn, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                    Text(area.label.substringBefore(" ·"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            FilledIconToggleButton(checked = savedOnly, onCheckedChange = onSavedOnlyChange,
                modifier = Modifier.size(40.dp).testTag("saved-filter"),
                colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = Color.White, contentColor = Forest,
                    checkedContainerColor = Forest, checkedContentColor = Color.White)) {
                Icon(if (savedOnly) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, stringResource(R.string.saved), Modifier.size(19.dp))
            }
        }
    }
}

/** Loads the area on the map once it has moved away from the loaded area, or retries the area while offline. */
@Composable
internal fun BoxScope.SearchAreaButton(point: SearchPoint, browsePoint: SearchPoint?, syncing: Boolean, offline: Boolean, search: (SearchPoint) -> Unit) {
    val movedAway = browsePoint?.let { approximateDistanceMetres(point, it.latitude, it.longitude) > 750 } == true
    if (movedAway || offline) Button(onClick = {
        search((browsePoint ?: point).copy(label = "Map area", source = SearchSource.MAP))
    }, enabled = !syncing, modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp).height(44.dp).testTag("search-map-area")) {
        Text(if (syncing) "Searching…" else if (offline) "Retry area search" else "Search this area")
    }
}

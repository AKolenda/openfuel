// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The area menu: the device location, a city search and a few preset cities. */
@Composable
internal fun LocationSearch(repository: StationRepository, message: String?, dismiss: () -> Unit, useLocation: () -> Unit, choose: (SearchPoint) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf(SearchPoint.EDMONTON, SearchPoint(51.0447, -114.0719, "Calgary · chosen city"), SearchPoint(49.2827, -123.1207, "Vancouver · chosen city"), SearchPoint(43.6532, -79.3832, "Toronto · chosen city"))) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SheetTitle("Choose your area", dismiss)
    message?.let { Text(it, color = Muted, modifier = Modifier.padding(bottom = 12.dp)) }
    Button(onClick = useLocation, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(8.dp)); Text("Use my location") }
    OutlinedTextField(query, { query = it.take(100) }, label = { Text("Search a Canadian city") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("city-query"))
    Button(onClick = { scope.launch {
        searching = true; error = null
        runCatching { repository.searchCities(query) }.onSuccess { results = it; if (it.isEmpty()) error = "No cities found. Try a nearby city." }
            .onFailure { error = "City search unavailable. Try again or choose a city below." }
        searching = false
    } }, enabled = query.trim().length >= 2 && !searching, modifier = Modifier.testTag("search-city")) { Text(if (searching) "Searching…" else "Search") }
    error?.let { Text(it, color = Muted) }
    results.forEach { result -> TextButton(onClick = { choose(result) }, modifier = Modifier.fillMaxWidth().testTag("city-${result.label.substringBefore(" ·")}")) { Text(result.label, modifier = Modifier.fillMaxWidth()) } }
    Text("Distances are straight-line distances from this area. Pan and zoom to explore the map; choose another area here to load its stations.", fontSize = 12.sp, color = Muted)
}

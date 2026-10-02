// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

/**
 * The menu sheet. It reads [menu] itself, so opening or switching a menu recomposes only the sheet.
 * [body] shows one menu; every close from inside a menu calls its `closeMenu`, which runs `after` once the menu is closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MenuHost(menu: () -> Menu?, dismiss: () -> Unit, body: @Composable (menu: Menu, closeMenu: (after: () -> Unit) -> Unit) -> Unit) {
    val closeMenu: (after: () -> Unit) -> Unit = { after -> dismiss(); after() }
    if (menu() != null) {
        ModalBottomSheet(onDismissRequest = dismiss, containerColor = Color.White,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
                menu()?.let { body(it, closeMenu) }
            }
        }
    }
}

/** One menu's content. Every close from inside the sheet goes through [closeMenu]. */
@Composable
internal fun MenuBody(
    menu: Menu,
    closeMenu: (after: () -> Unit) -> Unit,
    openMenu: (Menu) -> Unit,
    repository: StationRepository,
    locationMessage: String?,
    requestLocation: () -> Unit,
    chooseArea: (SearchPoint) -> Unit,
    filters: Filters,
    provider: MapProvider,
    fullWidth: Boolean,
    grade: Grade,
    changeGrade: (Grade) -> Unit,
    donate: (() -> Unit)?,
    refreshNow: () -> Unit,
    applySettings: (Filters, MapProvider, Boolean) -> Unit,
    sort: SortMode,
    changeSort: (SortMode) -> Unit,
    drafts: List<StationProposal>,
    clearDrafts: () -> Unit,
    selected: Station?,
    favorites: Set<String>,
    go: (Station) -> Unit,
    save: (Station) -> Unit,
    openReport: () -> Unit,
    submitting: Boolean,
    reportError: String?,
    reportLimited: Boolean,
    report: (Station, Int) -> Unit,
    saveDraft: (StationProposal) -> Unit,
    stack: List<Station>,
    brandLogos: Map<String, Bitmap>,
    showStation: (Station) -> Unit
) {
    when (menu) {
        Menu.LOCATION -> LocationSearch(repository, locationMessage, dismiss = { closeMenu {} }, useLocation = { closeMenu { requestLocation() } }) {
            closeMenu { chooseArea(it) }
        }
        Menu.SETTINGS -> SettingsContent(filters, provider, fullWidth, grade,
            changeGrade = changeGrade, about = { openMenu(Menu.ABOUT) },
            donate = donate, refresh = { refreshNow(); closeMenu {} },
            dismiss = { closeMenu {} }, apply = { f, p, wide ->
                applySettings(f, p, wide)
                closeMenu {}
            })
        Menu.SORT -> {
            SheetTitle(stringResource(R.string.sort_stations)) { closeMenu {} }
            SortMode.entries.forEach { mode -> OptionRow(sortLabel(mode), sortHelp(mode), sort == mode) { changeSort(mode); closeMenu {} } }
        }
        Menu.ABOUT -> {
            SheetTitle(stringResource(R.string.about)) { closeMenu {} }
            Text(stringResource(R.string.about_body), lineHeight = 24.sp, color = Muted)
            donate?.let { TextButton(onClick = it, modifier = Modifier.testTag("about-donate")) { Text(stringResource(R.string.donate_costs)) } }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.drafts_count, drafts.size), fontWeight = FontWeight.SemiBold)
            drafts.forEach { draft -> Text("${draft.name} · ${draft.kind.name.lowercase().replace('_', ' ')}", Modifier.padding(top = 10.dp), fontSize = 12.sp) }
            TextButton(onClick = clearDrafts) { Text(stringResource(R.string.delete_local_drafts)) }
        }
        Menu.DETAIL -> selected?.let { s ->
            StationDetail(s, grade, filters, saved = s.id in favorites, close = { closeMenu {} }, go = { go(s) },
                reportPrice = openReport, save = { save(s) }, suggestCorrection = { openMenu(Menu.CORRECTION) },
                stack = stack, brandLogos = brandLogos, showStation = showStation)
        }
        Menu.PRICE -> selected?.let { s -> PriceForm(s, grade, submitting, reportError, donate.takeIf { reportLimited }, close = { if (!submitting) closeMenu {} }) { value ->
            report(s, value)
        } }
        Menu.NEW_STATION, Menu.CORRECTION -> ProposalForm(if (menu == Menu.CORRECTION) selected else null,
            close = { closeMenu {} }, submit = saveDraft)
    }
}

@Composable private fun SettingsContent(initial: Filters, initialProvider: MapProvider, initialWide: Boolean, grade: Grade, changeGrade: (Grade) -> Unit, about: () -> Unit, donate: (() -> Unit)?, refresh: () -> Unit, dismiss: () -> Unit, apply: (Filters, MapProvider, Boolean) -> Unit) {
    var f by remember { mutableStateOf(initial) }; var provider by remember { mutableStateOf(initialProvider) }; var wide by remember { mutableStateOf(initialWide) }
    SheetTitle(stringResource(R.string.settings), dismiss)
    Text("Fuel type", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Grade.entries.forEach { fuel -> FilterChip(selected = grade == fuel, onClick = { changeGrade(fuel) },
            label = { Text(gradeLabel(fuel)) }, modifier = Modifier.testTag("fuel-${fuel.name.lowercase()}")) }
    }
    Text("Pull down at the top of the station list to refresh. Drag its handle to expand or hide the panel.", fontSize = 12.sp, color = Muted)
    TextButton(onClick = refresh, modifier = Modifier.testTag("refresh-prices")) { Text("Refresh stations now") }
    Text("Station data", fontWeight = FontWeight.SemiBold)
    Text("Station locations: OpenStreetMap contributors. Community pump prices are unverified; check the report time and confirm at the pump.", fontSize = 12.sp, color = Muted)
    TextButton(onClick = about, modifier = Modifier.testTag("open-about")) { Text("About OpenFuel and data sources") }
    if (donate != null) TextButton(onClick = donate, modifier = Modifier.testTag("donate")) { Text(stringResource(R.string.donate_costs)) }

    Text(stringResource(R.string.open_with), fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(provider == MapProvider.GOOGLE, { provider = MapProvider.GOOGLE }, label = { Text("Google Maps") })
        FilterChip(provider == MapProvider.ASK, { provider = MapProvider.ASK }, label = { Text(stringResource(R.string.ask_each_time)) })
    }
    Text(stringResource(R.string.maps_note), fontSize = 11.sp, color = Muted)
    Spacer(Modifier.height(22.dp)); Text(stringResource(R.string.button_layout), fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!wide, { wide = false }, label = { Text(stringResource(R.string.beside_price)) })
        FilterChip(wide, { wide = true }, label = { Text(stringResource(R.string.full_width)) })
    }
    Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.distance), fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(2000,5000,10000).forEach { radius -> FilterChip(f.radiusMetres == radius, { f = f.copy(radiusMetres = radius) }, label = { Text("${radius / 1000} km") }) } }
    SettingSwitch(stringResource(R.string.recent), f.fresh) { f = f.copy(fresh = it) }
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { f = Filters(); provider = MapProvider.GOOGLE; wide = false }, Modifier.weight(1f)) { Text(stringResource(R.string.reset)) }
        Button(onClick = { apply(f,provider,wide) }, Modifier.weight(1f)) { Text(stringResource(R.string.apply)) }
    }
}
@Composable private fun PriceForm(s: Station, grade: Grade, submitting: Boolean, error: String?, donate: (() -> Unit)?, close: () -> Unit, submit: (Int) -> Unit) {
    var text by remember { mutableStateOf(s.prices[grade]?.let(FuelCore::priceText) ?: "") }
    val value = FuelCore.parsePrice(text)
    SheetTitle(stringResource(R.string.report_price), close)
    Text(s.name + " · " + gradeLabel(grade)); Spacer(Modifier.height(12.dp))
    OutlinedTextField(text, { text = it.take(16) }, label = { Text(stringResource(R.string.price_label)) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, enabled = !submitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = text.isNotEmpty() && value == null)
    Text(stringResource(R.string.local_price_note), Modifier.padding(vertical = 14.dp), color = Muted, fontSize = 12.sp)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp).testTag("report-error")) }
    if (donate != null) TextButton(onClick = donate, modifier = Modifier.padding(bottom = 8.dp)) { Text(stringResource(R.string.donate)) }
    Button(onClick = { value?.let(submit) }, enabled = value != null && !submitting, modifier = Modifier.fillMaxWidth().testTag("submit-report")) {
        if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(stringResource(R.string.update_demo))
    }
}
@Composable private fun ProposalForm(s: Station?, close: () -> Unit, submit: (StationProposal) -> Unit) {
    var name by remember { mutableStateOf(s?.name ?: "") }; var lat by remember { mutableStateOf("") }; var lon by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }; var kind by remember { mutableStateOf(if (s == null) ProposalKind.NEW_STATION else ProposalKind.CORRECTION) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetTitle(stringResource(if (s == null) R.string.suggest_station else R.string.suggest_correction), close)
    Text(stringResource(R.string.proposal_note), fontSize = 12.sp, color = Muted)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.station_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (s == null) {
        Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(lat, { lat = it.take(20) }, label = { Text(stringResource(R.string.latitude)) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(lon, { lon = it.take(20) }, label = { Text(stringResource(R.string.longitude)) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    } else {
        listOf(ProposalKind.CORRECTION, ProposalKind.TEMPORARY_CLOSURE, ProposalKind.PERMANENT_CLOSURE).forEach { k ->
            val title = stringResource(when(k) { ProposalKind.CORRECTION -> R.string.correction; ProposalKind.TEMPORARY_CLOSURE -> R.string.temporary_closure; else -> R.string.permanent_closure })
            Row(Modifier.fillMaxWidth().clickable { kind = k }, verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == k, { kind = k }); Text(title) }
        }
    }
    Spacer(Modifier.height(10.dp)); OutlinedTextField(note, { note = it.take(500) }, label = { Text(stringResource(R.string.evidence_note)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    Text(stringResource(R.string.no_personal_data), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = {
        try { submit(FuelCore.newProposal(UUID.randomUUID().toString(), kind, s, name, lat.toDoubleOrNull(), lon.toDoubleOrNull(), note)) }
        catch (e: IllegalArgumentException) { error = e.message }
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_draft)) }
}

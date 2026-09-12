// SPDX-License-Identifier: AGPL-3.0-only
import ca.openfuel.prototype.*
fun main() {
    var n = 0
    fun verify(name: String, ok: Boolean) { check(ok) { name }; n++; println("PASS $name") }
    val stations = SampleData.stations(); val p = stations.first()
    verify("exact CAD price", FuelCore.priceText(1429) == "142.9")
    verify("price parser", FuelCore.parsePrice("142.9") == 1429)
    verify("price parser trimmed", FuelCore.parsePrice(" 142.9 ") == 1429)
    listOf("NaN", "Infinity", "-3", "142.99", "49.9", "400.0", "142<script>", "142,9", "").forEach { v -> verify("reject $v", FuelCore.parsePrice(v) == null) }
    verify("best fresh first", FuelCore.visible(stations, Grade.REGULAR, SortMode.BEST, Filters()).first().id == "parkside")
    verify("stale last", FuelCore.visible(stations, Grade.REGULAR, SortMode.BEST, Filters()).last().id == "cedar")
    verify("raw cheapest includes stale", FuelCore.visible(stations, Grade.REGULAR, SortMode.PRICE, Filters()).get(1).id == "cedar")
    verify("nearest", FuelCore.visible(stations, Grade.REGULAR, SortMode.NEAREST, Filters()).first().id == "juniper")
    verify("radius", FuelCore.visible(stations, Grade.REGULAR, SortMode.BEST, Filters(radiusMetres=2000)).size == 2)
    verify("unknown price station remains discoverable", FuelCore.visible(stations, Grade.DIESEL, SortMode.BEST, Filters()).any { it.id == "juniper" })
    verify("casefolded search", FuelCore.visible(stations, Grade.REGULAR, SortMode.BEST, Filters(), "sHeLl").single().id == "northline")
    verify("fresh filter", FuelCore.visible(stations, Grade.REGULAR, SortMode.BEST, Filters(fresh=true)).size == 5)
    verify("open filter", FuelCore.visible(stations, Grade.REGULAR, SortMode.BEST, Filters(open=true)).size == 5)
    verify("favorites", FuelCore.visible(stations, Grade.REGULAR, SortMode.BEST, Filters(), savedOnly=true, favorites=setOf("northline")).single().id == "northline")
    verify("membership discount", stations.first { it.id == "cedar" }.price(Grade.REGULAR, true) == 1409)
    verify("sample link contains no fictional address", !FuelCore.sampleMapUrl(p).contains("Parkway") && !FuelCore.sampleMapUrl(p).contains("origin"))
    verify("sample link query encoded", FuelCore.sampleMapUrl(p).endsWith("Petro-Canada+Canada"))
    val changed = FuelCore.updatePrice(p, Grade.REGULAR, 1439)
    verify("price edit immutable", changed.prices[Grade.REGULAR] == 1439 && p.prices[Grade.REGULAR] == 1429)
    verify("freshness per grade", changed.age(Grade.REGULAR) == 0 && changed.age(Grade.PREMIUM) == 7)
    verify("valid station coordinate", FuelCore.validStationPoint(45.4, -75.7))
    verify("invalid coordinate", !FuelCore.validStationPoint(Double.NaN,-75.7) && !FuelCore.validStationPoint(91.0,0.0))
    val proposal = FuelCore.newProposal("test", ProposalKind.NEW_STATION, null, "Sample station", 45.4, -75.7, "New sign")
    verify("new station pending", proposal.state == "pending_review")
    verify("closure never auto applies", FuelCore.newProposal("c",ProposalKind.PERMANENT_CLOSURE,p,p.name,null,null,"Empty site").state == "pending_review")
    verify("coordinates required", runCatching { FuelCore.newProposal("x",ProposalKind.NEW_STATION,null,"Station",null,null,"") }.isFailure)
    val camera = MapCamera.fit(390.0,844.0,336.0,stations)
    verify("camera matches approved HTML", kotlin.math.abs(camera.scale-305.0/785.0)<0.000001)
    verify("sample map is not GPS", stations.first().x==620f && stations.first().y==462f)
    verify("empty map camera fallback", MapCamera.fit(390.0,844.0,336.0,emptyList())==MapCamera(600.0,500.0,1.0))
    println("$n core checks passed")
}

// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Local only: no network requests. The saved station snapshot lives in its own file, not in the preferences. */
@RunWith(AndroidJUnit4::class)
class SnapshotStorageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("openfuel-live-v2", Context.MODE_PRIVATE)
    private val file get() = File(context.noBackupFilesDir, "stations-v3.json")
    private val body = """{"is_demo":false,"stations":[{"id":"osm-node-test","name":"Test station","brand":"","address":"Test address","latitude":51.05,"longitude":-114.07,"distanceMetres":100,"prices":{"regular":1459,"premium":null,"diesel":null},"ages":{"regular":5},"synthetic":false}]}"""

    @Before fun clear() {
        prefs.edit().clear().commit()
        AtomicFile(file).delete()
    }

    @Test fun preferencesSnapshotMovesToItsFile() {
        // As saved by versions up to 0.3.2, ten minutes ago.
        prefs.edit().putString("latitude", "51.04").putString("longitude", "-114.07").putString("area-label", "Calgary · chosen city")
            .putString("area-source", "CITY").putString("stations", body).putString("snapshot-latitude", "51.04")
            .putString("snapshot-longitude", "-114.07").putBoolean("coarse-snapshot-v3", true)
            .putLong("saved-at", System.currentTimeMillis() - 10 * 60_000).commit()
        val migrated = StationRepository(context).initial()
        assertTrue(migrated.cached)
        assertEquals(listOf("osm-node-test"), migrated.stations.map { it.id })
        assertEquals(15, migrated.stations.single().age(Grade.REGULAR))
        assertTrue(file.exists())
        assertFalse(prefs.contains("stations"))
        assertFalse(prefs.contains("snapshot-latitude"))
        // The next start reads the file, and the report ages keep counting from the original save.
        val again = StationRepository(context).initial()
        assertTrue(again.cached)
        assertEquals(1459, again.stations.single().price(Grade.REGULAR))
        assertEquals(15, again.stations.single().age(Grade.REGULAR))
        assertEquals("Calgary · chosen city", again.point.label)
    }

    @Test fun snapshotOnlyAnswersItsOwnArea() {
        val repository = StationRepository(context)
        repository.cache(repository.parseStations(JSONObject(body)), SearchPoint(51.0447, -114.0719, "Calgary · chosen city"))
        val saved = StationRepository(context).initial()
        assertTrue(saved.cached)
        assertEquals(SearchSource.CITY, saved.point.source)
        assertTrue(prefs.getLong("saved-at", 0) > 0)
        // Clearing the preferences leaves the file behind; another area must not show its stations.
        prefs.edit().clear().commit()
        assertFalse(StationRepository(context).initial().cached)
        repository.rememberArea(SearchPoint.EDMONTON)
        val other = StationRepository(context).initial()
        assertFalse(other.cached)
        assertTrue(other.stations.isEmpty())
    }

    @Test fun finishingAnOlderSnapshotDoesNotRestoreAnEarlierSearchArea() {
        val repository = StationRepository(context)
        repository.rememberArea(SearchPoint.EDMONTON)
        repository.cache(repository.parseStations(JSONObject(body)), SearchPoint(51.0447, -114.0719, "Calgary"), rememberSearchArea = false)
        val saved = repository.initial()
        assertEquals(SearchPoint.EDMONTON.forStorage(), saved.point)
        assertFalse(saved.cached)
        assertTrue(saved.stations.isEmpty())
    }

    @Test fun unreadableSnapshotKeepsTheChosenAreaForTheNextRefresh() {
        val repository = StationRepository(context)
        repository.rememberArea(SearchPoint.EDMONTON)
        file.parentFile!!.mkdirs()
        file.writeText("{interrupted")
        val saved = repository.initial()
        assertFalse(saved.cached)
        assertEquals(SearchPoint.EDMONTON.forStorage(), saved.point)
    }

    @Test fun atomicBackupIsRecoveredWhenTheBaseFileIsMissing() {
        val repository = StationRepository(context)
        repository.cache(repository.parseStations(JSONObject(body)), SearchPoint(51.0447, -114.0719, "Calgary"))
        assertTrue(file.renameTo(File(file.path + ".bak")))
        val recovered = repository.initial()
        assertTrue(recovered.cached)
        assertEquals("osm-node-test", recovered.stations.single().id)
        assertTrue(file.exists())
    }
}

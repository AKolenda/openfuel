// SPDX-License-Identifier: AGPL-3.0-only
package ca.openfuel.prototype

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private local draft storage only. No publisher trust and no remote synchronization. */
class LocalDraftStore(context:Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"station-drafts-v1.json"))
    @Synchronized fun load():List<StationProposal> {
        if(!file.baseFile.exists())return emptyList()
        val array=JSONArray(String(file.readFully(),Charsets.UTF_8))
        require(array.length()<=100){"Unexpected draft count"}
        return (0 until array.length()).map { index ->
            val o=array.getJSONObject(index)
            StationProposal(o.getString("id"),ProposalKind.valueOf(o.getString("kind")),if(o.isNull("stationId"))null else o.getString("stationId"),
                o.getString("name"),if(o.isNull("latitude"))null else o.getDouble("latitude"),if(o.isNull("longitude"))null else o.getDouble("longitude"),o.getString("note"),"pending_review")
        }
    }
    @Synchronized fun save(draft:StationProposal) {
        val values=load().toMutableList()
        val existing=values.find{it.id==draft.id}
        if(existing!=null){require(existing==draft){"Conflicting draft identifier"};return}
        require(values.size<100){"Draft limit reached. Delete old drafts first."}
        values.add(draft)
        val array=JSONArray()
        values.forEach { d -> array.put(JSONObject().put("id",d.id).put("kind",d.kind.name).put("stationId",d.stationId ?: JSONObject.NULL)
            .put("name",d.name).put("latitude",d.latitude ?: JSONObject.NULL).put("longitude",d.longitude ?: JSONObject.NULL).put("note",d.note)) }
        val output=file.startWrite()
        try {output.write(array.toString().toByteArray(Charsets.UTF_8));file.finishWrite(output)}
        catch(e:Exception){file.failWrite(output);throw e}
    }
    @Synchronized fun clear(){file.delete()}
}

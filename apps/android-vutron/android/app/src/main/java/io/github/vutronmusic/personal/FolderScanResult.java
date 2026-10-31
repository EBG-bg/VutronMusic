package io.github.vutronmusic.personal;
import org.json.*;
import java.util.*;

/** Only completely read authorized roots replace their prior index. Empty success is authoritative. */
final class FolderScanResult {
    final LinkedHashMap<String,List<JSONObject>> completed = new LinkedHashMap<>();
    final JSONArray warnings = new JSONArray();
    void failed(String folderId,String message) {
        try { warnings.put(new JSONObject().put("folderId",folderId).put("message",message)); }
        catch(JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    boolean complete() { return warnings.length()==0; }
    JSONArray merge(JSONArray previous) {
        JSONArray merged=new JSONArray();
        for(int i=0;i<previous.length();i++) {
            JSONObject row=previous.optJSONObject(i);
            if(row!=null && !completed.containsKey(row.optString("localFolderId"))) merged.put(row);
        }
        for(List<JSONObject> rows:completed.values()) for(JSONObject row:rows) merged.put(row);
        return merged;
    }
}

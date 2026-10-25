package io.github.vutronmusic.personal;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalMetadataMatchTest {
    private JSONObject row(long id, String title, String artist, String album, long duration, boolean local) throws Exception {
        JSONObject value = new JSONObject().put("id",id).put("name",title).put("dt",duration).put("localHasTitle",true)
                .put("ar",new JSONArray().put(new JSONObject().put("name",artist))).put("al",new JSONObject().put("name",album));
        if (local) value.put("localUri","file:///music/original.flac");
        return value;
    }
    @Test public void exactAdditionalIdentityCanEnrichWithoutChangingLocalAudio() throws Exception {
        JSONObject local = row(1,"Song","Artist","Album",180000,true), online = row(2,"Song","Artist","Album",181000,false);
        assertEquals(2,LocalMetadataEnricher.choose(local,new JSONArray().put(online)).getLong("id"));
        assertEquals("file:///music/original.flac",local.getString("localUri"));
    }
    @Test public void namesAloneWrongArtistsVersionsAlbumsOrDurationsAreRejected() throws Exception {
        JSONObject local = row(1,"Song","Artist","Album",180000,true);
        for(JSONObject candidate:new JSONObject[]{row(2,"Song","Other","Album",180000,false),row(3,"Song (Live)","Artist","Album",180000,false),row(4,"Song","Artist","Other",180000,false),row(5,"Song","Artist","Album",220000,false)})
            assertNull(LocalMetadataEnricher.choose(local,new JSONArray().put(candidate)));
        assertNull(LocalMetadataEnricher.choose(row(1,"Song","","",0,true),new JSONArray().put(row(2,"Song","Artist","Album",180000,false))));
    }
    @Test public void ambiguousOnlineIdsAreNotChosenArbitrarily() throws Exception {
        JSONObject local=row(1,"Song","Artist","Album",180000,true);
        assertNull(LocalMetadataEnricher.choose(local,new JSONArray().put(row(2,"Song","Artist","Album",180000,false)).put(row(3,"Song","Artist","Album",180000,false))));
    }
}

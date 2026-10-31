package io.github.vutronmusic.personal;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class NextTrackPrefetchTest {
    @Test public void unchangedQueueSkipsRepeatedPeeksAndMutationsReenableIt() throws Exception {
        NextTrackPrefetch.QueueSignature signature=new NextTrackPrefetch.QueueSignature();
        PlaybackQueue.Snapshot queue=PlaybackQueue.create(new JSONArray("[{\"id\":1},{\"id\":2}]"),0);
        int peeks=0;
        for(int second=0;second<60;second++) if(signature.claim(queue.token,7,0,queue.count(),queue.upNext.length(),queue.index,queue.insertedPlaying)) {
            assertEquals(2,queue.peekNext(0,true,0).getLong("id"));peeks++;
        }
        assertEquals(1,peeks);
        PlaybackQueue.Snapshot inserted=queue.appendNext(new JSONObject().put("id",3));
        assertTrue(signature.claim(inserted.token,7,0,inserted.count(),inserted.upNext.length(),inserted.index,inserted.insertedPlaying));
        assertFalse(signature.claim(inserted.token,7,0,inserted.count(),inserted.upNext.length(),inserted.index,inserted.insertedPlaying));
        assertTrue(signature.claim("new-token",7,0,2,1,0,false));
        signature.clear();assertTrue(signature.claim("new-token",7,0,2,1,0,false));
        assertTrue(signature.claim("new-token",8,0,2,1,0,false));
        assertTrue(signature.claim("new-token",8,1,2,1,0,false));
        assertTrue(signature.claim("new-token",8,1,2,1,1,false));
    }
    @Test public void peekDoesNotConsumeOrMutateModes() throws Exception {
        PlaybackQueue.Snapshot queue=PlaybackQueue.create(new JSONArray("[{\"id\":1},{\"id\":2},{\"id\":3}]"),0);
        String original=queue.encode();
        for (int mode=0;mode<3;mode++) for (boolean automatic:new boolean[]{true,false}) for(int random=0;random<2;random++)
            assertEquals(queue.next(mode,automatic,random).current().getLong("id"),queue.peekNext(mode,automatic,random).getLong("id"));
        assertEquals(original,queue.encode());
        queue=queue.appendNext(new JSONObject().put("id",4));
        assertEquals(4,queue.peekNext(1,true,0).getLong("id"));assertEquals(1,queue.upNext.length());
        assertFalse(NextTrackPrefetch.deterministic(2,3,0,false));
        assertFalse(NextTrackPrefetch.deterministic(1,3,0,false));
        assertTrue(NextTrackPrefetch.deterministic(2,3,1,false));
    }
    @Test public void ttlIdentityRevisionAndSingleConsumption() throws Exception {
        NextTrackPrefetch p=new NextTrackPrefetch();JSONObject location=new JSONObject().put("url","https://example.test/audio");
        int revision=p.revision();p.put(revision,"a","queue",location,100);
        assertSame(location,p.take("a","queue",101));assertNull(p.take("a","queue",102));
        p.put(p.revision(),"a","queue",location,100);assertNull(p.take("a","queue",100+NextTrackPrefetch.TTL_MS+1));
        p.put(p.revision(),"a","queue",location,100);assertNull(p.take("b","queue",100));
        p.put(p.revision(),"a","queue",location,100);assertNull(p.take("a","other",100));
        int old=p.revision();p.invalidate();p.put(old,"a","queue",location,100);assertNull(p.take("a","queue",101));
    }
}

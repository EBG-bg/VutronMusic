package io.github.vutronmusic.personal;
import org.junit.Test;
import org.json.*;
import com.getcapacitor.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ReviewRegressionTest {
    @Test public void bridgePlaybackCommandsDoNotWaitForABlockedOnlineWorker() throws Exception {
        VutronMusicPlugin plugin=new VutronMusicPlugin();
        java.lang.reflect.Field backgroundField=VutronMusicPlugin.class.getDeclaredField("worker");
        java.lang.reflect.Field playbackField=VutronMusicPlugin.class.getDeclaredField("playbackWorker");
        backgroundField.setAccessible(true);playbackField.setAccessible(true);
        ExecutorService background=(ExecutorService)backgroundField.get(plugin),playback=(ExecutorService)playbackField.get(plugin);
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            background.submit(()->{blocked.countDown();try{release.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}});
            assertTrue(blocked.await(2,TimeUnit.SECONDS));
            assertEquals("ready",playback.submit(()->"ready").get(2,TimeUnit.SECONDS));
            List<Integer> commands=Collections.synchronizedList(new ArrayList<>());
            playback.submit(()->commands.add(1));playback.submit(()->commands.add(2)).get(2,TimeUnit.SECONDS);
            assertEquals(Arrays.asList(1,2),commands);
        } finally { release.countDown();background.shutdownNow();playback.shutdownNow(); }
    }
    private JSONObject track(long id,String folder) throws Exception { return new JSONObject().put("id",id).put("localFolderId",folder); }
    @Test public void failedRootAndPartialChildNeverReplaceOldRowsButEmptySuccessDoes() throws Exception {
        JSONArray previous=new JSONArray().put(track(1,"root")).put(track(2,"other"));
        FolderScanResult failedRoot=new FolderScanResult();failedRoot.failed("root","root query failed");
        assertEquals(previous.toString(),failedRoot.merge(previous).toString());
        FolderScanResult partialChild=new FolderScanResult();partialChild.failed("root","child query failed after one row");
        partialChild.completed.put("other",Arrays.asList(track(3,"other")));
        JSONArray merged=partialChild.merge(previous);
        assertEquals(1,merged.getJSONObject(0).getLong("id"));assertEquals(3,merged.getJSONObject(1).getLong("id"));assertFalse(partialChild.complete());
        FolderScanResult empty=new FolderScanResult();empty.completed.put("root",Collections.emptyList());
        assertEquals(1,empty.merge(previous).length());assertTrue(empty.complete());
    }
    @Test public void persistentBackupOverInputLimitRemainsReadableAndOverflowIsRejectedBeforeWriting() throws Exception {
        JSONObject data=new JSONObject().put("collections",new JSONArray()).put("padding","x".repeat(LibraryBackup.MAX_BYTES+100));
        byte[] bytes=LibraryBackup.storeBytes(data);
        assertThrows(IOException.class,()->LibraryBackup.read(new ByteArrayInputStream(bytes)));
        assertEquals(0,LibraryBackup.readStore(new ByteArrayInputStream(bytes)).getJSONArray("collections").length());
        assertThrows(IOException.class,()->LibraryBackup.checkStoreSize((long)LibraryBackup.MAX_STORE_BYTES+1));
    }
    @Test public void realCapacitorIntegerAndLongNumbersUseTheCommonDecoder() throws Exception {
        for(String value:new String[]{"123","2000000000","3000000000"}) {
            PluginCall call=new PluginCall(null,"VutronMusic","test","deleteCachedTrack",new JSObject("{\"trackId\":"+value+"}"));
            assertEquals(Long.parseLong(value),VutronMusicPlugin.longArg(call,"trackId",0));
        }
    }
    @Test public void consecutiveValidImportsProduceAReadableMergedLibraryWithoutMutatingPriorData() throws Exception {
        JSONArray rows=new JSONArray();
        for(int i=0;i<2000;i++) rows.put(new JSONObject().put("name","Track "+i+" "+"x".repeat(1980))
                .put("artists",new JSONArray().put("Artist")).put("album","Album").put("duration",1000).put("fileName","track.flac"));
        JSONObject input=new JSONObject().put("format",1).put("generator",LibraryBackup.GENERATOR)
                .put("collections",new JSONArray().put(new JSONObject().put("name","First").put("tracks",rows)));
        assertTrue(input.toString().getBytes(StandardCharsets.UTF_8).length<LibraryBackup.MAX_BYTES);
        LibraryBackup.RestorePlan first=LibraryBackup.planRestore(new JSONObject().put("collections",new JSONArray()),input);
        JSONObject secondInput=new JSONObject(input.toString());secondInput.getJSONArray("collections").getJSONObject(0).put("name","Second");
        LibraryBackup.RestorePlan second=LibraryBackup.planRestore(first.document,secondInput);
        assertEquals(1,first.document.getJSONArray("collections").length());assertTrue(second.encoded.length>LibraryBackup.MAX_BYTES);
        assertEquals(2,LibraryBackup.readStore(new ByteArrayInputStream(second.encoded)).getJSONArray("collections").length());
    }
    @Test public void cancelledOrReplacedQrCannotPersistAndCannotRemoveNewLogin() throws Exception {
        Map<String,Object> sessions=new HashMap<>();Object old=new Object(),current=new Object();int[] writes={0};
        sessions.put("key",old);sessions.remove("key");
        assertFalse(QrLoginCommit.commit(sessions,"key",old,()->writes[0]++));
        sessions.put("key",current);
        assertFalse(QrLoginCommit.commit(sessions,"key",old,()->writes[0]++));
        assertSame(current,sessions.get("key"));assertEquals(0,writes[0]);
        assertTrue(QrLoginCommit.commit(sessions,"key",current,()->writes[0]++));assertEquals(1,writes[0]);assertTrue(sessions.isEmpty());
    }
}

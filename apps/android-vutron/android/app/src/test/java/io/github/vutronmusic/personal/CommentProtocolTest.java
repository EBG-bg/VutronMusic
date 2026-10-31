package io.github.vutronmusic.personal;
import org.junit.Test;
import org.json.*;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class CommentProtocolTest {
    @Test public void pagingAndWritesFollowNativeProtocol() throws Exception {
        List<String> paths=new ArrayList<>(); List<JSONObject> bodies=new ArrayList<>();
        NeteaseApi api=new NeteaseApi("MUSIC_U=test",()->true,(path,body,weapi)->{
            paths.add(path);bodies.add(body);
            return response("{\"code\":200,\"data\":{\"comments\":[],\"hasMore\":false}}");
        });
        api.comments("track","123",2,3,"0");
        assertEquals("normalHot#60",bodies.get(0).getString("cursor"));
        assertEquals("R_SO_4_123",bodies.get(0).getString("threadId"));
        api.commentFloor("album","2","9007199254740993","0");
        assertEquals("9007199254740993",bodies.get(1).getString("parentCommentId"));
        api.likeComment("playlist","3","9007199254740993",true);
        assertEquals("/api/v1/comment/like",paths.get(2));
        api.submitComment("track","1","reply","  text  ","3");
        assertEquals("text",bodies.get(3).getString("content"));
        assertThrows(IOException.class,()->api.submitComment("track","1","add"," ",""));
        assertThrows(IOException.class,()->api.submitComment("track","1","add","x".repeat(141),""));
    }
    @Test public void preservesLongIdentifiersAndDeletedReplies() throws Exception {
        JSONObject data=new JSONObject("{\"comments\":[{\"commentId\":9007199254740993,\"content\":\"<script>x</script>\",\"user\":{\"userId\":9007199254740995},\"beReplied\":[{\"content\":null}]}]}");
        JSONObject comment=CommentData.page(data,2,99,false).getJSONArray("comments").getJSONObject(0);
        assertEquals("9007199254740993",comment.getString("id"));assertEquals("9007199254740995",comment.getString("userId"));
        assertEquals("<script>x</script>",comment.getString("content"));
        assertEquals("该评论已删除",comment.getJSONArray("replies").getJSONObject(0).getString("content"));
        assertThrows(IOException.class,()->CommentData.page(new JSONObject(),2,99,false));
    }
    @Test public void offlineAndExpiredLoginFailWithoutRetries() throws Exception {
        NeteaseApi offline=new NeteaseApi("",()->false,(p,b,w)->{throw new AssertionError("network");});
        assertThrows(IOException.class,()->offline.comments("track","1",99,1,"0"));
        NeteaseApi expired=new NeteaseApi("",()->true,(p,b,w)->response("{\"code\":301}"));
        assertEquals(301,assertThrows(NeteaseApi.CommentFailure.class,()->expired.likeComment("track","1","2",true)).code);
    }
    private static JSONObject response(String text) throws IOException {
        try { return new JSONObject(text); } catch (JSONException invalid) { throw new IOException(invalid); }
    }
}

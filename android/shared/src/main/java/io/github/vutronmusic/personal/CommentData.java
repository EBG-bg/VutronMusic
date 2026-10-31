package io.github.vutronmusic.personal;

import org.json.*;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Explicit wire model: identifiers are strings, and UGC is never interpreted as HTML. */
final class CommentData {
    private CommentData() { }
    static JSONObject page(JSONObject data,int nextPage,int sort,boolean floor) throws IOException {
        if (data==null || !(data.opt("comments") instanceof JSONArray)) throw new IOException("评论响应不完整，请重试");
        try {
            JSONArray input=data.getJSONArray("comments"), rows=new JSONArray(); Set<String> seen=new HashSet<>();
            for (int i=0;i<input.length();i++) {
                JSONObject raw=input.optJSONObject(i); if (raw==null) continue;
                String id=raw.optString("commentId","");
                if (!id.matches("[1-9][0-9]{0,18}") || !seen.add(id)) continue;
                JSONObject user=raw.optJSONObject("user");
                String content=raw.optString("content",""); if (content.equals("null")) content="";
                boolean deleted=content.trim().isEmpty() || raw.optBoolean("isRemoveHotComment") || raw.optBoolean("deleted");
                JSONArray replies=new JSONArray(), original=raw.optJSONArray("beReplied");
                if (original!=null) for (int j=0;j<original.length();j++) {
                    JSONObject reply=original.optJSONObject(j); if(reply==null)continue;
                    JSONObject author=reply.optJSONObject("user"); String text=reply.optString("content","");
                    replies.put(new JSONObject().put("id",reply.optString("beRepliedCommentId",reply.optString("commentId","")))
                            .put("nickname",author==null?"":author.optString("nickname",""))
                            .put("content",text.isEmpty() || text.equals("null") ? "该评论已删除" : text));
                }
                long time=raw.optLong("time"); if(time<0 || time>4102444800000L)time=0;
                rows.put(new JSONObject().put("id",id).put("userId",user==null?"":user.optString("userId",""))
                        .put("nickname",user==null?"用户":user.optString("nickname","用户"))
                        .put("avatarUrl",user==null?"":user.optString("avatarUrl",""))
                        .put("content",deleted?"该评论已删除":content).put("deleted",deleted).put("time",time)
                        .put("liked",raw.optBoolean("liked")).put("likedCount",Math.max(0,raw.optInt("likedCount")))
                        .put("replyCount",Math.max(0,raw.optInt("replyCount"))).put("replies",replies));
            }
            String cursor=data.optString(floor?"time":"cursor","0"); if(cursor.equals("null"))cursor="0";
            return new JSONObject().put("comments",rows).put("total",Math.max(0,data.optInt("totalCount",data.optInt("total"))))
                    .put("hasMore",data.optBoolean("hasMore") && rows.length()>0).put("cursor",cursor).put("nextPage",nextPage).put("sort",sort);
        } catch(JSONException invalid) { throw new IOException("评论响应无效",invalid); }
    }
}

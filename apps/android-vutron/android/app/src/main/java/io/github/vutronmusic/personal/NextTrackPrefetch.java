package io.github.vutronmusic.personal;
import org.json.JSONObject;

/** One short-lived, single-consumption result. No signed URL or header is persisted. */
final class NextTrackPrefetch {
    static final long TTL_MS = 45000;
    /** Cheap queue state guard, before cloning the candidate JSON or calculating its identity. */
    static final class QueueSignature {
        private String token;
        private int generation,mode,count,pending,index;
        private boolean inserted,claimed;
        boolean claim(String token,int generation,int mode,int count,int pending,int index,boolean inserted) {
            if(claimed && java.util.Objects.equals(this.token,token) && this.generation==generation && this.mode==mode
                    && this.count==count && this.pending==pending && this.index==index && this.inserted==inserted) return false;
            this.token=token;this.generation=generation;this.mode=mode;this.count=count;this.pending=pending;
            this.index=index;this.inserted=inserted;claimed=true;return true;
        }
        void clear() { claimed=false;token=null; }
    }
    private String key="", queue="";
    private JSONObject location;
    private long created;
    private int revision;
    synchronized int invalidate() { revision++; location=null; key="";queue="";return revision; }
    synchronized int revision() { return revision; }
    synchronized void put(int expected,String trackKey,String queueToken,JSONObject result,long now) {
        if (expected!=revision || result==null) return;
        key=trackKey; queue=queueToken;location=result;created=now;
    }
    synchronized JSONObject take(String trackKey,String queueToken,long now) {
        JSONObject selected=location;
        boolean valid=location!=null && key.equals(trackKey) && queue.equals(queueToken) && now>=created && now-created<=TTL_MS;
        invalidate(); return valid?selected:null;
    }
    static String key(JSONObject song) { return song==null?"":song.optString("id")+"\n"+song.optString("neteaseId")+"\n"+LocalLibraryIds.uri(song)+PlaybackSegment.suffix(song); }
    static boolean deterministic(int mode, int count, int pending, boolean insertedPlaying) {
        return pending>0 || count>1 && mode==0 || count>1 && mode==1 && insertedPlaying;
    }
}

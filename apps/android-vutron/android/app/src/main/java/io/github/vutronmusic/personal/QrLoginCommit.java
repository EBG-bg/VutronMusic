package io.github.vutronmusic.personal;
import java.util.Map;

/** Cancellation and successful persistence share the registry lock. Network work stays outside it. */
final class QrLoginCommit {
    interface Persistence { void run() throws Exception; }
    static <T> boolean commit(Map<String,T> sessions,String key,T expected,Persistence persistence) throws Exception {
        synchronized(sessions) {
            if(sessions.get(key)!=expected) return false;
            persistence.run();
            sessions.remove(key);
            return true;
        }
    }
}

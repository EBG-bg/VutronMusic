package io.github.vutronmusic.personal;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reproduce WebView JSON decoding, including the exact Integer-to-zero bug. */
public class NativeBridgeNumberTest {
    private PluginCall call(String json) throws Exception {
        return new PluginCall(null, "VutronMusic", "test", "getPlaylists", new JSObject(json));
    }

    @Test public void ordinaryUserIdIsNotSilentlyZeroed() throws Exception {
        PluginCall request = call("{\"userId\":123456789}");
        assertTrue(request.getData().opt("userId") instanceof Integer);
        assertEquals(Long.valueOf(0), request.getLong("userId", 0L));
        assertEquals(123456789L, VutronMusicPlugin.longArg(request, "userId", 0));
    }

    @Test public void longIdsAndJavascriptNumericNotationRemainExact() throws Exception {
        assertEquals(4294967296L, VutronMusicPlugin.longArg(call("{\"id\":4294967296}"), "id", 0));
        assertEquals(1800000L, VutronMusicPlugin.longArg(call("{\"id\":1800000.0}"), "id", 0));
        assertEquals(4294967296L, VutronMusicPlugin.longArg(call("{\"id\":\"4294967296\"}"), "id", 0));
        assertEquals(-2500L, VutronMusicPlugin.longArg(call("{\"id\":-2500}"), "id", 0));
    }

    @Test public void malformedNumbersCannotBecomeValidSettings() throws Exception {
        for (String value : new String[]{"null", "true", "1.5", "1e40", "\"abc\""}) {
            assertEquals(-1L, VutronMusicPlugin.longArg(call("{\"id\":" + value + "}"), "id", -1));
        }
        assertEquals(-1L, VutronMusicPlugin.longArg(call("{}"), "id", -1));
    }
}

package io.github.vutronmusic.personal;
import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;

public class CueDirectoryIndexTest {
    private String cue(String file,String prefix) {
        return "FILE \""+file+"\" WAVE\nTRACK 01 AUDIO\nTITLE \""+prefix+"1\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \""+prefix+"2\"\nINDEX 01 00:10:00";
    }
    @Test public void twoCuesCountNamesOnceAndParseOnceInAudioOrder() throws Exception {
        List<String> audio=Arrays.asList("A.flac","B.wav"); int[] nameReads={0},parses={0};
        Map<String,Integer> names=CueDirectoryIndex.countAudioNames(audio,name->{nameReads[0]++;return name;});
        CueDirectoryIndex index=new CueDirectoryIndex(names,CueDirectoryIndex.MAX_TEXT_BYTES,(text,duration)->{
            parses[0]++;assertEquals(30000,duration);return CueParser.parse(text,duration);
        });
        index.add("cue:b","B.cue",cue("B.wav","B"));
        index.add("cue:a","A.cue",cue("A.flac","A"));
        assertEquals(0,parses[0]);assertEquals(2,nameReads[0]);
        List<String> ordered=new ArrayList<>();Set<Long> ids=new HashSet<>();
        for(String name:audio) {
            CueParser.Sheet sheet=index.parseForAudio(name,30000);
            JSONObject source=new JSONObject().put("localUri","content://test/"+name).put("duration",30000);
            for(JSONObject row:LocalFolderStore.cueTracks(source,index.cueUri(name),sheet)) {
                ordered.add(row.getString("name"));assertTrue(ids.add(row.getLong("id")));
            }
        }
        assertEquals(Arrays.asList("A1","A2","B1","B2"),ordered);
        assertEquals(2,nameReads[0]);assertEquals(2,parses[0]);assertEquals(0,index.retainedTextBytes());
        index.clear();assertEquals(0,index.retainedTextBytes());
    }
    @Test public void textBudgetFallsBackWithoutHoldingMoreTextOrParsingSkippedCues() {
        String a=cue("A.flac","A"),b=cue("B.wav","B");int[] parses={0};
        int budget=a.length()*2;
        CueDirectoryIndex index=new CueDirectoryIndex(CueDirectoryIndex.countAudioNames(Arrays.asList("A.flac","B.wav"),s->s),budget,(text,duration)->{parses[0]++;return CueParser.parse(text,duration);});
        index.add("a","A.cue",a);index.add("b","B.cue",b);
        assertEquals(budget,index.retainedTextBytes());
        assertThrows(IllegalArgumentException.class,()->index.parseForAudio("B.wav",30000));
        assertEquals(0,parses[0]);assertNotNull(index.parseForAudio("A.flac",30000));
        assertEquals(1,parses[0]);assertEquals(0,index.retainedTextBytes());
        index.clear();
    }
    @Test public void duplicateAudioAndCueNamesStillFallBackAndWarningsStayAssociated() {
        Map<String,Integer> names=CueDirectoryIndex.countAudioNames(Arrays.asList("A.flac","a.FLAC","ordinary.mp3"),s->s);
        CueDirectoryIndex index=new CueDirectoryIndex(names);
        index.add("a","A.cue",cue("A.flac","A"));
        CueDirectoryIndex duplicateAudio=index;
        assertThrows(IllegalArgumentException.class,()->duplicateAudio.parseForAudio("A.flac",30000));
        assertNull(index.parseForAudio("ordinary.mp3",30000));
        assertEquals("",index.warning("ordinary.mp3"));
        index.clear();
        index=new CueDirectoryIndex(CueDirectoryIndex.countAudioNames(Arrays.asList("A.flac"),s->s));
        index.add("a","A.cue",cue("A.flac","A"));index.add("b","copy.cue",cue("A.flac","B"));
        CueDirectoryIndex duplicate=index;
        assertThrows(IllegalArgumentException.class,()->duplicate.parseForAudio("A.flac",30000));
        index.clear();
    }
    @Test public void fileNameOnlyValidatesOnlyTheHeaderUntilActualDurationIsKnown() {
        assertEquals("Album.flac",CueParser.fileNameOnly("\uFEFFREM ignored\nFILE \"Album.flac\" WAVE\nTRACK broken\nINDEX 01 bad"));
        assertEquals("a.wav",CueParser.fileNameOnly(" file a.wav WAVE"));
        assertThrows(IllegalArgumentException.class,()->CueParser.fileNameOnly("FILE \"../a.wav\" WAVE"));
        assertThrows(IllegalArgumentException.class,()->CueParser.fileNameOnly("FILE a.wav WAVE\nFILE b.wav WAVE"));
        assertThrows(IllegalArgumentException.class,()->CueParser.fileNameOnly("TITLE missing"));
        assertThrows(IllegalArgumentException.class,()->CueParser.fileNameOnly("x".repeat(CueParser.MAX_BYTES+1)));
    }
}

package io.github.vutronmusic.personal;

import java.util.*;
import java.util.function.Function;

/** Directory-scoped CUE text/index budget. Full parsing happens once, with actual audio metadata. */
final class CueDirectoryIndex {
    static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    interface SheetParser { CueParser.Sheet parse(String text,long duration); }
    private final Map<String,Integer> audioNameCounts;
    private final Map<String,List<String>> cuesByFile = new HashMap<>();
    private final Map<String,String> texts = new HashMap<>(), errors = new HashMap<>(), warnings = new HashMap<>();
    private final SheetParser parser;
    private final int maxTextBytes;
    private int textBytes;

    CueDirectoryIndex(Map<String,Integer> names) { this(names,MAX_TEXT_BYTES,CueParser::parse); }
    CueDirectoryIndex(Map<String,Integer> names,int limit,SheetParser parser) {
        this.audioNameCounts=names;this.maxTextBytes=limit;this.parser=parser;
    }
    static <T> Map<String,Integer> countAudioNames(Iterable<T> files,Function<T,String> name) {
        Map<String,Integer> counts=new HashMap<>();
        for(T file:files) counts.merge(name.apply(file).toLowerCase(Locale.ROOT),1,Integer::sum);
        return counts;
    }
    void add(String cueId,String cueName,String text) {
        try {
            String file=CueParser.fileNameOnly(text).toLowerCase(Locale.ROOT);
            if (!audioNameCounts.containsKey(file)) {
                unreadable(cueName,"","CUE 引用的音频不在当前目录，已保留整轨");return;
            }
            cuesByFile.computeIfAbsent(file,key -> new ArrayList<>()).add(cueId);
            int bytes=text.length()*2; // Conservative UTF-16 storage budget, independent of the input encoding.
            if (bytes>maxTextBytes-textBytes) {
                errors.put(cueId,"同目录 CUE 文本超过内存上限，已保留整轨");return;
            }
            texts.put(cueId,text);textBytes+=bytes;
        } catch(IllegalArgumentException invalid) { unreadable(cueName,text,"CUE 未分轨："+invalid.getMessage()); }
    }
    void unreadable(String cueName,String text,String message) {
        for(String related:CueParser.warningTargets(cueName,text)) warnings.put(related,message);
    }
    String cueUri(String audioName) {
        List<String> ids=cuesByFile.get(audioName.toLowerCase(Locale.ROOT));
        return ids==null || ids.isEmpty()?"":ids.get(0);
    }
    CueParser.Sheet parseForAudio(String name,long duration) {
        String key=name.toLowerCase(Locale.ROOT);
        List<String> ids=cuesByFile.get(key);
        if(ids==null) return null;
        if(ids.size()!=1 || audioNameCounts.getOrDefault(key,0)!=1)
            throw new IllegalArgumentException("音频或 CUE 名称不唯一，已保留整轨");
        String id=ids.get(0), error=errors.get(id);
        if(error!=null) throw new IllegalArgumentException(error);
        String text=texts.remove(id);
        if(text==null) throw new IllegalStateException("CUE 文本已释放");
        textBytes-=text.length()*2;
        return parser.parse(text,duration);
    }
    String warning(String name) { return CueParser.warningFor(warnings,name); }
    int retainedTextBytes() { return textBytes; }
    void clear() { texts.clear();errors.clear();warnings.clear();cuesByFile.clear();textBytes=0; }
}

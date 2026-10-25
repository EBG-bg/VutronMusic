package io.github.vutronmusic.personal;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reads only the selected recording and an exact-basename sibling within its existing grant. */
final class LocalLyricSource {
    private static final int MAX_LYRIC_BYTES = 256 * 1024;
    private static final int MAX_TAG_BYTES = 4 * 1024 * 1024;
    private LocalLyricSource() { }

    static LyricRepository.Lyrics load(Context context, JSONObject song) {
        if (song == null) return null;
        String source = song.optString("localUri", "");
        if (source.isEmpty()) source = song.optString("localPath", "");
        if (source.isEmpty() || song.optBoolean("cached") || "cache".equals(song.optString("source"))) return null;
        try {
            String sidecar = sidecar(context, source, song.optString("localFileName", ""));
            LyricRepository.Lyrics result = fromText(sidecar);
            if (result != null) return result;
        } catch (Exception unavailable) { Log.d("VutronLyrics", "Local sidecar unavailable: " + unavailable.getClass().getSimpleName()); }
        try (InputStream input = open(context, source)) { return fromText(embedded(input)); }
        catch (Exception unavailable) { Log.d("VutronLyrics", "Embedded lyrics unavailable: " + unavailable.getClass().getSimpleName()); return null; }
    }

    private static LyricRepository.Lyrics fromText(String value) throws org.json.JSONException {
        if (value == null || value.isEmpty()) return null;
        LyricRepository.Lyrics result = new LyricRepository.Lyrics(new JSONObject().put("lrc", value), true);
        return result.original.lines.isEmpty() ? null : result;
    }

    private static InputStream open(Context context, String source) throws IOException {
        if (source.startsWith("content://")) return context.getContentResolver().openInputStream(Uri.parse(source));
        return new FileInputStream(source.startsWith("file://") ? new File(Uri.parse(source).getPath()) : new File(source));
    }

    private static String sidecar(Context context, String source, String filename) throws Exception {
        if (!source.startsWith("content://")) {
            File audio = source.startsWith("file://") ? new File(Uri.parse(source).getPath()) : new File(source);
            String wanted = sidecarName(audio.getName());
            File[] siblings = audio.getParentFile() == null ? null : audio.getParentFile().listFiles();
            if (siblings != null) for (File file : siblings) if (file.isFile() && file.getName().equalsIgnoreCase(wanted)) {
                try (InputStream input = new FileInputStream(file)) { return decode(readLimited(input, MAX_LYRIC_BYTES)); }
            }
            return "";
        }
        Uri audio = Uri.parse(source);
        if (!DocumentsContract.isTreeUri(audio)) return "";
        String document = DocumentsContract.getDocumentId(audio);
        String parent = "";
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                DocumentsContract.Path path = DocumentsContract.findDocumentPath(context.getContentResolver(), audio);
                List<String> ids = path == null ? null : path.getPath();
                if (ids != null && ids.size() > 1) parent = ids.get(ids.size() - 2);
            } catch (Exception unsupported) { /* Some providers expose no parent path. */ }
        }
        if (parent.isEmpty() && "com.android.externalstorage.documents".equals(audio.getAuthority())) {
            int slash = document.lastIndexOf('/');
            parent = slash >= 0 ? document.substring(0, slash) : document.substring(0, document.indexOf(':') + 1);
        }
        if (parent.isEmpty()) return "";
        if (filename.isEmpty()) {
            try (Cursor cursor = context.getContentResolver().query(audio, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) filename = cursor.getString(0);
            }
        }
        if (filename == null || filename.isEmpty()) return "";
        String wanted = sidecarName(filename);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(audio, parent);
        try (Cursor cursor = context.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) return "";
            while (cursor.moveToNext()) if (wanted.equalsIgnoreCase(cursor.getString(1))) {
                Uri sibling = DocumentsContract.buildDocumentUriUsingTree(audio, cursor.getString(0));
                try (InputStream input = context.getContentResolver().openInputStream(sibling)) { return decode(readLimited(input, MAX_LYRIC_BYTES)); }
            }
        }
        return "";
    }

    static String sidecarName(String name) { int dot = name.lastIndexOf('.'); return (dot > 0 ? name.substring(0, dot) : name) + ".lrc"; }

    static String decode(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        int start = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191 ? 3 : 0;
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString(); }
        catch (CharacterCodingException legacy) { return Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString(); }
    }

    /** ID3 USLT (MP3) and Vorbis LYRICS/UNSYNCEDLYRICS (FLAC); no whole-audio read. */
    static String embedded(InputStream input) throws IOException {
        byte[] head = readExactly(input, 10);
        if (head.length < 4) return "";
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3' && head.length == 10) {
            int size = syncSafe(head, 6);
            if (size <= 0 || size > MAX_TAG_BYTES || (head[5] & 0x80) != 0) return "";
            return id3Lyrics(readExactly(input, size), head[3] & 255);
        }
        if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
            // The first six bytes following fLaC were already read with the header.
            InputStream blocks = new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(head, 4, head.length - 4), input);
            int consumed = 0;
            while (consumed < MAX_TAG_BYTES) {
                byte[] header = readExactly(blocks, 4); if (header.length != 4) break;
                int size = ((header[1] & 255) << 16) | ((header[2] & 255) << 8) | (header[3] & 255);
                if (size > MAX_TAG_BYTES - consumed) break;
                byte[] data = readExactly(blocks, size); if (data.length != size) break;
                consumed += size + 4;
                if ((header[0] & 127) == 4) { String value = vorbisLyrics(data); if (!value.isEmpty()) return value; }
                if ((header[0] & 128) != 0) break;
            }
        }
        return "";
    }

    static String id3Lyrics(byte[] tag, int version) {
        if (version != 3 && version != 4) return "";
        for (int offset = 0; offset + 10 <= tag.length;) {
            String id = new String(tag, offset, 4, StandardCharsets.ISO_8859_1);
            int size = version == 4 ? syncSafe(tag, offset + 4) : bigEndian(tag, offset + 4);
            if (size <= 0 || size > tag.length - offset - 10) break;
            int body = offset + 10;
            if ("USLT".equals(id) && size >= 5 && (tag[offset + 9] & 0xcf) == 0) {
                int encoding = tag[body] & 255; int start = body + 4; int end = body + size;
                boolean wide = encoding == 1 || encoding == 2;
                while (start < end) {
                    if (tag[start] == 0 && (!wide || start + 1 < end && tag[start + 1] == 0)) { start += wide ? 2 : 1; break; }
                    start += wide ? 2 : 1;
                }
                if (end - start > MAX_LYRIC_BYTES || start > end) return "";
                Charset charset = encoding == 1 ? StandardCharsets.UTF_16 : encoding == 2 ? StandardCharsets.UTF_16BE : encoding == 3 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
                return new String(tag, start, end - start, charset).replace("\u0000", "");
            }
            offset += 10 + size;
        }
        return "";
    }

    static String vorbisLyrics(byte[] data) {
        if (data.length < 8) return "";
        long vendor = littleEndian(data, 0); if (vendor > data.length - 8) return "";
        int offset = 4 + (int) vendor; long count = littleEndian(data, offset); offset += 4;
        for (int i = 0; i < count && offset + 4 <= data.length; i++) {
            long size = littleEndian(data, offset); offset += 4;
            if (size > data.length - offset) return "";
            String value = size <= MAX_LYRIC_BYTES ? new String(data, offset, (int) size, StandardCharsets.UTF_8) : "";
            int equals = value.indexOf('=');
            if (equals > 0 && ("LYRICS".equalsIgnoreCase(value.substring(0, equals)) || "UNSYNCEDLYRICS".equalsIgnoreCase(value.substring(0, equals)))) return value.substring(equals + 1);
            offset += (int) size;
        }
        return "";
    }

    private static byte[] readLimited(InputStream input, int max) throws IOException {
        if (input == null) return new byte[0];
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > max) throw new IOException("Lyric text exceeds limit"); output.write(buffer, 0, count); }
        return output.toByteArray();
    }
    private static byte[] readExactly(InputStream input, int count) throws IOException {
        if (input == null) return new byte[0];
        byte[] data = new byte[count]; int position = 0, read;
        while (position < count && (read = input.read(data, position, count - position)) != -1) position += read;
        return position == count ? data : java.util.Arrays.copyOf(data, position);
    }
    private static int syncSafe(byte[] data, int at) { return ((data[at] & 127) << 21) | ((data[at + 1] & 127) << 14) | ((data[at + 2] & 127) << 7) | (data[at + 3] & 127); }
    private static int bigEndian(byte[] data, int at) { return ((data[at] & 255) << 24) | ((data[at + 1] & 255) << 16) | ((data[at + 2] & 255) << 8) | (data[at + 3] & 255); }
    private static long littleEndian(byte[] data, int at) { return ((long) data[at] & 255) | (((long) data[at + 1] & 255) << 8) | (((long) data[at + 2] & 255) << 16) | (((long) data[at + 3] & 255) << 24); }
}

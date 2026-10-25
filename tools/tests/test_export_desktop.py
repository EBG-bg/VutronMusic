import importlib.util
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("desktop_export", ROOT / "tools/export-desktop-local-playlist.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)

class DesktopExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.db = self.root / "library.sqlite"
        self.a = self.root / "original a.flac"; self.b = self.root / "original b.mp3"
        self.a.write_bytes(b"original-a"); self.b.write_bytes(b"original-b")
    def test_current_schema_preserves_entry_order_and_originals_without_exporting_private_data(self):
        with sqlite3.connect(self.db) as db:
            db.executescript((ROOT / "src/public/migrations/plugin.sql").read_text(encoding="utf-8"))
            db.execute("INSERT INTO Playlist(id,pluginId,name,createTime,updateTime) VALUES('p','local','Playlist',1,1)")
            db.execute("INSERT INTO AppData VALUES('cookie','must-not-export')")
            for ident,path in [("a",self.a),("b",self.b)]:
                db.execute("INSERT INTO Track(id,name,duration) VALUES(?,?,?)",(ident,ident,180000))
                db.execute("INSERT INTO Audio(id,trackId,filePath,md5,size) VALUES(?,?,?,?,?)",(ident,ident,str(path),"digest",path.stat().st_size))
            for position,ident in enumerate(["b","a"]):
                db.execute("INSERT INTO PlaylistEntry(playlistId,pluginId,sourceContext,position,createTime) VALUES('p','local',?,?,1)",(json.dumps({"id":ident}),1-position))
        db.close()
        before=self.db.read_bytes(); output=self.root/"export.zip"
        result=tool.export(self.db,"p",output)
        self.assertEqual(result["tracks"],2); self.assertEqual(before,self.db.read_bytes())
        with zipfile.ZipFile(output) as archive:
            manifest=json.loads(archive.read("manifest.json"))
            self.assertEqual(manifest["generator"],"vutron-desktop-export")
            self.assertEqual([x["sourceTrackId"] for x in manifest["tracks"]],["b","a"])
            text=json.dumps(manifest)
            self.assertNotIn("must-not-export",text); self.assertNotIn(str(self.root),text)
            self.assertEqual(archive.read(manifest["tracks"][0]["fileName"]),b"original-b")
        self.assertEqual(self.a.read_bytes(),b"original-a"); self.assertEqual(self.b.read_bytes(),b"original-b")
        with self.assertRaises(FileExistsError): tool.export(self.db,"p",output)
    def test_matching_lyrics_use_audio_basename_and_respect_size_limit(self):
        enhanced = "[00:01]<00:01>One<00:02>two<00:03>"
        self.a.with_suffix('.eslrc').write_text(enhanced, encoding='utf-8')
        self.a.with_suffix('.lrc').write_text('[00:01]Plain', encoding='utf-8')
        self.b.with_suffix('.lrc').write_bytes(b'x' * (256 * 1024 + 1))
        with sqlite3.connect(self.db) as db:
            db.executescript("CREATE TABLE Playlist(id INTEGER,isLocal INTEGER,json TEXT); CREATE TABLE Track(id INTEGER,json TEXT)")
            db.execute("INSERT INTO Playlist VALUES(1,1,?)", (json.dumps({'name':'Lyrics','trackIds':[1,2]}),))
            for ident, source in [(1,self.a),(2,self.b)]:
                db.execute('INSERT INTO Track VALUES(?,?)', (ident,json.dumps({'name':str(ident),'filePath':str(source)})))
        db.close()
        output = self.root / 'lyrics.zip'
        result = tool.export(self.db, '1', output)
        self.assertEqual(result['lyricFiles'], 2)
        self.assertEqual(result['skippedLyrics'], ['2.lrc'])
        with zipfile.ZipFile(output) as archive:
            manifest = json.loads(archive.read('manifest.json'))
            first = Path(manifest['tracks'][0]['fileName'])
            self.assertEqual(archive.read(first.with_suffix('.eslrc').as_posix()).decode('utf-8'), enhanced)
            self.assertIn(first.with_suffix('.lrc').as_posix(), archive.namelist())
            self.assertFalse(any('\\' in name for name in archive.namelist()))
            self.assertEqual(len(archive.namelist()), 5)
        self.assertTrue(self.a.with_suffix('.eslrc').is_file())

    def test_legacy_json_schema_retains_playlist_sequence_and_skips_missing_files(self):
        with sqlite3.connect(self.db) as db:
            db.executescript("CREATE TABLE Playlist(id INTEGER,isLocal INTEGER,json TEXT); CREATE TABLE Track(id INTEGER,json TEXT)")
            db.execute("INSERT INTO Playlist VALUES(1,1,?)",(json.dumps({"name":"Legacy","trackIds":[2,404,1]}),))
            for ident,path in [(1,self.a),(2,self.b)]:
                db.execute("INSERT INTO Track VALUES(?,?)",(ident,json.dumps({"name":str(ident),"filePath":str(path),"artists":[{"name":"Artist"}],"cookie":"never"})))
        db.close()
        output=self.root/"legacy.zip"; result=tool.export(self.db,"1",output)
        self.assertEqual(result["missing"],["404"])
        with zipfile.ZipFile(output) as archive:
            data=json.loads(archive.read("manifest.json")); self.assertEqual([x["name"] for x in data["tracks"]],["2","1"])
            self.assertNotIn("cookie",json.dumps(data))

    def test_account_playlists_export_full_sequence_without_downloading_or_credentials(self):
        with sqlite3.connect(self.db) as db:
            db.executescript("CREATE TABLE Playlist(id INTEGER,isLocal INTEGER,json TEXT); CREATE TABLE Track(id INTEGER,json TEXT)")
            db.execute("INSERT INTO Track VALUES(7,?)",(json.dumps({"name":"Local matched", "filePath":str(self.a), "matched":True}),))
        db.close()
        class Account:
            def playlists(self): return [{"id":"netease:90", "remoteId":"90", "kind":"account", "name":"My account playlist", "description":""}]
            def tracks(self, selected):
                return [(str(i), {"name":"Track "+str(i),"artist":"Singer","album":"Album","duration":1000,"path":""}) for i in [8,7]]
        account=Account(); choices=tool.list_choices(self.db,account)["playlists"]
        self.assertEqual(choices[0]["kind"],"account")
        output=self.root/"account.zip"
        result=tool.export(self.db,"netease:90",output,account=account,selection=choices[0])
        self.assertEqual((result["tracks"],result["audioTracks"],result["onlineTracks"]),(2,1,1))
        with zipfile.ZipFile(output) as archive:
            data=json.loads(archive.read('manifest.json'))
            self.assertEqual([x['neteaseId'] for x in data['tracks']],[8,7])
            self.assertTrue(data['tracks'][0]['onlineOnly'])
            self.assertNotIn('fileName',data['tracks'][0])
            self.assertEqual(archive.read(data['tracks'][1]['fileName']),b'original-a')
            self.assertNotIn(str(self.root),json.dumps(data))
    def test_cancelled_export_keeps_existing_output_and_cleans_staging(self):
        with sqlite3.connect(self.db) as db:
            db.executescript("CREATE TABLE Playlist(id INTEGER,isLocal INTEGER,json TEXT); CREATE TABLE Track(id INTEGER,json TEXT)")
            db.execute("INSERT INTO Playlist VALUES(1,1,?)",(json.dumps({"name":"Legacy","trackIds":[1]}),))
            db.execute("INSERT INTO Track VALUES(1,?)",(json.dumps({"name":"one","filePath":str(self.a)}),))
        db.close(); output=self.root/'saved.zip'; output.write_bytes(b'previous export')
        stopped=[False]
        def progress(*args): stopped[0]=True
        with self.assertRaises(tool.Cancelled):
            tool.export(self.db,'1',output,overwrite=True,progress=progress,cancel=lambda:stopped[0])
        self.assertEqual(output.read_bytes(),b'previous export')
        self.assertEqual(list(self.root.glob('*.tmp')),[])
    def test_desktop_account_rejects_non_loopback_before_reading_credentials(self):
        with self.assertRaises(ValueError): tool.DesktopAccount(self.db,'https://example.com')

if __name__ == "__main__": unittest.main()

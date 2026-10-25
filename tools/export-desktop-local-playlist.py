"""Read-only VutronMusic local-playlist exporter. Python 3.10+, standard library only."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import shutil
import zipfile
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from desktop_account import DesktopAccount, check_cancel, normalize_track, Cancelled

GENERATOR = "vutron-desktop-export"
MAX_AUDIO_BYTES = 2 * 1024 ** 3

def columns(db: sqlite3.Connection, table: str) -> set[str]:
    if table not in {"Playlist", "Track", "Audio"}: raise ValueError("Unsupported table")
    return {row[1] for row in db.execute(f'PRAGMA table_info("{table}")')}

def playlists(db: sqlite3.Connection) -> tuple[str, list[dict]]:
    fields = columns(db, "Playlist")
    if {"json", "isLocal"} <= fields:
        values = []
        for ident, raw in db.execute('SELECT id, json FROM Playlist WHERE isLocal != 0 ORDER BY id'):
            item = json.loads(raw)
            values.append({"id": str(ident), "name": item.get("name", "Local playlist"), "description": item.get("description", ""), "trackIds": item.get("trackIds", [])})
        return "legacy-json", values
    if {"pluginId", "name"} <= fields:
        return "local-plugin", [dict(row) for row in db.execute("SELECT id, name, description FROM Playlist WHERE pluginId='local' ORDER BY name,id")]
    raise ValueError("Unsupported desktop database: expected VutronMusic Playlist tables")

def track_rows(db: sqlite3.Connection, schema: str, playlist: dict):
    if schema == "legacy-json":
        for ident in playlist["trackIds"]:
            row = db.execute('SELECT json FROM Track WHERE id=?', (ident,)).fetchone()
            if row is None:
                yield str(ident), None
                continue
            data = json.loads(row[0])
            yield str(ident), {"path": data.get("filePath", ""), "name": data.get("name", ""),
                "artist": " / ".join(a.get("name", "") for a in data.get("artists", []) if isinstance(a, dict) and a.get("name")),
                "album": (data.get("album") or {}).get("name", ""), "duration": int(float(data.get("dt", data.get("duration", 0)) or 0)),
                "neteaseId": int(ident) if data.get("matched") and str(ident).isdigit() else 0, "picUrl": data.get("picUrl", "")}
        return
    # This adapter interprets only the local plugin's sourceContext.id contract.
    entries = db.execute("SELECT sourceContext FROM PlaylistEntry WHERE playlistId=? AND pluginId='local' ORDER BY position DESC,id DESC", (playlist["id"],))
    for entry in entries:
        context = json.loads(entry[0]); ident = str(context.get("id", ""))
        row = db.execute('SELECT id,name,duration,albumId FROM Track WHERE id=? AND deleted=0', (ident,)).fetchone()
        if row is None:
            yield ident, None
            continue
        audio = next((item for item in db.execute('SELECT filePath FROM Audio WHERE trackId=? AND deleted=0 ORDER BY bitrate DESC,size DESC,id', (ident,)) if Path(item[0]).is_file()), None)
        artists = [item[0] for item in db.execute('SELECT a.name FROM TrackArtist ta JOIN Artist a ON a.id=ta.artistId WHERE ta.trackId=? ORDER BY ta.rowid', (ident,))]
        album = db.execute('SELECT name FROM Album WHERE id=?', (row["albumId"],)).fetchone()
        remote = db.execute("SELECT json_extract(sourceContext,'$.id') FROM TrackSource WHERE trackId=? AND pluginId='netease' AND matched=1", (ident,)).fetchone()
        yield ident, {"path": audio[0] if audio else "", "name": row["name"], "duration": row["duration"], "artist": " / ".join(artists), "album": album[0] if album else "", "neteaseId": int(remote[0]) if remote and str(remote[0]).isdigit() else 0}

def default_database() -> Path:
    base = Path(os.environ.get("APPDATA", "")) / "VutronMusic" / "api_cache"
    return next((base / name for name in ["vutron_music.sqlite", "db.sqlite"] if (base / name).is_file()), base / "vutron_music.sqlite")


def list_choices(db_path: Path, account: DesktopAccount | None = None) -> dict:
    if not db_path.is_file(): raise ValueError("Desktop database not found; pass --db")
    db = sqlite3.connect(db_path.resolve().as_uri() + "?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    try:
        schema, choices = playlists(db)
        for item in choices: item["kind"] = "local"
        if schema == "legacy-json":
            for ident, raw in db.execute('SELECT id,json FROM Playlist WHERE isLocal=0 ORDER BY id'):
                data = json.loads(raw)
                choices.append({"id": "netease:" + str(ident), "remoteId": str(ident), "kind": "account-cache",
                    "name": data.get("name", "网易云歌单"), "description": data.get("description") or "", "trackIds": data.get("trackIds", [])})
        if account:
            remote = account.playlists()
            remote_ids = {item["id"] for item in remote}
            choices = [item for item in choices if item["id"] not in remote_ids] + remote
        return {"schema": schema, "playlists": choices}
    finally: db.close()


def cached_remote_path(db, schema, ident):
    if schema == "legacy-json":
        row = db.execute('SELECT json FROM Track WHERE id=?', (ident,)).fetchone()
        if row:
            data = json.loads(row[0])
            # Local row IDs alone are not proof of an online identity.
            if data.get("matched") and Path(data.get("filePath") or "__missing__").is_file():
                return data["filePath"]
        return ""
    query = """SELECT a.filePath FROM TrackSource s JOIN Audio a ON a.trackId=s.trackId
        WHERE s.pluginId='netease' AND CAST(json_extract(s.sourceContext,'$.id') AS TEXT)=?
        AND s.matched=1 AND a.deleted=0 ORDER BY a.bitrate DESC,a.size DESC"""
    return next((r[0] for r in db.execute(query, (ident,)) if Path(r[0]).is_file()), "")


def export(db_path: Path, playlist_id: str, output: Path, overwrite: bool = False,
           account: DesktopAccount | None = None, selection: dict | None = None,
           include_online: bool = True, progress=None, cancel=None) -> dict:
    if not db_path.is_file(): raise ValueError("Desktop database not found; pass --db with its path")
    db = sqlite3.connect(db_path.resolve().as_uri() + "?mode=ro", uri=True); db.row_factory = sqlite3.Row
    try:
        db.execute('PRAGMA query_only=ON'); db.execute('BEGIN')
        schema, choices = playlists(db)
        selected = selection or next((item for item in choices if str(item["id"]) == str(playlist_id)), None)
        if selected is None and str(playlist_id).startswith("netease:"):
            selected = next((item for item in list_choices(db_path, account)["playlists"] if str(item["id"]) == playlist_id), None)
        if selected is None: raise ValueError("Playlist not found; refresh the list first")
        remote = selected.get("kind", "local").startswith("account")
        if remote and account:
            rows = account.tracks(selected)
        elif remote:
            rows = []
            for value in selected.get("trackIds", []):
                ident = str(value.get("id")) if isinstance(value, dict) else str(value)
                row = db.execute('SELECT json FROM Track WHERE id=?', (ident,)).fetchone()
                rows.append((ident, normalize_track(json.loads(row[0]), ident) if row else {"name": "网易云歌曲 " + ident, "neteaseId": int(ident), "path": "", "artist": "", "album": "", "duration": 0}))
        else:
            rows = list(track_rows(db, schema, selected))
        tracks, missing, paths = [], [], []
        for position, (ident, item) in enumerate(rows):
            check_cancel(cancel)
            if remote and item: item["path"] = cached_remote_path(db, schema, ident)
            source = Path(item["path"]) if item and item["path"] else None
            if source is None or not source.is_file():
                missing.append(ident)
                if not (remote and include_online): continue
                source = None
            name = f"audio/{position:06d}-{hashlib.sha256(ident.encode()).hexdigest()[:16]}{source.suffix.lower() or '.audio'}" if source else ""
            track = {"sourceTrackId": ident, "name": item["name"] or (source.stem if source else ident),
                "artist": item["artist"], "album": item["album"], "duration": item["duration"]}
            if source: track["fileName"] = name
            else: track["onlineOnly"] = True
            if item.get("neteaseId", 0) > 0: track["neteaseId"] = item["neteaseId"]
            if remote:
                track["entryKey"] = hashlib.sha256(f"{position}:{ident}".encode()).hexdigest()
                track["neteaseId"] = int(ident)
                if item.get("picUrl", "").startswith(("https://", "http://")): track["picUrl"] = item["picUrl"]
            tracks.append(track); paths.append(source)
        if not tracks: raise ValueError("歌单没有可导出的音频；在线歌单可勾选包含未下载歌曲的信息")
        if len(tracks) >= 20000 or sum(p.stat().st_size for p in paths if p) > MAX_AUDIO_BYTES:
            raise ValueError("Playlist exceeds the mobile import limit; split it before exporting")
        if output.suffix.lower() != '.zip' or output.resolve() in {db_path.resolve(), *(p.resolve() for p in paths if p)}:
            raise ValueError("Output must be a separate ZIP, never the database or original audio")
        lyric_assets, skipped_lyrics = [], []
        for track, source in zip(tracks, paths):
            if source is None: continue
            for extension in [".eslrc", ".lrc"]:
                sibling = source.with_suffix(extension)
                if not sibling.is_file(): continue
                if sibling.stat().st_size > 256 * 1024:
                    skipped_lyrics.append(track["sourceTrackId"] + extension); continue
                lyric_assets.append((sibling, Path(track["fileName"]).with_suffix(extension).as_posix()))
        assets = [(source, track["fileName"]) for track, source in zip(tracks, paths) if source] + lyric_assets
        if len(assets) + 1 > 20000: raise ValueError("音频与歌词文件数量超过手机导入限制，请拆分歌单")
        manifest = {"format": 1, "generator": GENERATOR, "name": selected["name"], "description": selected["description"], "tracks": tracks}
        manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8')
        if len(manifest_bytes) > 4 * 1024 ** 2 or len(manifest_bytes) + sum(p.stat().st_size for p, _ in assets) > MAX_AUDIO_BYTES:
            raise ValueError("歌单清单或总内容超过手机导入限制，请拆分后导出")
        output.parent.mkdir(parents=True, exist_ok=True)
        if output.exists() and not overwrite: raise FileExistsError("Output exists; choose another path or explicitly pass --overwrite")
        handle, staged_name = tempfile.mkstemp(prefix=output.name + '.', suffix='.tmp', dir=output.parent)
        os.close(handle)
        staged = Path(staged_name); created = False
        try:
            with zipfile.ZipFile(staged, "w", compression=zipfile.ZIP_STORED) as archive:
                archive.writestr("manifest.json", manifest_bytes)
                total = sum(p.stat().st_size for p, _ in assets); done = 0
                for source, archive_name in assets:
                    check_cancel(cancel)
                    with source.open('rb') as stream, archive.open(archive_name, 'w', force_zip64=True) as target:
                        while chunk := stream.read(1024 * 1024):
                            check_cancel(cancel); target.write(chunk); done += len(chunk)
                            if progress: progress(done, total, source.name)
                check_cancel(cancel)
            if overwrite:
                os.replace(staged, output)
            else:
                try: os.link(staged, output)
                except FileExistsError: raise
                except OSError:
                    with output.open('xb') as destination:
                        created = True
                        with staged.open('rb') as source: shutil.copyfileobj(source, destination)
        except Exception:
            if created and output.exists(): output.unlink()
            raise
        finally:
            staged.unlink(missing_ok=True)
        return {"output": str(output.resolve()), "name": selected["name"], "tracks": len(tracks), "audioTracks": sum(p is not None for p in paths), "onlineTracks": sum(p is None for p in paths), "lyricFiles": len(lyric_assets), "skippedLyrics": skipped_lyrics, "missing": missing, "schema": schema}
    finally: db.close()

def main() -> None:
    default = default_database()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=default)
    parser.add_argument("--account", action="store_true", help="Also read playlists from the signed-in desktop account (desktop must be running)")
    parser.add_argument("--audio-only", action="store_true", help="Skip online songs without accessible local audio")
    parser.add_argument("--list", action="store_true", help="List local playlists without exporting")
    parser.add_argument("--playlist-id", help="ID from --list; current string/UUID and legacy numeric IDs supported")
    parser.add_argument("--output", type=Path); parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()
    try:
        account = DesktopAccount(args.db) if args.account else None
        if args.list or args.playlist_id is None:
            if not args.db.is_file(): raise ValueError("Desktop database not found; pass --db")
            print(json.dumps(list_choices(args.db, account), ensure_ascii=False, indent=2))
        else:
            if args.output is None: parser.error("--output is required when exporting")
            print(json.dumps(export(args.db, args.playlist_id, args.output, args.overwrite, account=account, include_online=not args.audio_only), ensure_ascii=False, indent=2))
    except (ValueError, OSError, sqlite3.Error) as error: raise SystemExit(str(error)) from error

if __name__ == "__main__": main()

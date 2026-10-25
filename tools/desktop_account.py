"""Read the desktop account through its loopback API; never serialize credentials."""
from __future__ import annotations
import json
from pathlib import Path
import sqlite3
import sys
import urllib.error
import urllib.parse
import urllib.request


class Cancelled(Exception):
    pass


def check_cancel(cancel=None):
    if cancel and cancel():
        raise Cancelled("操作已取消")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("桌面 API 不允许重定向")


def session(db: sqlite3.Connection, data_dir: Path) -> tuple[str, str]:
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "PluginData" in tables:
        row = db.execute("SELECT json FROM PluginData WHERE pluginId='netease' LIMIT 1").fetchone()
        if row:
            data = json.loads(row[0])
            return str(data.get("userId", "")), str(data.get("cookie", ""))
    uid = ""
    if "AccountData" in tables:
        row = db.execute("SELECT json FROM AccountData ORDER BY updatedAt DESC LIMIT 1").fetchone()
        if row:
            uid = str(json.loads(row[0]).get("userId", ""))
    # Legacy Electron stores its session in Chromium LevelDB. This optional,
    # maintained parser reads live records, including deletion/sequence numbers.
    try:
        from dfindexeddb.leveldb import definitions, record
    except ImportError:
        legacy_dependencies = Path(__file__).resolve().parents[1] / "android" / "_tools" / "pydeps"
        if legacy_dependencies.is_dir(): sys.path.append(str(legacy_dependencies))
        try:
            from dfindexeddb.leveldb import definitions, record
        except ImportError:
            return uid, ""
    storage = data_dir / "Local Storage" / "leveldb"
    if not storage.is_dir():
        return uid, ""
    allowed = {"MUSIC_U", "__csrf", "MUSIC_R_U", "NMTID", "__remember_me"}
    current = {}

    def decode(raw):
        if not raw:
            return ""
        if raw[0] == 1:
            return raw[1:].decode("latin-1")
        if raw[0] == 0:
            return raw[1:].decode("utf-16-le")
        return ""

    for item in record.FolderReader(storage).GetRecords(use_manifest=True):
        if item.recovered:
            continue
        row = item.record
        origin, _, raw_key = bytes(row.key).partition(b"\0")
        if origin not in {b"_http://localhost:41830", b"_http://127.0.0.1:41830"}:
            continue
        key = decode(raw_key)
        if not key.startswith("cookie-") or key[7:] not in allowed:
            continue
        name = key[7:]
        if name in current and current[name][0] > row.sequence_number:
            continue
        value = decode(bytes(row.value)) if row.record_type == definitions.InternalRecordType.VALUE else ""
        if any(c in value for c in "\r\n;"):
            raise ValueError("桌面 Cookie 格式无效")
        current[name] = row.sequence_number, value
    return uid, "; ".join(name + "=" + value for name, (_, value) in current.items() if value)


class DesktopAccount:
    def __init__(self, db_path: Path, api_base="http://127.0.0.1:41830", cancel=None):
        parsed = urllib.parse.urlsplit(api_base)
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.username or parsed.password or parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
            raise ValueError("桌面 API 必须为本机回环地址")
        self.base = api_base.rstrip("/")
        self.cancel = cancel
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        db = sqlite3.connect(db_path.resolve().as_uri() + "?mode=ro", uri=True)
        try:
            self.uid, self.cookie = session(db, db_path.resolve().parent.parent)
        finally:
            db.close()

    def get(self, path, **params):
        check_cancel(self.cancel)
        headers = {"Accept": "application/json"}
        if self.cookie:
            if "\r" in self.cookie or "\n" in self.cookie:
                raise ValueError("桌面 Cookie 格式无效")
            headers["Cookie"] = self.cookie
        req = urllib.request.Request(self.base + "/netease/" + path + "?" + urllib.parse.urlencode(params), headers=headers)
        try:
            with self.opener.open(req, timeout=12) as response:
                raw = response.read(32 * 1024 * 1024 + 1)
                if len(raw) > 32 * 1024 * 1024:
                    raise ValueError("桌面 API 返回数据过大")
                payload = json.loads(raw)
        except urllib.error.HTTPError as error:
            raise ValueError(f"桌面接口 {path} 返回 HTTP {error.code}；请检查桌面版登录状态") from None
        except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as error:
            raise ValueError(f"桌面接口 {path} 无法访问（{type(error).__name__}），请打开并登录 VutronMusic 桌面版后重试") from None
        check_cancel(self.cancel)
        if payload.get("code", 200) != 200:
            raise ValueError("桌面账号接口读取失败，请检查桌面版登录状态")
        return payload

    def playlists(self):
        status = self.get("login/status")
        profile = status.get("data", status).get("profile") or {}
        self.uid = str(profile.get("userId") or self.uid)
        if not self.uid or self.uid in {"0", "0.0"}:
            raise ValueError("尚未找到桌面账号，请先在桌面版登录")
        choices, seen, offset = [], set(), 0
        while True:
            data = self.get("user/playlist", uid=self.uid, limit=1000, offset=offset)
            rows = data.get("playlist") or []
            added = 0
            for row in rows:
                ident = str(row["id"])
                if ident in seen:
                    continue
                seen.add(ident); added += 1
                choices.append({"id": "netease:" + ident, "remoteId": ident, "name": row.get("name", "网易云歌单"),
                    "description": row.get("description") or "", "kind": "account", "trackCount": row.get("trackCount", 0)})
            offset += len(rows)
            if not data.get("more") or not added:
                break
        return choices

    def tracks(self, playlist):
        data = self.get("playlist/detail", id=playlist["remoteId"]).get("playlist") or {}
        ids = [str(row["id"]) if isinstance(row, dict) else str(row) for row in data.get("trackIds", [])]
        details = {str(row["id"]): row for row in data.get("tracks", [])}
        for start in range(0, len(ids), 200):
            missing = [ident for ident in ids[start:start + 200] if ident not in details]
            if missing:
                details.update({str(row["id"]): row for row in self.get("song/detail", ids=",".join(missing)).get("songs", [])})
        return [(ident, normalize_track(details.get(ident, {"name": "不可用歌曲 " + ident}), ident)) for ident in ids]


def normalize_track(data, ident):
    artists = data.get("ar", data.get("artists", []))
    album = data.get("al", data.get("album", {})) or {}
    return {"path": data.get("filePath", ""), "name": data.get("name", ""),
        "artist": " / ".join(a.get("name", "") for a in artists if isinstance(a, dict)),
        "album": album.get("name", ""), "duration": int(data.get("dt", data.get("duration", 0)) or 0),
        "neteaseId": int(ident), "picUrl": data.get("picUrl") or album.get("picUrl", "")}

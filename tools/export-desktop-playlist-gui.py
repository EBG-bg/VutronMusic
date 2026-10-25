"""VutronMusic desktop playlist export window (Tkinter)."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

spec = importlib.util.spec_from_file_location("desktop_export", Path(__file__).with_name("export-desktop-local-playlist.py"))
backend = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backend)


class ExportWindow:
    def __init__(self, root):
        self.root = root
        root.title("Vutron Music - 歌单导出工具")
        root.geometry("780x530"); root.minsize(620, 430)
        self.events = queue.Queue(); self.cancel_event = threading.Event()
        self.busy = False; self.closed = False; self.account = None; self.choices = []; self.loaded_database = None
        self.database = tk.StringVar(value=str(backend.default_database()))
        self.account_enabled = tk.BooleanVar(value=True)
        self.include_online = tk.BooleanVar(value=True)
        self.status = tk.StringVar(value="选择数据库后读取歌单；账号歌单需要桌面版保持运行。")
        box = ttk.Frame(root, padding=16); box.pack(fill="both", expand=True)
        ttk.Label(box, text="VutronMusic 桌面歌单", font=("Microsoft YaHei UI", 13, "bold")).pack(anchor="w")
        path = ttk.Frame(box); path.pack(fill="x", pady=(12, 8))
        ttk.Entry(path, textvariable=self.database).pack(side="left", fill="x", expand=True)
        self.browse = ttk.Button(path, text="选择数据库…", command=self.pick_database); self.browse.pack(side="left", padx=(8, 0))
        options = ttk.Frame(box); options.pack(fill="x")
        self.account_toggle = ttk.Checkbutton(options, text="包含桌面已登录账号的网易云歌单", variable=self.account_enabled); self.account_toggle.pack(side="left")
        self.refresh = ttk.Button(options, text="读取 / 刷新歌单", command=self.load); self.refresh.pack(side="right")
        table = ttk.Frame(box); table.pack(fill="both", expand=True, pady=12)
        self.list = ttk.Treeview(table, columns=("kind", "count"), show="tree headings", selectmode="browse")
        self.list.heading("#0", text="歌单名称"); self.list.heading("kind", text="来源"); self.list.heading("count", text="歌曲数")
        self.list.column("#0", width=400); self.list.column("kind", width=110, stretch=False); self.list.column("count", width=70, stretch=False)
        scroll = ttk.Scrollbar(table, orient="vertical", command=self.list.yview)
        self.list.configure(yscrollcommand=scroll.set); scroll.pack(side="right", fill="y"); self.list.pack(fill="both", expand=True)
        self.online_toggle = ttk.Checkbutton(box, text="包含未下载歌曲的信息（手机联网登录后播放，不在电脑下载音频）", variable=self.include_online)
        self.online_toggle.pack(anchor="w")
        ttk.Label(box, text="已有本地音频按原顺序复制；账号 Cookie、设置和绝对路径不会进入导出包。", wraplength=700).pack(anchor="w", pady=(5, 8))
        self.progress = ttk.Progressbar(box, maximum=100); self.progress.pack(fill="x")
        ttk.Label(box, textvariable=self.status, wraplength=710).pack(anchor="w", pady=8)
        actions = ttk.Frame(box); actions.pack(fill="x")
        self.cancel_button = ttk.Button(actions, text="取消当前操作", command=self.cancel, state="disabled"); self.cancel_button.pack(side="left")
        self.export_button = ttk.Button(actions, text="导出选中歌单…", command=self.export); self.export_button.pack(side="right")
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.poll_id = root.after(80, self.poll)

    def pick_database(self):
        value = filedialog.askopenfilename(title="选择 VutronMusic 数据库", filetypes=[("SQLite 数据库", "*.sqlite *.db"), ("所有文件", "*")])
        if value: self.database.set(value)

    def run(self, operation):
        if self.busy: return
        self.busy = True; self.cancel_event.clear(); self.progress["value"] = 0
        for control in [self.browse, self.refresh, self.export_button, self.account_toggle, self.online_toggle]: control.configure(state="disabled")
        self.cancel_button.configure(state="normal")
        def worker():
            try: self.events.put(("result", operation()))
            except backend.Cancelled: self.events.put(("cancelled", None))
            except Exception as error: self.events.put(("error", str(error)))
            finally: self.events.put(("done", None))
        threading.Thread(target=worker, daemon=True).start()

    def load(self):
        path = Path(self.database.get()); include = self.account_enabled.get()
        self.status.set("正在读取歌单…")
        def operation():
            account, warning = None, ""
            if include:
                try:
                    account = backend.DesktopAccount(path, cancel=self.cancel_event.is_set)
                    result = backend.list_choices(path, account)
                except backend.Cancelled: raise
                except Exception as error:
                    warning = str(error); account = None; result = backend.list_choices(path)
            else: result = backend.list_choices(path)
            backend.check_cancel(self.cancel_event.is_set)
            return {"type": "list", "choices": result["playlists"], "account": account, "warning": warning,
                "sessionLimited": include and account is not None and not bool(account.cookie), "database": path.resolve()}
        self.run(operation)

    def export(self):
        if self.loaded_database != Path(self.database.get()).resolve():
            self.status.set("数据库位置已变化，请先读取 / 刷新歌单。"); return
        selected = self.list.selection()
        if not selected:
            self.status.set("请先读取歌单，再选择一个歌单。"); return
        item = self.choices[int(selected[0])]
        filename = "".join(c if c not in '<>:"/\\|?*' else "_" for c in item["name"]).rstrip(". ") or "playlist"
        output = filedialog.asksaveasfilename(title="保存歌单包", initialfile=filename + ".zip", defaultextension=".zip", filetypes=[("歌单 ZIP", "*.zip")])
        if not output: return
        path = Path(self.database.get()); include = self.include_online.get(); account = self.account
        self.status.set("正在准备歌曲和本地音频…")
        def progress(done, total, name): self.events.put(("progress", (done, total, name)))
        self.run(lambda: {"type": "export", "data": backend.export(path, str(item["id"]), Path(output),
            overwrite=True, account=account if item["kind"] == "account" else None, selection=item,
            include_online=include, progress=progress, cancel=self.cancel_event.is_set)})

    def cancel(self):
        self.cancel_event.set(); self.status.set("正在取消；网络请求最迟在超时后结束，未完成的导出包会清理。")

    def poll(self):
        if self.closed: return
        for _ in range(100):
            try: kind, value = self.events.get_nowait()
            except queue.Empty: break
            if kind == "progress":
                done, total, name = value; self.progress["value"] = done * 100 / max(total, 1)
                self.status.set(f"正在复制：{name} · {done // 1048576} / {total // 1048576} MiB")
            elif kind == "result":
                if value["type"] == "list":
                    self.loaded_database = value["database"]
                    self.account = value["account"]; self.choices = value["choices"]
                    self.list.delete(*self.list.get_children())
                    for index, item in enumerate(self.choices):
                        kind_label = {"local": "本地", "account": "网易云账号", "account-cache": "已保存在线歌单"}[item["kind"]]
                        self.list.insert("", "end", iid=str(index), text=item["name"], values=(kind_label, item.get("trackCount", len(item.get("trackIds", []))) or "—"))
                    self.status.set(f"已读取 {len(self.choices)} 个歌单。" + ("账号读取失败：" + value["warning"] if value["warning"] else "")
                        + ("旧桌面登录态未读取，私密歌单可能缺失；安装 tools/requirements-export.txt 后重试。" if value["sessionLimited"] else ""))
                else:
                    data = value["data"]; self.progress["value"] = 100
                    self.status.set(f"已导出 {data['tracks']} 首：本地音频 {data['audioTracks']} 首，在线资料 {data['onlineTracks']} 首，同名歌词 {data.get('lyricFiles', 0)} 份。保存至 {data['output']}" + (f"；{len(data['skippedLyrics'])} 份歌词超过 256 KiB，未复制" if data.get('skippedLyrics') else ""))
            elif kind == "cancelled": self.status.set("已取消，原始文件和已有导出包均保留。")
            elif kind == "error": self.status.set("操作失败：" + value)
            elif kind == "done":
                self.busy = False; self.cancel_button.configure(state="disabled")
                for control in [self.browse, self.refresh, self.export_button, self.account_toggle, self.online_toggle]: control.configure(state="normal")
        self.poll_id = self.root.after(80, self.poll)

    def close(self):
        if self.busy:
            if not messagebox.askokcancel("正在处理", "取消当前操作并在清理完成后关闭？"): return
            self.cancel(); self.root.after(100, self.close_when_idle); return
        self.closed = True; self.root.after_cancel(self.poll_id); self.root.destroy()

    def close_when_idle(self):
        if self.busy: self.root.after(100, self.close_when_idle)
        else: self.close()


if __name__ == "__main__":
    root = tk.Tk(); window = ExportWindow(root); root.mainloop()

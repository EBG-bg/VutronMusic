import importlib.util
from pathlib import Path
import tempfile
import time
import tkinter as tk
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('desktop_gui',ROOT/'tools/export-desktop-playlist-gui.py')
gui=importlib.util.module_from_spec(spec); spec.loader.exec_module(gui)

class ExportGuiTest(unittest.TestCase):
    def setUp(self):
        self.root=tk.Tk(); self.root.withdraw(); self.window=gui.ExportWindow(self.root)
        self.addCleanup(self.window.close)
    def drain(self):
        deadline=time.monotonic()+5
        while self.window.busy and time.monotonic()<deadline:
            self.root.update(); time.sleep(.02)
        self.assertFalse(self.window.busy)
    def test_account_and_local_choices_are_selectable_in_actual_window(self):
        choices=[{'id':'local','name':'本地歌单','kind':'local'}, {'id':'netease:1','name':'账号歌单','kind':'account','trackCount':40}]
        with patch.object(gui.backend,'DesktopAccount',return_value=type('A',(),{'cookie':'secret'})()), patch.object(gui.backend,'list_choices',return_value={'playlists':choices}):
            self.window.load(); self.drain()
        self.assertEqual(len(self.window.list.get_children()),2)
        self.assertEqual(self.window.list.item('1')['values'],['网易云账号',40])
        self.assertNotIn('secret',self.window.status.get())
    def test_cancel_keeps_window_responsive_and_reenables_controls(self):
        def work():
            while not self.window.cancel_event.is_set(): time.sleep(.01)
            raise gui.backend.Cancelled()
        self.window.run(work); self.window.cancel(); self.drain()
        self.assertIn('已取消',self.window.status.get())
        self.assertEqual(str(self.window.export_button['state']),'normal')

if __name__=='__main__': unittest.main()

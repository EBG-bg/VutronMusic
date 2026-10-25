@echo off
where py >nul 2>nul
if errorlevel 1 goto python
py -3 "%~dp0export-desktop-playlist-gui.py" %*
goto done
:python
python "%~dp0export-desktop-playlist-gui.py" %*
:done
if errorlevel 1 pause

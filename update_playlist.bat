@echo off
chcp 65001 >nul
title IPTV Playlist & Excel Auto-Sync
echo ========================================================
echo   IPTV PLAYLIST & EXCEL AUTO-SYNC (DE & RU)
echo ========================================================
echo.
echo 1. Lade Live-Streams & pruefe neue Kategorien...
python "%~dp0scripts\build_multistream_excel.py"
echo.
echo ========================================================
echo   FERTIG! Playlists und Excel wurden aktualisiert.
echo ========================================================
pause

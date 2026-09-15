@echo off
title Schlafzimmer TV - Scrcpy
echo ===================================================
echo Verbinde mit Schlafzimmer TV (Chromecast HD)...
echo ===================================================
adb connect 192.168.0.26:44313
adb connect 192.168.0.26:5555
scrcpy -s adb-42281HFGN7AXTP-vOcnb4._adb-tls-connect._tcp --window-title "Schlafzimmer TV" --max-size 1920 --video-bit-rate 12M --max-fps 60 --stay-awake
if %ERRORLEVEL% NEQ 0 (
    scrcpy -s 192.168.0.26:44313 --window-title "Schlafzimmer TV" --max-size 1920 --video-bit-rate 12M --max-fps 60 --stay-awake
)
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Verbindungsfehler! Druecke eine Taste zum Beenden...
    pause
)

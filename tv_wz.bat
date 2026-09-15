@echo off
title Wohnzimmer TV - Scrcpy
echo ===================================================
echo Verbinde mit Wohnzimmer TV (192.168.0.237:44045)...
echo ===================================================
adb connect 192.168.0.237:44045
scrcpy -s 192.168.0.237:44045 --window-title "Wohnzimmer TV" --max-size 1920 --video-bit-rate 12M --max-fps 60 --stay-awake
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Verbindungsfehler! Druecke eine Taste zum Beenden...
    pause
)

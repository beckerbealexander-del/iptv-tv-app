@echo off
title Wohnzimmer TV - Scrcpy
echo ===================================================
set PORT=45359
set /p USER_PORT="Port eingeben [Enter fuer 45359]: "
if not "%USER_PORT%"=="" set PORT=%USER_PORT%

echo.
echo Verbinde mit 192.168.0.237:%PORT% ...
adb connect 192.168.0.237:%PORT%
scrcpy -s 192.168.0.237:%PORT% --no-control --window-title "Wohnzimmer TV (Nur Bild)" --max-size 1920 --video-bit-rate 12M --max-fps 60
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Verbindungsfehler! Druecke eine Taste zum Beenden...
    pause
)

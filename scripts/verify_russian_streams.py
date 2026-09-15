import urllib.request
import subprocess
import os
import sys
import json
import time

sys.stdout.reconfigure(encoding='utf-8')

FFMPEG = r"C:\Users\becke\AppData\Local\Microsoft\WinGet\Links\ffmpeg.exe"
FFPROBE = r"C:\Users\becke\AppData\Local\Microsoft\WinGet\Links\ffprobe.exe"

TEMP_DIR = r"C:\Users\becke\.gemini\antigravity\scratch\iptv-tv-app\scratch\stream_check"
os.makedirs(TEMP_DIR, exist_ok=True)

HEADERS = {
    'User-Agent': 'VLC/3.0.18 (Linux; Android 11; TV) ExoPlayerLib/2.18.2',
    'Connection': 'close'
}

test_groups = {
    'PERVIY KANAL': [
        (439653, 'RU: PERVIY KANAL HD'),
        (439547, 'RU: 1 HD'),
        (439651, 'RU: CHANNEL ONE')
    ],
    'NTV / HTB': [
        (439632, 'RU: NTV HD'),
        (439630, 'RU: HTB')
    ],
    'TNT / THT': [
        (439624, 'RU: TNT HD'),
        (439623, 'RU: THT')
    ],
    'REN TV / PEH TB': [
        (439629, 'RU: REN TV'),
        (439628, 'RU: PEH TB')
    ],
    '5 KANAL': [
        (439425, 'RU: PYATIT KANAL'),
        (439375, 'RU: 5 KANAL RU')
    ],
    'ROSSIYA 1 / RTR PLANETA': [
        (439644, 'RU: ROSSIYA 1 HD'),
        (439642, 'RU: RTR PLANETA')
    ]
}

def check_stream(stream_id, label):
    url = f"http://cf.rilox.sbs/live/fb5940d0a3a0/b1d99e5206/{stream_id}.ts"
    ts_file = os.path.join(TEMP_DIR, f"{stream_id}.ts")
    jpg_file = os.path.join(TEMP_DIR, f"{stream_id}.jpg")

    # 1. Download ~1.5 MB chunk (approx. 2-3 seconds of video)
    print(f"  -> Lade Sample von Stream {stream_id} ({label})...")
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = resp.read(1500000)
            with open(ts_file, 'wb') as f:
                f.write(data)
    except Exception as e:
        print(f"     [FEHLER beim Download]: {e}")
        return None

    # 2. ffprobe metadata (Service Name, Resolution, Codec, Bitrate)
    probe_cmd = [
        FFPROBE, '-v', 'quiet', '-print_format', 'json',
        '-show_format', '-show_streams', ts_file
    ]
    meta = {}
    try:
        res = subprocess.run(probe_cmd, capture_output=True, text=True, timeout=10)
        if res.returncode == 0:
            p_data = json.loads(res.stdout)
            format_info = p_data.get('format', {})
            tags = format_info.get('tags', {})
            service_name = tags.get('service_name', '')
            service_provider = tags.get('service_provider', '')
            
            # Video Stream Info
            v_stream = next((s for s in p_data.get('streams', []) if s.get('codec_type') == 'video'), {})
            width = v_stream.get('width', 0)
            height = v_stream.get('height', 0)
            codec = v_stream.get('codec_name', '')
            fps = v_stream.get('r_frame_rate', '')
            
            meta = {
                'service_name': service_name,
                'service_provider': service_provider,
                'resolution': f"{width}x{height}",
                'codec': codec,
                'fps': fps
            }
    except Exception as e:
        print(f"     [FEHLER bei ffprobe]: {e}")

    # 3. Extract 1 frame as jpg for visual check
    frame_cmd = [
        FFMPEG, '-y', '-i', ts_file, '-ss', '00:00:00.5',
        '-vframes', '1', '-q:v', '2', jpg_file
    ]
    try:
        subprocess.run(frame_cmd, capture_output=True, timeout=10)
        meta['has_frame'] = os.path.exists(jpg_file)
        meta['frame_path'] = jpg_file
    except Exception:
        pass

    # Pause 0.5s to close session cleanly on server
    time.sleep(0.5)
    return meta

def main():
    print("=== LIVE-PRÜFUNG DER RUSSISCHEN STREAMS ===")
    results = {}
    for group_name, streams in test_groups.items():
        print(f"\n[GRUPPE: {group_name}]")
        results[group_name] = []
        for sid, label in streams:
            info = check_stream(sid, label)
            if info:
                print(f"     Stream-ID {sid}:")
                print(f"       - Auflösung:    {info.get('resolution')} ({info.get('codec')})")
                if info.get('service_name'):
                    print(f"       - Service Name: {info.get('service_name')}")
                print(f"       - Frame erzeugt: {info.get('has_frame')}")
                results[group_name].append((sid, label, info))

    print("\n=== ZUSAMMENFASSUNG DER PRÜFUNG ===")
    for group_name, items in results.items():
        print(f"\nGruppe {group_name}:")
        for sid, label, info in items:
            print(f"  - {sid} ({label}): Res={info.get('resolution')}, SvcName='{info.get('service_name')}'")

if __name__ == "__main__":
    main()

import openpyxl
import sys
import re

sys.stdout.reconfigure(encoding='utf-8')

PROJECT_ROOT = r"C:\Users\becke\.gemini\antigravity\scratch\iptv-tv-app"
XLSX_PATH = rf"{PROJECT_ROOT}\live_tv_categories_export.xlsx"

wb = openpyxl.load_workbook(XLSX_PATH)

def get_sort_key_freetv(clean_name):
    u = clean_name.upper().strip()
    lcn_top = [
        'DAS ERSTE', 'ZDF', 'RTL', 'SAT.1', 'PROSIEBEN', 'VOX', 'KABEL EINS', 'RTL ZWEI',
        '3SAT', 'ARTE', 'NITRO', 'DMAX', 'SIXX', 'SAT.1 GOLD', 'PROSIEBEN MAXX', 'VOXUP', 'VOX UP',
        'RTL UP', 'KABEL EINS DOKU', 'TELE 5', 'SERVUS TV', 'DF1', 'PROSIEBEN FUN', 'SAT.1 EMOTIONS',
        'KABEL EINS CLASSICS', 'NTV', 'WELT', 'PHOENIX', 'ZDFINFO', 'TAGESSCHAU24', 'ARD-ALPHA',
        'EURONEWS', 'ZDFNEO', 'ONE', 'KIKA', 'RTL SUPER', 'TOGGO PLUS', 'DISNEY CHANNEL', 'NICKELODEON',
        'RIC', 'EUROSPORT 1', 'SPORT1', 'REDBULLTV', 'MTV', 'DELUXE MUSIC'
    ]
    for idx, name in enumerate(lcn_top):
        if u == name:
            return (1, idx, u)

    rtl_plus = [
        'RTL CRIME', 'RTL LIVING', 'RTL PASSION', 'GEO TELEVISION',
        'RTL COMEDY', 'RTL HAUS & GARTEN', 'RTL SHINE', 'NOW',
        'BAUER SUCHT FRAU', 'ALARM FÜR COBRA 11 / BALKO',
        'ALLES WAS ZÄHLT CLASSICS', 'HUNDKATZEMAUS', 'SHOPPING QUEEN'
    ]
    for idx, name in enumerate(rtl_plus):
        if name in u or u in name:
            return (2, idx, u)

    dritte = ['WDR', 'NDR', 'BR FERNSEHEN', 'SWR', 'MDR', 'HR', 'RBB', 'RADIO BREMEN', 'SR ']
    for idx, d in enumerate(dritte):
        if d in u:
            return (3, idx, u)

    if u.startswith('SAT.1 ') or u.startswith('RTL '):
        return (4, 0, u)

    lokal = ['ALLGÄU', 'AUGSBURG', 'MÜNCHEN', 'FRANKEN', 'RHEIN', 'RNF', 'REGIONAL', 'K.TV', '25 K.TV']
    for idx, lk in enumerate(lokal):
        if lk in u:
            return (5, idx, u)

    shopping = ['QVC', 'HSE', 'SONNENKLAR', 'BIBEL', 'SHOP']
    for idx, sh in enumerate(shopping):
        if sh in u:
            return (6, idx, u)

    return (7, 0, u)

# Test FreeTV
ws_free = wb['1. FreeTV']
free_groups = {}
for r in list(ws_free.iter_rows(values_only=True))[1:]:
    clean = str(r[0]).replace('   ↳ ', '').strip()
    if clean:
        free_groups.setdefault(clean, []).append(r)

sorted_free = sorted(free_groups.keys(), key=lambda k: get_sort_key_freetv(k))
print('=== TOP 25 FreeTV Sender (nach Sortierung) ===')
for i, name in enumerate(sorted_free[:25], 1):
    print(f'{i:2}. {name}')

print('\n=== LETZTE 10 FreeTV Sender (Nischen / FAST) ===')
for i, name in enumerate(sorted_free[-10:], len(sorted_free)-9):
    print(f'{i:2}. {name}')

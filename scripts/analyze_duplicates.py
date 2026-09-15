import openpyxl
import sys
import re

sys.stdout.reconfigure(encoding='utf-8')

wb = openpyxl.load_workbook(r"C:\Users\becke\.gemini\antigravity\scratch\iptv-tv-app\live_tv_categories_export.xlsx")

def normalize_key(name):
    if not name or str(name).startswith('---'):
        return None
    k = str(name).upper().strip()
    k = re.sub(r'[^A-Z0-9\+]', '', k)
    return k

for sname in ["1. FreeTV", "3. Sport", "4. Sky", "8. Doku"]:
    ws = wb[sname]
    groups = {}
    for r in list(ws.iter_rows(values_only=True))[1:]:
        subcat, orig, clean = r[:3]
        k = normalize_key(clean)
        if k:
            groups.setdefault(k, []).append((subcat, orig, clean))
    
    print(f"\n=== Duplicates in {sname} ===")
    dup_keys = [k for k, v in groups.items() if len(v) > 1][:6]
    for k in dup_keys:
        items = groups[k]
        print(f"  Sender \"{items[0][2]}\" ({len(items)} Versionen):")
        for subcat, orig, clean in items:
            print(f"    - [{subcat[:25]:25}] {orig}")

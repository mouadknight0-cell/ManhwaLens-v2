"""Offline: export an actual phone report into a NEW directory. Never score here."""
import argparse
import csv
import json
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('report', type=Path)
p.add_argument('new_directory', type=Path)
a = p.parse_args()
base = Path(__file__).resolve().parent
rows = json.loads(a.report.read_text(encoding='utf8'))['rows']
with (base / 'dataset.tsv').open(encoding='utf8') as f:
    gold = list(csv.DictReader(f, delimiter='\t'))
assert len(rows) == len(gold) == 40
for r, g in zip(rows, gold):
    assert (r['id'], r['original_en'], r['reference_ar']) == (g['id'], g['original_en'], g['independent_ar_reference'])
a.new_directory.mkdir(exist_ok=False)
with (base / 'observations.csv').open(encoding='utf8') as f:
    ledger = list(csv.DictReader(f))
flat = []
for r in rows:
    for arm in ('app', 'native_only'):
        d = r.get(arm, {})
        flat.append(dict(id=r['id'], scene=r['scene'], category=r['category'], original_en=r['original_en'], arm=arm, reference_ar=r['reference_ar'], **{k:d.get(k, '') for k in ('prepared_en','masked_en','model_input','route','raw_ar','restored_ar','final_ar','cache')}, status=d.get('status', 'NOT_RUN')))
    app = r.get('app', {})
    line = next(x for x in ledger if x['id']==r['id'] and x['layer']=='clean')
    line.update({k:app[k] for k in ('status','prepared_en','model_input','route','raw_ar','final_ar') if k in app})
for name, data in [('phase3_results.csv',flat), ('observations.csv',ledger)]:
    with (a.new_directory / name).open('w', encoding='utf8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(data[0])); w.writeheader(); w.writerows(data)

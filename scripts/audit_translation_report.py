#!/usr/bin/env python3
"""Local-only retained-sample audit. Findings are review candidates, never accuracy scores.
Output contains screen text: keep it private. No network, no dependencies.
"""
import argparse
import collections
import json
import re
from pathlib import Path


def audit(text, version):
    chunks = re.split(r'^== (.*?) ==\s*$', text, flags=re.M)
    sections = dict(zip(chunks[1::2], chunks[2::2]))
    key = next((k for k in sections if k.startswith('Translation samples JSON')), None)
    if key is None or 'END OF REPORT' not in sections:
        raise ValueError('Missing samples or report terminator; do not treat as complete evidence')
    samples = json.loads(sections[key])
    versions = collections.Counter(row.get('app_version', 'unknown') for row in samples)
    # Samples may be exported more than once. One record per version+sample ID.
    unique = {}
    for i, row in enumerate(samples):
        unique[(row.get('app_version'), row.get('id', f'missing-id-{i}'))] = row
    selected = [row for row in unique.values() if row.get('app_version') == version]
    queue = []
    for row in selected:
        source = row.get('source', '')
        raw = row.get('ocr_source', source)
        target = row.get('translation') or ''
        flags = []
        if row.get('error_code'):
            flags.append('technical_error:' + row['error_code'])
        if re.search(r'\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9]+\b', raw):
            flags.append('review_mixed_alphanumeric_OCR')
        if re.search(r'[A-Za-z]{2,}', target):
            flags.append('review_latin_residue_may_be_name')
        src_numbers = re.findall(r'(?<!\w)\d+(?:[.,:]\d+)*(?!\w)', source)
        dst_numbers = re.findall(r'(?<!\w)\d+(?:[.,:]\d+)*(?!\w)', target)
        if target and collections.Counter(src_numbers) != collections.Counter(dst_numbers):
            flags.append('review_numeric_difference_may_be_spelled_out')
        queue.append({'id': row.get('id'), 'trace': row.get('trace'), 'ocr': raw,
                      'source': source, 'translation': target, 'method': row.get('method'),
                      'flags': flags, 'human_review': 'unreviewed'})
    return {'scope': 'retained samples only; flags are candidates, not semantic verdicts',
            'selected_version': version, 'versions_in_file': dict(versions),
            'unique_selected_samples': len(selected),
            'technical_states': dict(collections.Counter(row.get('error_code') or 'no_technical_error' for row in selected)),
            'semantic_accuracy': None, 'review_queue': queue}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--version', required=True, help='Never aggregate releases together')
    parser.add_argument('--output', required=True, type=Path, help='Private JSON destination (contains screen text)')
    args = parser.parse_args()
    args.output.write_text(json.dumps(audit(args.report.read_text(encoding='utf-8'), args.version), ensure_ascii=False, indent=2), encoding='utf-8')

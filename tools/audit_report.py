"""Read one exported report at a time: exports can contain overlapping journeys."""
import collections
import math
import pathlib
import re
import statistics
import sys


def summarize(path):
    text = pathlib.Path(path).read_text()
    blocks = re.split(r'(?m)^Run \d+: ', text)[1:]
    samples = collections.defaultdict(list)
    for block in blocks:
        if not re.match(r'\d+ \| (ok|partial) \|', block):
            continue
        for stage, value in re.findall(r'(?m)^([A-Za-z /]+): ([\d.]+) ms', block.split('Raw monotonic marks')[0]):
            samples[stage].append(float(value))
    print(path, '(ok/partial only; ms; nearest-rank p90)')
    for stage, values in samples.items():
        print(f'{stage}: n={len(values)} median={statistics.median(values):.1f} p90={sorted(values)[math.ceil(.9*len(values))-1]:.1f}')
    for session in re.findall(r'(?m)^session=.*', text):
        print(session)


if __name__ == '__main__':
    for path in sys.argv[1:]:
        summarize(path)

#!/usr/bin/env python3
"""Export allowlisted metadata and unchanged transcripts; leave machine paths in raw output."""
import argparse
import hashlib
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--run-dir', required=True, type=Path)
parser.add_argument('--output-dir', required=True, type=Path)
parser.add_argument('--expected-error', help='Exact expected baseline assertion text; omit for passing runs')
args = parser.parse_args()
raw = json.loads((args.run_dir / 'result.json').read_text(encoding='utf-8'))
text = (args.run_dir / 'instrumentation.log').read_text(encoding='utf-8')
if args.expected_error:
    if raw['passed'] or args.expected_error not in text:
        parser.error('The expected baseline regression assertion was not observed')
elif not raw['passed']:
    parser.error('Refusing to export a failed run as passing evidence')
if args.output_dir.exists():
    parser.error('Choose a fresh output directory; existing evidence is not overwritten')
keys = ['scenario', 'revision', 'avd', 'device_fingerprint', 'app_apk_sha256',
        'test_apk_sha256', 'passed', 'adb_exit_code']
public = {key: raw[key] for key in keys if key in raw}
public['outcome'] = 'expected_regression_failure' if args.expected_error else 'pass'
public['transcript_sha256'] = {}
transcripts = {}
for name in ['instrumentation.log', 'logcat.log']:
    data = (args.run_dir / name).read_bytes()
    decoded = data.decode('utf-8')
    if re.search(r'/(Users|home)/[^/\s]+|[A-Za-z]:\\Users\\', decoded):
        parser.error(f'Personal host path found in {name}; retain raw evidence and review before publication')
    transcripts[name] = data
    public['transcript_sha256'][name] = hashlib.sha256(data).hexdigest()
args.output_dir.mkdir(parents=True)
for name, data in transcripts.items():
    (args.output_dir / name).write_bytes(data)
(args.output_dir / 'result.json').write_text(json.dumps(public, indent=2) + '\n', encoding='utf-8')
print(f'Exported {public["outcome"]}: {public["revision"]} {public["scenario"]}')

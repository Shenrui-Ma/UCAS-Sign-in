#!/usr/bin/env python3
"""Run real service instrumentation on an explicitly named disposable emulator.

Only Python's standard library and adb are needed. Instrumentation assertions,
not adb's process exit code alone, decide success. No developer-home paths.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def instrumentation_passed(text, scenario, adb_exit_code):
    return (adb_exit_code == 0
            and re.search(r'^INSTRUMENTATION_CODE: -1\s*$', text, re.MULTILINE) is not None
            and re.search(r'\bPASS ' + re.escape(scenario) + r'\s*$', text, re.MULTILINE) is not None
            and 'FAIL ' not in text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd-name', required=True)
    parser.add_argument('--app-apk', required=True, type=Path)
    parser.add_argument('--test-apk', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--revision', default='local-fixed')
    parser.add_argument('--scenario', default='cooldown_restart')
    args = parser.parse_args()
    for path in [args.adb, args.app_apk, args.test_apk]:
        if not path.is_file():
            parser.error(f'Missing file: {path}')
    if not args.serial.startswith('emulator-'):
        parser.error('This destructive fixture runner is limited to disposable emulators')
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output / 'result.json').exists():
        parser.error('Choose a fresh output directory to preserve previous evidence')
    adb = [str(args.adb.resolve()), '-s', args.serial]

    def call(*argv, **kwargs):
        return subprocess.run(adb + list(argv), check=True, **kwargs)

    avd = subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True).splitlines()[0]
    if avd != args.expected_avd_name:
        parser.error(f'Expected AVD {args.expected_avd_name!r}; found {avd!r}')
    call('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    call('shell', 'svc', 'wifi', 'disable')
    call('shell', 'svc', 'data', 'disable')
    fingerprint = subprocess.check_output(adb + ['shell', 'getprop', 'ro.build.fingerprint'], text=True).strip()
    call('install', '-r', str(args.app_apk.resolve()))
    call('install', '-r', str(args.test_apk.resolve()))
    package = 'com.ucas.qingxin.signin'
    call('shell', 'am', 'force-stop', package)
    call('shell', 'pm', 'clear', package)
    call('shell', 'pm', 'grant', package, 'android.permission.POST_NOTIFICATIONS')
    call('shell', 'dumpsys', 'deviceidle', 'whitelist', '+' + package)
    call('logcat', '-c')
    result = {'scenario': args.scenario, 'revision': args.revision, 'avd': avd,
              'device_fingerprint': fingerprint, 'app_apk_sha256': digest(args.app_apk),
              'test_apk_sha256': digest(args.test_apk), 'passed': False}
    command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'scenario', args.scenario,
                     '-e', 'revision', args.revision,
                     package + '.test/' + package + '.attendance.DaemonProofInstrumentation']
    result['command'] = command
    try:
        with (output / 'instrumentation.log').open('w') as stream:
            process = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=150)
        text = (output / 'instrumentation.log').read_text()
        result['adb_exit_code'] = process.returncode
        result['passed'] = instrumentation_passed(text, args.scenario, process.returncode)
        print(text, flush=True)
    except subprocess.TimeoutExpired:
        result['error'] = 'Instrumentation exceeded 150 s; transcript retained'
    finally:
        with (output / 'logcat.log').open('w') as stream:
            call('logcat', '-d', '-s', 'DaemonProof:I', 'AndroidRuntime:E', 'QingxinApp:E', '--format=threadtime', stdout=stream)
        call('shell', 'am', 'force-stop', package)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())

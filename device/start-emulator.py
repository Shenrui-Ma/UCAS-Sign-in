#!/usr/bin/env python3
"""Start a specified disposable AVD; paths are parameters, existing config is read-only."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', required=True, type=Path, help='SDK containing emulator and system image')
parser.add_argument('--adb', required=True, type=Path)
parser.add_argument('--avd-home', required=True, type=Path)
parser.add_argument('--user-home', required=True, type=Path)
parser.add_argument('--name', required=True)
parser.add_argument('--port', required=True, type=int)
parser.add_argument('--create-if-missing', action='store_true')
args = parser.parse_args()
if args.port % 2 or not 5554 <= args.port <= 5682:
    parser.error('Use an even emulator port in 5554..5682')
if not args.name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in args.name):
    parser.error('AVD name must use letters, digits, underscores or hyphens')
emulator = args.sdk.resolve() / 'emulator/emulator'
if not emulator.is_file() or not args.adb.is_file():
    parser.error('Missing emulator or adb executable')
devices = subprocess.check_output([str(args.adb), 'devices'], text=True)
serial = f'emulator-{args.port}'
if any(line.startswith(serial + '\t') for line in devices.splitlines()):
    parser.error(f'{serial} already exists; no emulator was touched')
avd_home = args.avd_home.resolve()
ini = avd_home / (args.name + '.ini')
if not ini.exists():
    if not args.create_if_missing:
        parser.error('AVD missing; create a disposable one or use --create-if-missing')
    avd_dir = avd_home / (args.name + '.avd')
    avd_dir.mkdir(parents=True, exist_ok=False)
    template = (Path(__file__).resolve().parent / 'avd-config.ini').read_text()
    template = template.replace('UCAS_DAEMON_PROOF', args.name)
    (avd_dir / 'config.ini').write_text(template)
    ini.write_text(f'avd.ini.encoding=UTF-8\npath={avd_dir}\ntarget=android-35\n')
properties = dict(tuple(part.strip() for part in line.split('=', 1)) for line in ini.read_text().splitlines() if '=' in line)
avd_dir = Path(properties.get('path', ''))
if not avd_dir.is_dir() or not (avd_dir / 'config.ini').is_file():
    parser.error('AVD config is missing')
config = dict(tuple(part.strip() for part in line.split('=', 1)) for line in (avd_dir / 'config.ini').read_text().splitlines() if '=' in line)
if config.get('avd.id', config.get('AvdId')) != args.name:
    parser.error('AVD identity mismatch')
image = args.sdk.resolve() / config.get('image.sysdir.1', '')
if not image.is_dir():
    parser.error(f'Install the configured Android system image first: {image}')
args.user_home.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env.update(ANDROID_HOME=str(args.sdk.resolve()), ANDROID_SDK_ROOT=str(args.sdk.resolve()),
           ANDROID_AVD_HOME=str(avd_home), ANDROID_USER_HOME=str(args.user_home.resolve()))
argv = [str(emulator), '-avd', args.name, '-no-window', '-no-snapshot', '-no-audio',
        '-gpu', 'swiftshader', '-port', str(args.port), '-memory', '2048', '-no-boot-anim']
print(f'Starting dedicated AVD {args.name} at {serial}; config={avd_dir}', flush=True)
os.execve(emulator, argv, env)

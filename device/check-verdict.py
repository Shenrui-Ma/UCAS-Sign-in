#!/usr/bin/env python3
"""Check adb/instrumentation verdict handling using captured real transcripts."""
import importlib.util
from pathlib import Path
import sys

sys.dont_write_bytecode = True

root = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('proof_runner', root / 'run-device-proof.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
failure = (root / 'parser-fixtures/baseline-failure.log').read_text()
success = (root / 'parser-fixtures/fixed-success.log').read_text()
assert not runner.instrumentation_passed(failure, 'baseline_observe', 0)
assert runner.instrumentation_passed(success, 'receiver', 0)
assert not runner.instrumentation_passed(success, 'receiver', 1)
print('PASS: real failure with adb exit 0 remains failure; real success passes; adb failure remains failure')

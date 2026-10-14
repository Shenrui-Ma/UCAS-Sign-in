# Android daemon regression proof

This optional, external framework instrumentation exercises the real service, alarm receiver, engine, repositories and notifications. It adds no project dependencies and is not integrated into the repository's normal JVM tests or CI.

The comparison uses upstream base `4593162ee1bd2e42f2a8f5b9c49ea9df8312d777` and the local fix on that base. `source-identity.json` records source and APK hashes. This v4 harness passes the real `QingxinApp.signRecords` store to the updated `AttendanceRepository` constructor and checks automatic-sign history after normal and retried success.

## Recorded results

All nine patched scenarios passed. The base passed the first-attempt success control and reproduced both targeted regression failures.

| Scenario | Base | Patched |
| --- | --- | --- |
| Receiver after a transient failure | One request across 96,001 ms, no daemon, course unsigned | Retry after 82,237 ms between requests; success and cleanup |
| Repeated starts during future wait | Service and notification disappeared | Service remained; disabling auto-sign then stopped it |
| Repeated starts during retry cooldown | Not used as a separate baseline assertion | 12 starts; no second submit before 60 s; retry 78,964 ms after failure |
| Earlier course restored | Not used as a baseline assertion | Earlier course signed promptly; later course remained scheduled |
| Success, exhausted budget, deadline, no courses, saver | Success control passed | All controls passed; no over-budget or post-deadline submit |

Representative current-base/current-fix transcript lines:

```text
218812 baseline after96001ms requests=1 serviceRunning=false daemonNotification=false courseSigned=false
304348 retryGapMs=82237 actualSignRequests=2
304349 final serviceRunning=false daemonNotification=false notifications=[5512:自动签到成功]
394823 cooldown retryAfterFailureMs=78964 requestGapMs=79464 actualSignRequests=2 externalStarts=12
394827 production sign history records=2 successRecords=1 source=AUTO
```

Read the [base receiver observation](results/baseline-baseline_observe/instrumentation.log), [patched receiver result](results/patched-receiver/instrumentation.log), and [cooldown result](results/patched-cooldown_restart/instrumentation.log). Each has a paired logcat transcript containing the intercepted production HTTP requests.

## What is real, and what is synthetic

Android delivers the explicit alarm broadcast and repeated service starts. The production engine chooses courses, submits attendance, waits through its unchanged 60–90 second retry delay, writes sign history, posts notifications and stops the service. `ActivityManager` and `NotificationManager` observe real service and notification state.

The session, course and HTTP responses are synthetic. An OkHttp interceptor checks the submitted method, session header, course/user IDs and timestamp; it never forwards requests to a network socket. All fixture URLs use `example.invalid`. A failing connection takes 500 ms; later attempts succeed. The fixture freezes the production target map to deterministic times, without replacing wall clocks, dispatchers, coroutine delays or retry randomization.

The app runs in a disposable Android 15 / API 35 arm64 emulator. Network access is disabled, background work is canceled, notifications are granted and battery optimization is exempted. The budget scenario seeds the production attempt map to its existing cap. Reflection wires production repositories and deterministic fixture state; internal field or constructor changes may require updating this external harness.

The first-attempt success control checks the submit, signed course, success notification, history and absence of a running service. It does not require removal of a separately posted status notification; that existing behavior was observed on both base and fix. The receiver-retry and cooldown scenarios explicitly require the daemon notification to be removed after success.

This establishes behavior for the recorded scenarios. It does not establish real school-server behavior, OEM restrictions, older Android versions, every possible coroutine interleaving, or a global rate limit across all execution paths. The repeated-start cooldown assertion applies to the daemon loop.

## Reproduce

Use an isolated checkout with the desired base/fix. Requirements are JDK 17, Gradle 8.11.1, Python 3, and Android SDK platform/build tools plus an API 35 emulator image. Use an external Gradle 8.11.1 installation: the existing repository wrapper points to `file:///D:/tools/downloads/gradle-8.11.1-bin.zip`.

All paths are supplied by the caller:

```sh
PROOF=/path/to/this/directory
REPO=/path/to/isolated/UCAS-Sign-in
SDK=/path/to/android-sdk
GRADLE=/path/to/gradle-8.11.1/bin/gradle
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME="$SDK"
ADB="$SDK/platform-tools/adb"
OUT=/path/to/fresh-proof-output
mkdir -p "$OUT"
```

In a new checkout without private local configuration, copy `synthetic-local.properties` to `android-app/local.properties`. It has only fixture endpoints and no machine-specific SDK directory.

```sh
cp "$PROOF/synthetic-local.properties" "$REPO/android-app/local.properties"
"$GRADLE" -p "$REPO/android-app" -I "$PROOF/harness.init.gradle" \
  -PdaemonProofSourceRoot="$PROOF/src" \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

The following creates a dedicated arm64/API 35 AVD if its SDK image is installed. On another architecture, supply an appropriate disposable AVD. Existing AVD configuration is read without rewriting it, and an occupied emulator serial is refused.

```sh
python3 "$PROOF/start-emulator.py" --sdk "$SDK" --adb "$ADB" \
  --avd-home "$OUT/avd" --user-home "$OUT/android-user" \
  --name daemon-proof --port 5580 --create-if-missing \
  > "$OUT/emulator.log" 2>&1 &
```

After `adb -s emulator-5580 shell getprop sys.boot_completed` returns `1`, run a scenario:

```sh
python3 "$PROOF/run-device-proof.py" \
  --adb "$ADB" --serial emulator-5580 --expected-avd-name daemon-proof \
  --app-apk "$REPO/android-app/app/build/outputs/apk/debug/app-debug.apk" \
  --test-apk "$REPO/android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
  --output-dir "$OUT/receiver" --revision local-fixed --scenario receiver
```

Recorded patched scenarios are `success`, `receiver`, `restart`, `earlier`, `cooldown_restart`, `budget`, `deadline`, `idle` and `saver`. Use a fresh output directory for each. `restart` checks repeated starts during a future wait; `cooldown_restart` sends 12 starts at approximately 5/15/35/55 seconds after failure and rejects a second submission before 60 seconds. Its upper timing assertion allows three seconds of scheduler overhead; actual measurements are recorded in the transcript.

For the base revision, `success` is a passing control, while `restart` and `baseline_observe` are expected regression failures. `baseline_observe` keeps the receiver fixture active for 96 seconds. The runner returns nonzero when assertions fail even if adb itself returns zero. `check-verdict.py` checks that distinction using captured current-base/current-fix transcripts.

Raw result metadata includes the local command. To prepare shareable evidence, export only allowlisted metadata and unchanged transcripts; the exporter refuses personal host paths in transcripts:

```sh
python3 "$PROOF/export-public-evidence.py" \
  --run-dir "$OUT/receiver" --output-dir "$OUT/public-receiver"
```

An expected baseline failure additionally requires `--expected-error` with its actual assertion message, so an unrelated harness failure cannot be mislabeled as a reproduced regression. The raw original remains intact. After recording results, stop only the dedicated emulator with `"$ADB" -s emulator-5580 emu kill`.

## Evidence layout

- `results/`: current-base and current-fix assertion streams, paired HTTP logcat transcripts, and allowlisted result metadata.
- `summary.json`: scenario outcomes and measured timings.
- `source-identity.json`: upstream revision, source hashes and exact APK identities.
- `src/`, scripts, init file and synthetic config: reproducible external harness sources.
- `parser-fixtures/`: captured assertion transcripts used to verify runner failure handling.

No APKs, emulator state, caches, account credentials, personal machine paths or harness-debugging logs are included in this public folder. Fixture identifiers in the source and request transcripts are synthetic.

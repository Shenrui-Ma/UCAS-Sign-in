# Build and unit test validation

Base: `4593162ee1bd2e42f2a8f5b9c49ea9df8312d777` (1.3.5). The patch changes two attendance lifecycle files and adds six policy regression tests. Build files, dependencies, SDK levels and signing configuration are unchanged.

The checks used Gradle 8.11.1, JDK 17 compilation and Android SDK 35 on macOS arm64. Builds use synthetic `example.invalid` endpoints. Release APKs are unsigned; R8 was enabled.

| Check, for each Debug and Release variant | Base | Patched |
| --- | --- | --- |
| JDK 17 unit tests | 320 tests, 1 failure, no skips | 326 tests, the same failure, no skips |
| JRE 21 test execution with JDK 17 compilation | 320 tests, no failures or skips | 326 tests, no failures or skips |
| APK build | Pass | Pass |
| Lint | 25 warnings, 2 informational diagnostics | Same diagnostics, no additions |

The JDK 17 failure is unchanged in `LectureTableParserTest`:

```text
org.junit.ComparisonFailure: 分隔符 至 的开始 expected:<[15:30]> but was:<[]>
```

This is a baseline parser/runtime difference, not a parser fix in this patch. Host-JVM results do not establish Android parser behavior. Debug and Release are variants of the same tests and are not counted as independent scenarios.

Both revisions also retain the same SDK metadata-version warning and unstripped-native-library warning. Lint's only location difference is a line shift in the modified service. No Kotlin compiler warnings were reported. The complete comparison is in [build-comparison.json](build-comparison.json).

## Reproduce

Use an isolated checkout and the synthetic configuration from [device/synthetic-local.properties](device/synthetic-local.properties), plus the caller's SDK path. The repository's wrapper currently points to a Windows-local ZIP, so use a Gradle 8.11.1 installation directly:

```sh
"$GRADLE" -p "$REPO/android-app" \
  :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease \
  --continue
```

The canonical JDK 17 command retains the failure above. The JRE 21 comparison changes only each Gradle `Test` task's Java executable. The public release-response snapshot needed by the optional smoke test is supplied as `app/build/real-releases.json`; its digest and observed tags are recorded in the comparison JSON. No school API is needed for these checks.

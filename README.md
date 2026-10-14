# UCAS Sign in daemon retry evidence

Reproduction material for [the daemon retry fix](https://github.com/Shenrui-Ma/UCAS-Sign-in/commit/f42ece84b9347bc825c8a8a1aecb73c9142c961c), compared with upstream base `4593162ee1bd2e42f2a8f5b9c49ea9df8312d777` (1.3.5).

- [Build and JVM test comparison](BUILD_VALIDATION.md)
- [Android service scenarios, fixture boundaries and reproduction instructions](device/README.md)
- [Source and commit identity](patch-identity.json)

The application change contains only two production files and one regression-test file. This separate branch keeps the external instrumentation and captured traces available without adding them to the application's normal JVM test suite or CI.

The device runs use synthetic data and intercepted HTTP on an Android 15 emulator. They verify the recorded scenarios, not live school services, vendor background restrictions or every possible concurrent execution. The deadline check confirms no late submission; a timeout notification can still be missed after the window closes. The JDK 17 parser failure is unchanged from the base revision.

The harness and supporting scripts are provided under the repository's [AGPL-3.0 license](LICENSE).

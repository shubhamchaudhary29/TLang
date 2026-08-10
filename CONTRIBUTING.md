# Contributing

Use a focused branch and submit changes through a pull request. Keep unrelated formatting and generated files out of a change.

Requirements: JDK 21 and the checked-in Gradle wrapper. From the repository root run:

```bash
./gradlew clean check
./gradlew distributionSmokeTest
```

`check` runs JUnit tests, TLang fixtures, and version validation. `fmt --check` is available for TLang source files; the repository has no Java formatter task. Update documentation and regression tests with behavior changes, and describe validation in the pull request.

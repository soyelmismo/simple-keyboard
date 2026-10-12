# Contributing to Simple Keyboard

Thanks for helping improve Simple Keyboard. This guide covers the workflow and the checks every change must pass.

## Setup

Requirements and build commands are listed in [README.md](README.md#build-instructions) (JDK 21+, Android SDK `compileSdk 37`, `minSdk 23`).

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

## Workflow

Contributors with push access to `soyelmismo/simple-keyboard` are trusted: they may commit and push directly to `master` (or any branch) whenever they prefer, with no pull request required. The repository ruleset does not block direct pushes; push access plus the bypass actor grant is enough.

Pull requests remain the default path for outside contributions and for changes that benefit from CI and review before landing:

1. Open an issue first for features or behavior changes, so the approach can be agreed before you write code. Translations and small fixes can go straight to a pull request, and trusted collaborators can commit them directly.
2. Create a short branch from `master` (in your fork for outside contributors, or directly in the repository for collaborators with push access).
3. Keep each change to one bug or feature with the minimal diff. Separate cosmetic or formatting changes from functional fixes, and fill in the pull request template (including the verification command) when opening a pull request.
4. Write commit messages in [Conventional Commits](https://www.conventionalcommits.org/) format: `feat(scope): ...`, `fix(scope): ...`, `docs: ...`. Releases are versioned from these prefixes (`feat!` or `BREAKING CHANGE` bumps major, `feat` bumps minor, `fix` and others bump patch).

## Checks

Run this before opening a pull request. On pull requests, CI runs the unit tests (`testDebugUnitTest`); on pushes to `master` it additionally computes the semantic version, builds the release and debug APKs in a single pass, and publishes the GitHub Release. A Gitleaks secret scan runs on every push and pull request.

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```

## Code rules

- Read [AGENTS.md](AGENTS.md) before touching code: zero allocations on hot paths (`onDraw`, `onTouchEvent`, suggestions, keypress), immutable `SettingsValues`, no redundant `SharedPreferences.apply()` writes, no silent null bailouts without logging, and atomic PRs.
- Respect the native engine: this project derives from AOSP LatinIME. Do not refactor central pipelines (decoders, parsers, renderers) unless the task requires it.
- The files under [Locked core](AGENTS.md#5-archivos-bloqueados-core-neuronal--jni) (ternary inference engine, JNI bridge, training notebook) require explicit approval from the lead architect before any modification.
- Code, comments, commit messages, and documentation are written in English.

## Translations

See [Localization & Contributing Translations](README.md#localization--contributing-translations). Adding `app/src/main/res/values-<locale>/` with a translated `strings.xml` is enough; the build picks up the new locale automatically with no build file change.

## Security issues

Do not open public issues for vulnerabilities. Follow [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the [Apache License Version 2.0](LICENSE).

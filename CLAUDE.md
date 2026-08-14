# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```shell
./gradlew clean shadowJar     # app/build/libs/kraken-launcher-<version>-fat.jar
./gradlew clean createExe     # app/build/launch4j/KrakenInstaller.exe (needs a `jre` folder beside it at runtime)
java -jar app/build/libs/kraken-launcher-1.0.0-fat.jar   # runs the Installer GUI
```

Version comes from the `VERSION` env var (defaults to `1.0.0`) and is filtered into `kraken-version.properties` by `processResources`. Java 11 toolchain — do not use APIs above 11.

There are currently no tests (`app/src/test` is empty); `./gradlew test` is a no-op. CI (`.github/workflows/release.yml`) builds on push to master, versions as `1.0.<run_number>`, tags, uploads the fat jar as `KrakenSetup.jar` and a zipped exe+JRE bundle to MinIO, and cuts a GitHub release.

Runtime CLI flags (passed through `RuneLite.exe`, e.g. `./RuneLite.exe --qa`): `--qa` (beta bootstrap), `--force-ui`, `--configure`.

Useful paths on a dev machine:
- Logs: `~/.runelite/kraken/logs/launcher.log`
- Preferences: `~/.runelite/kraken/krakenprefs.json`
- Artifact cache: `~/.runelite/kraken/repository2/`
- RuneLite dir: `%LOCALAPPDATA%\RuneLite` (Windows) or `/Applications/RuneLite.app/Contents/Resources` (macOS)

## Architecture

This jar has **two entry points** that run in completely different contexts:

1. **`Installer`** — the fat jar's `Main-Class` and the launch4j `mainClassName`. A one-shot Swing GUI the user runs once. It copies itself (or downloads `KrakenSetup.jar` from MinIO when running as `.exe`) into the RuneLite directory, then rewrites `config.json`: `mainClass` → `com.kraken.launcher.Launcher`, `classPath` → `[RuneLite.jar, <jar>]`, and `vmArgs` → `-javaagent:<jar>` plus the `--add-opens`/`--add-exports` list. It also appends `--disable-telemetry` to `settings.json`, then marks both files read-only so RuneLite cannot revert them. `Uninstaller` reverses this and **must be kept in sync** with any `Installer` change.

2. **`Launcher`** — what RuneLite's native launcher actually starts after install. It runs the launcher UI, verifies bootstraps, hands control to `net.runelite.launcher.Launcher.main`, and injects Kraken artifacts on a background thread.

### The class loader boundary

This is the single most important constraint in the codebase. `Launcher` runs on the **system class loader**; RuneLite loads the client into a **child `URLClassLoader`**. The launcher therefore cannot reference RuneLite types directly at runtime — every RuneLite interaction in `Launcher` goes through reflection (`Class.forName`/`loadClass` on the located class loader).

The one class that *does* compile against RuneLite (`ClientWatcher`, which uses `EventBus`, `PluginManager`, `SplashScreen`) is declared `compileOnly 'net.runelite:client'` and is only ever loaded *through RuneLite's class loader* — which is why `Launcher.injectDependencies` adds the launcher's own jar URL to that loader before instantiating it. Never add a direct import of a RuneLite class to any other file.

### Injection sequence (`Launcher.injectDependencies`)

1. Poll `UIManager.get("ClassLoader")` until a loader defining `net.runelite.client.rs` appears — that's RuneLite's `URLClassLoader`.
2. Reflectively call `URLClassLoader.addURL`. On Java 16+ this fails with `InaccessibleObjectException`, so `openJavaNetPackage()` uses the ByteBuddy `Instrumentation` handle to `redefineModule` and open `java.base/java.net`. The ByteBuddy agent is installed via the `Premain-Class`/`Agent-Class` manifest entries (or `ByteBuddyAgent.install()` when run from an IDE) — byte-buddy must stay a system-classloader dependency and cannot move into the bootstrap.
3. Add every artifact from the Kraken bootstrap. `kraken-client-*` and `kraken-api-*` are deliberately **never cached** (their versions are also published as the `kraken-client-version`/`kraken-api-version` system properties); everything else goes through `BootstrapDownloader.cacheArtifact`, which SHA-256-verifies against the bootstrap hash and atomically moves into the cache.
4. On another thread, poll `net.runelite.client.RuneLite.getInjector()`, then use the **`com.google.inject.Injector` interface** (not the impl class — the impl is not accessible across loaders) to obtain a `ClientWatcher` and invoke `start(KrakenLoaderPlugin.class)`.
5. `ClientWatcher` waits for the splash screen to close, then loads/enables/starts the Kraken loader plugin **on the EDT** to avoid racing RuneLite's config and profile managers.

### Bootstrap safety gate

`Launcher.checkInjectedClientVersion` compares Kraken's bootstrap `hash` against RuneLite's `injected-client` artifact hash, and Kraken's `hookHash` against the `rlicn-*` artifact hash. Any mismatch means RuneLite shipped an unreviewed update and the launcher halts with a `FatalErrorDialog`. Users can bypass with "Skip Update Check" or run vanilla via "RuneLite Mode" (which skips `patch()` entirely). Both are `LauncherPreferences` flags persisted to `krakenprefs.json`.

Bootstrap sources: `https://minio.kraken-plugins.com/kraken-bootstrap-static/bootstrap.json` (or `bootstrap-qa.json` with `--qa`) and `https://static.runelite.net/bootstrap.json`.

## Conventions & gotchas

- Java agents and the callstack are visible to Jagex in the login packet, so runtime patching must happen through the already-attached agent rather than by attaching a new one — don't introduce a second agent attach path.
- If you add a JVM arg the launcher needs, update `Installer.requiredVmArgs` (or `macRequiredArgs`), the `Uninstaller` cleanup if it needs removing, and the manual-install JSON examples in `README.md`.
- Lombok (`@Slf4j`, `@Data`, `@Getter`) is used throughout; bootstrap models are plain Gson-mapped `@Data` classes.
- Don't use comments like // --------------------- something here ----------------------
- This repo is one of several siblings under `kraken/` (`kraken-client`, `kraken-api`, `kraken-plugins`, `kraken-updater`). The launcher only knows about them through bootstrap artifact names and the reflectively-loaded `net.runelite.client.plugins.kraken.KrakenLoaderPlugin`.

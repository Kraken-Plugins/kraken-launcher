# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```shell
./gradlew clean shadowJar     # app/build/libs/kraken-launcher-<version>-fat.jar
./gradlew clean createExe     # app/build/launch4j/KrakenInstaller.exe (needs a `jre` folder beside it at runtime)
java -jar app/build/libs/kraken-launcher-1.0.0-fat.jar   # runs the Installer GUI
```

Version comes from the `VERSION` env var (defaults to `1.0.0`) and is filtered into `kraken-version.properties` by `processResources`. Java 17 toolchain — do not use APIs above 17.

Tests are JUnit 4 under `app/src/test` (`./gradlew test`). CI (`.github/workflows/release.yml`) builds on push to master, versions as `1.0.<run_number>`, tags, uploads the fat jar as `KrakenSetup.jar` and a zipped exe+JRE bundle to SeaweedFS, and cuts a GitHub release.

Runtime CLI flags (passed through `RuneLite.exe`, e.g. `./RuneLite.exe --qa`): `--qa` (beta bootstrap), `--force-ui` (show the launcher UI even when Skip Launcher is saved), `--kraken-profile <name>` (log in as a Jagex account linked through the Profiles plugin; see `KrakenProfiles`). `LaunchArgs` strips these before RuneLite's launcher runs, because RuneLite treats any leftover positional argument as the client arguments. RuneLite's own `--configure` and `--postinstall` pass straight through without the Kraken UI.

Useful paths on a dev machine:
- Logs: `~/.runelite/logs/launcher.log` for the installed launcher (RuneLite.jar's `logback.xml` is first on the class path); `~/.runelite/kraken/logs/launcher.log` for the Installer and IDE runs
- Preferences: `~/.runelite/kraken/krakenprefs.json`
- Artifact cache: `~/.runelite/kraken/repository2/`
- RuneLite dir: `%LOCALAPPDATA%\RuneLite` (Windows) or `/Applications/RuneLite.app/Contents/Resources` (macOS)

## Architecture

This jar has **two entry points** that run in completely different contexts:

1. **`Installer`** — the fat jar's `Main-Class` and the launch4j `mainClassName`. A one-shot Swing GUI the user runs once. It copies itself (or downloads `KrakenSetup.jar` from SeaweedFS when running as `.exe`) into the RuneLite directory, then rewrites `config.json` through `Installer.applyKrakenConfig`: `mainClass` and `classPath` stay RuneLite's own (`net.runelite.launcher.Launcher`, `["RuneLite.jar"]`) and `vmArgs` → `-javaagent:<jar>` plus the `--add-opens`/`--add-exports` list. It also appends `--disable-telemetry` to `settings.json`, then marks both files read-only so RuneLite cannot revert them. `Uninstaller` reverses this and **must be kept in sync** with any `Installer` change.

2. **`KrakenAgent`** — the manifest's `Premain-Class`, so it runs in RuneLite's launcher JVM before `net.runelite.launcher.Launcher.main`. `premain` never sees the program arguments, so it only registers the `Instrumentation` with ByteBuddy and inlines `LauncherMainAdvice` at the start of RuneLite's `main` (the transformer is removed once applied). The advice calls `KrakenStartup.beforeRuneLite(args)` (once per JVM), which shows the launcher UI and returns RuneLite's arguments, or `null` to skip RuneLite's `main`. Kraken mode runs `Launcher.prepareKraken`: it sets `runelite.launcher.reflect` so the client runs in this JVM, verifies bootstraps and injects Kraken artifacts on a background thread. RuneLite Mode changes nothing except forcing `--launch-mode FORK` over a REFLECT launch mode, so RuneLite forks a stock client. `Launcher.main` is the entry point for IDE runs and for installs whose `config.json` still names it as `mainClass` (older installers, such as an existing `KrakenInstaller.exe`, always download the latest jar but write the old layout): it migrates `config.json` to the current layout (`Installer.migrateConfigJson`) before running the same `KrakenStartup`, and hands a launch started by RuneLite's fork (`--classpath` in the args) straight to RuneLite's launcher.

### The class loader boundary

This is the single most important constraint in the codebase. `Launcher` runs on the **system class loader**; RuneLite loads the client into a **child `URLClassLoader`**. The launcher therefore cannot reference RuneLite types directly at runtime — every RuneLite interaction in `Launcher` goes through reflection (`Class.forName`/`loadClass` on the located class loader).

The one class that *does* compile against RuneLite (`ClientWatcher`, which uses `EventBus`, `PluginManager`, `SplashScreen`) is declared `compileOnly 'net.runelite:client'` and is only ever loaded *through RuneLite's class loader* — which is why `Launcher.injectDependencies` adds the launcher's own jar URL to that loader before instantiating it. Never add a direct import of a RuneLite class to any other file.

### Injection sequence (`Launcher.injectDependencies`)

1. Poll `UIManager.get("ClassLoader")` until a loader defining `net.runelite.client.rs` appears — that's RuneLite's `URLClassLoader`.
2. Reflectively call `URLClassLoader.addURL`. On Java 16+ this fails with `InaccessibleObjectException`, so `openJavaBasePackage("java.net")` uses the ByteBuddy `Instrumentation` handle to `redefineModule` and open `java.base/java.net` (the same helper opens `java.lang` and `java.util` for the profile environment override). `KrakenAgent.premain` registers the `Instrumentation` with ByteBuddy (`ByteBuddyAgent.install()` through the `Agent-Class` entry when run from an IDE) — byte-buddy must stay a system-classloader dependency and cannot move into the bootstrap.
3. Add every artifact from the Kraken bootstrap. `kraken-client-*` and `kraken-api-*` are deliberately **never cached** (their versions are also published as the `kraken-client-version`/`kraken-api-version` system properties); everything else goes through `BootstrapDownloader.cacheArtifact`, which SHA-256-verifies against the bootstrap hash and atomically moves into the cache.
4. On another thread, poll `net.runelite.client.RuneLite.getInjector()`, then use the **`com.google.inject.Injector` interface** (not the impl class — the impl is not accessible across loaders) to obtain a `ClientWatcher` and invoke `start(KrakenLoaderPlugin.class)`.
5. `ClientWatcher` waits for the splash screen to close, then enables/starts the Kraken loader plugin **on the EDT** to avoid racing RuneLite's config and profile managers. If the Kraken jar landed before RuneLite's core plugin scan, RuneLite already instantiated the loader and that instance is reused — RuneLite treats two plugins with the same name as conflicts and disables them through the shared config key, so loading a second instance would silently prevent the loader from ever starting.

### Bootstrap safety gate

`Launcher.checkInjectedClientVersion` compares Kraken's bootstrap `hash` against RuneLite's `injected-client` artifact hash, and Kraken's `hookHash` against the `rlicn-*` artifact hash. Any mismatch means RuneLite shipped an unreviewed update and the launcher halts with a `FatalErrorDialog`. Users can bypass with "Skip Update Check" or run vanilla via "RuneLite Mode" (which skips `patch()` and lets RuneLite fork a stock client). Both are `LauncherPreferences` flags persisted to `krakenprefs.json`.

Bootstrap sources: `https://seaweed.kraken-plugins.com/kraken-bootstrap-static/bootstrap.json` (or `bootstrap-qa.json` with `--qa`) and `https://static.runelite.net/bootstrap.json`.

## Conventions & gotchas

- Java agents and the callstack are visible to Jagex in the login packet, so runtime patching must happen through the already-attached agent rather than by attaching a new one — don't introduce a second agent attach path.
- If you add a JVM arg the launcher needs, update `Installer.REQUIRED_VM_ARGS` (or `MAC_REQUIRED_VM_ARGS`), the `Uninstaller` cleanup if it needs removing, and the manual-install JSON examples in `README.md`.
- Lombok (`@Slf4j`, `@Data`, `@Getter`) is used throughout; bootstrap models are plain Gson-mapped `@Data` classes.
- Don't use comments like // --------------------- something here ----------------------
- This repo is one of several siblings under `kraken/` (`kraken-client`, `kraken-api`, `kraken-plugins`, `kraken-updater`). The launcher only knows about them through bootstrap artifact names and the reflectively-loaded `net.runelite.client.plugins.kraken.KrakenLoaderPlugin`.
- RuneLite Mode depends on two RuneLite behaviours: on Windows/macOS its launcher forks the client as `RuneLite.exe -c -J … -- --classpath …`, and the native launcher (`runelite/launcher` → `native/src/packr.cpp`) ignores `config.json`'s `vmArgs` whenever `-J` is given while still reading `mainClass`/`classPath` from it. Re-check both after RuneLite launcher updates, and never put Kraken back into `mainClass` or `classPath`.
- In the installed launcher JVM `RuneLite.jar` is first on the class path, so its minimized Gson 2.8.5 (no `JsonParser`), Guava, SLF4J and Logback (and its `logback.xml`) win over the fat jar's. Launcher-path code should only use `Gson#fromJson` and the `Json*` element classes. The Installer and Uninstaller run standalone and are not affected.

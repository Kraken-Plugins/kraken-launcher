<!-- PROJECT LOGO -->
<br />
<div align="center">
  <a href="https://github.com/cbartram/kraken-launcher">
    <img src="app/src/main/resources/logo.png" alt="Logo" width="128" height="128">
  </a>

<h3 align="center">Kraken Launcher</h3>

  <p align="center">
   A custom RuneLite launcher that loads the Kraken client by rewriting RuneLite's startup config and adding the launcher JAR to the classpath.
  </p>
</div>

[![Contributors][contributors-shield]][contributors-url]
[![Discord Chat](https://shields.io)](https://discord.gg/6UGZqXj22s)
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]
[![ko-fi](https://www.ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/runewraith)

# Kraken Launcher

Kraken Launcher is a custom bootstrap loader designed to wrap and modify the official RuneLite client. It functions by intercepting the RuneLite startup process, patching the RuneLite `URLClassLoader`, and injecting custom, side-loaded plugins directly into the client's dependency graph. This project was inspired by [Arnuh's RuneLite Hijack repository](https://github.com/Arnuh/RuneLiteHijack/tree/master), which uses a similar system for loading custom plugins without modifying or forking RuneLite's launcher.

This repository now also includes a local installer path that rewrites RuneLite's `config.json`, adds the launcher JAR to RuneLite's classpath, and starts the Kraken plugin through RuneLite's plugin system.

> ⚠️ Disclaimer: This software injects the Kraken Client plugin and modifies RuneLite's classpath at runtime. 
> Use at your own risk. The developers are not responsible for account bans or client instability caused by RuneLite updates.

## Features

- Automated Bootstrap Management: Downloads and caches artifacts for both RuneLite and Kraken to ensure version compatibility.
- Runtime Injection: Hooks into the RuneLite `URLClassLoader` to inject external JARs without modifying the physical RuneLite client or launcher files.
- Safety Hash Checking: Verifies RuneLite's injected-client and rlicn artifacts against known safe hashes. If RuneLite pushes a silent update, the launcher halts to prevent detection or instability.
- Local RuneLite install support: Updates RuneLite's `config.json` so the launcher jar is on the classpath and `com.kraken.launcher.Launcher` is the entry point.

## Installation & Usage

This launcher requires Java 11 or higher and RuneLite to be pre-installed on your system. The installer supports Windows and macOS.

### Build and run locally

Build the shaded jar with Gradle:

```shell
./gradlew clean shadowJar
```

The local build artifact is:

```text
app/build/libs/kraken-launcher-<version>-fat.jar
```

If `VERSION` is not set, Gradle defaults to `1.0.0`.

Run the shaded jar directly:

```shell
java -jar app/build/libs/kraken-launcher-<version>-fat.jar
```

If you downloaded a release bundle or renamed the jar to match the published artifact, run that name instead:

```shell
java -jar KrakenSetup.jar
```

To run the installer in QA mode which uses a beta build of the Kraken client pass the `--qa` flag to the executable:

```shell
./RuneLite.exe --qa
```

### Automatic install

The installer copies the launcher JAR into RuneLite's resources directory and updates `config.json` so RuneLite starts `com.kraken.launcher.Launcher`
instead of the default launcher. This also adds a `-javaagent:` entry to the JVM args so that runtime bytecode modifications can be made to the client.

> :warning: Note, attached java agent information is sent over the network to OSRS servers to on login. 

### Manual local install

If you want to wire it up yourself, place the built jar in RuneLite's resources directory:

- Windows: `%LOCALAPPDATA%\RuneLite\`
- macOS: `/Applications/RuneLite.app/Contents/Resources/`

Then edit RuneLite's `config.json` so it points at the launcher and includes the launcher jar on the classpath.

Windows example:

```json
{
  "mainClass": "com.kraken.launcher.Launcher",
  "classPath": [
    "RuneLite.jar",
    "kraken-launcher-1.0.0-fat.jar"
  ],
  "vmArgs": [
    "-javaagent:kraken-launcher-1.0.0-fat.jar"
  ]
}
```

macOS example:

```json
{
  "mainClass": "com.kraken.launcher.Launcher",
  "classPath": [
    "RuneLite.jar",
    "kraken-launcher-1.0.0-fat.jar"
  ],
  "vmArgs": [
    "-javaagent:kraken-launcher-1.0.0-fat.jar",
    "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED",
    "--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-exports=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-exports=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-exports=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED"
  ]
}
```

Preserve any existing RuneLite `vmArgs` that you still need, but remove any older `-javaagent:` entry for the launcher before adding the new one.

### Windows executable

You can also build a `.exe` file for easy Windows installation using:

```shell
./gradlew clean createExe
```

This uses launch4j and produces `app/build/launch4j/KrakenInstaller.exe`.

To run the executable with the bundled runtime behavior used by releases, keep a folder named `jre` next to the executable. The release workflow expects that folder at `app/build/launch4j/jre` during packaging.

## Architecture & How It Works

The launcher operates by hijacking the standard Java startup process. Bootstrap resolution contacts the Kraken server to get the manifest of required artifacts, downloads RuneLite's bootstrap, and compares the SHA-256 hashes of the gamepack and injection hooks against Kraken's allowed list. The launcher then patches RuneLite's classpath so it can add custom dependencies after the launcher has started but before the client starts.

It uses reflection to invoke `addURL` on the class loader, adding the Kraken client and its dependencies. The launcher creates a daemon thread that polls for `net.runelite.client.RuneLite.getInjector()` so it can use RuneLite classes like `PluginManager`. Because RuneLite is loaded in a child class loader, the launcher uses reflection on the `com.google.inject.Injector` interface to access the dependency graph.

The `ClientWatcher` is instantiated via the Guice injector and waits for the splash screen to close, then uses `PluginManager` to forcefully load the Kraken plugin.

## Built With

- [Gradle](https://gradle.org/) - Build tool
- [Java](https://www.java.com/en/download/) - Programming language used for the launcher
- [RuneLite](https://runelite.net/) - The base client for the launcher
- [MinIO](https://min.io/) - Object storage for the bootstrap and release artifacts

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct, and the process for submitting pull requests to us.

## Versioning

We use [Semantic Versioning](http://semver.org/) for versioning. For the versions available, see the [tags on this repository](https://github.com/cbartram/kraken-launcher/tags).

## Authors

- *Initial Project implementation* - [RuneWraith](https://github.com/cbartram)

See also the list of [contributors](https://github.com/cbartram/kraken-launcher/graphs/contributors) who participated in this project.

## License

This project is licensed under the [MIT License](LICENSE).

## Acknowledgments

- RuneLite for making an incredible piece of software and API.
- Arnuh's [RuneLiteHijack repo](https://github.com/Arnuh/RuneLiteHijack/tree/master) for inspiration on the actual hijack process

[contributors-shield]: https://img.shields.io/github/contributors/cbartram/kraken-launcher.svg?style=for-the-badge
[contributors-url]: https://github.com/cbartram/kraken-launcher/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/cbartram/kraken-launcher.svg?style=for-the-badge
[forks-url]: https://github.com/cbartram/kraken-launcher/network/members
[stars-shield]: https://img.shields.io/github/stars/cbartram/kraken-launcher.svg?style=for-the-badge
[stars-url]: https://github.com/cbartram/kraken-launcher/stargazers
[issues-shield]: https://img.shields.io/github/issues/cbartram/kraken-launcher.svg?style=for-the-badge
[issues-url]: https://github.com/cbartram/kraken-launcher/issues
[license-shield]: https://img.shields.io/github/license/cbartram/kraken-launcher.svg?style=for-the-badge
[license-url]: https://github.com/cbartram/kraken-launcher/blob/master/LICENSE

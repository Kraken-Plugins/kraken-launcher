[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]

<!-- PROJECT LOGO -->
<br />
<div align="center">
  <a href="https://github.com/cbartram/kraken-launcher">
    <img src="app/src/main/resources/logo.png" alt="Logo" width="128" height="128">
  </a>

<h3 align="center">Kraken Launcher</h3>

  <p align="center">
   A custom RuneLite launcher which loads the Kraken client.
    <br />
</div>

# Kraken Launcher

Kraken Launcher is a custom bootstrap loader designed to wrap and modify the official RuneLite client. It functions by 
intercepting the RuneLite startup process, patching the RuneLite `URLClassLoader`, and injecting custom, side-loaded plugins (specifically the Kraken Client) 
directly into the client's dependency graph. This project was inspired by [Arnuh's RuneLite Hijack repository](https://github.com/Arnuh/RuneLiteHijack/tree/master) which uses a similar system for
loading custom plugins without modifying or forking RuneLite's launcher.

> ⚠️ Disclaimer: This software modifies the RuneLite client at runtime. Use at your own risk. 
> The developers are not responsible for account bans or client instability caused by RuneLite updates.

## 🚀 Features

- Automated Bootstrap Management: Downloads and caches artifacts for both RuneLite and Kraken to ensure version compatibility.
- Runtime Injection: Hooks into the RuneLite URLClassLoader to inject external JARs without modifying the physical RuneLite client or launcher files.
- Safety Hash Checking: Automatically verifies RuneLite's injected-client and rlicn artifacts against known "safe" hashes. If RuneLite pushes a silent update, the launcher halts to prevent detection/instability.

## 📦 Installation & Usage

This launcher requires Java 11 or higher (matching RuneLite requirements) and RuneLite to be pre-installed on your system.

Download the latest jar file (for both Windows or MacOS) [here](https://kraken-plugins.com/download) and install the launcher
to hook into RuneLite by either double clicking the JAR file or running:

```shell
java -jar KrakenSetup.jar
```

You can now run the launcher by launching RuneLite through the Jagex Launcher or `RuneLite.exe`.

## 🛠 Architecture & How It Works

The launcher operates by "hijacking" the standard Java startup process. Bootstrap Resolution: The launcher contacts the
Kraken server to get the manifest of required artifacts. It downloads RuneLite's bootstrap and compares the SHA-256 
hashes of the gamepack and injection hooks against Kraken's allowed list. The launcher hijacks the RuneLite URLClassLoader
so that it can add custom dependencies to RuneLites classpath after the launcher has started but before the client starts.

It uses reflection to invoke addURL on this loader, adding the Kraken Client and its dependencies.
The launcher creates a daemon thread that polls for `net.runelite.client.RuneLite.getInjector()` so that it can use RuneLite
classes like `PluginManager` Because RuneLite is loaded in a child ClassLoader, 
the launcher uses Reflection on the `com.google.inject.Injector` interface to access the dependency graph.

The `ClientWatcher` is instantiated via the Guice Injector and waits for the Splash Screen to close, then uses 
`PluginManager` to forcefully load the Kraken Plugin.

## Building from Source
This project uses Gradle as the build tool. You can build the shaded JAR file using the following command:

```shell
./gradlew clean shadowJar
```

The output will be in `build/libs/kraken-launcher-1.0.0-fat.jar`.

## Executable File

You can also build a `.exe` file for easy Windows installation using: `./gradlew clean createExe` which uses launch4j. 
Note that to run the executable, you will need to have a folder named: `jre` in the same directory as the executable (`app/build/launch4j/jre`).

You can download the Java 11 [JRE here](https://api.adoptium.net/v3/binary/latest/11/ga/windows/x64/jre/hotspot/normal/eclipse) and
name it `jre`. If you omit this step, you will receive an error saying the executable requires java 11 to run.

## Built With

- [Gradle](https://go.dev/doc/install) - Build tool
- [Java](https://www.java.com/en/download/) - Programming language used for the plugins
- [RuneLite](https://runelite.net/) - The base client for the plugins
- [MinIO](https://min.io/) - Object storage for the plugins and bootstrap.json file

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code
of conduct, and the process for submitting pull requests to us.

## Versioning

We use [Semantic Versioning](http://semver.org/) for versioning. For the versions
available, see the [tags on this
repository](https://github.com/cbartram/kraken-loader-plugin/tags).

## Authors

- *Initial Project implementation* - [RuneWraith](https://github.com/cbartram)

See also the list of [contributors](https://github.com/PurpleBooth/a-good-readme-template/contributors)
who participated in this project.

## License

This project is licensed under the [CC0 1.0 Universal](LICENSE.md)
Creative Commons License - see the [LICENSE.md](LICENSE.md) file for
details

## Acknowledgments

- RuneLite for making an incredible piece of software and API.
- Arnuh's [RuneLiteHijack repo](https://github.com/Arnuh/RuneLiteHijack/tree/master) for inspiration on the actual Hijack process

[contributors-shield]: https://img.shields.io/github/contributors/cbartram/kraken-launcher.svg?style=for-the-badge
[contributors-url]: https://github.com/cbartram/kraken-launcher/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/cbartram/kraken-launcher.svg?style=for-the-badge
[forks-url]: https://github.com/cbartram/kraken-launcher/network/members
[stars-shield]: https://img.shields.io/github/stars/cbartram/kraken-launcher.svg?style=for-the-badge
[stars-url]: https://github.com/cbartram/kraken-launcher/stargazers
[issues-shield]: https://img.shields.io/github/issues/cbartram/kraken-launcher.svg?style=for-the-badge
[issues-url]: https://github.com/cbartram/kraken-launcher/issues
[license-shield]: https://img.shields.io/github/license/cbartram/kraken-launcher.svg?style=for-the-badge
[license-url]: https://github.com/cbartram/kraken-launcher/blob/master/LICENSE.txt

package com.kraken.launcher;

import com.kraken.launcher.bootstrap.BootstrapDownloader;
import com.kraken.launcher.bootstrap.model.Artifact;
import com.kraken.launcher.bootstrap.model.Bootstrap;
import com.kraken.launcher.ui.FatalErrorDialog;
import com.kraken.launcher.ui.LauncherPreferences;
import com.kraken.launcher.ui.LauncherUI;
import com.kraken.launcher.util.Utils;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.ByteBuddyAgent;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.net.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.bytebuddy.agent.ByteBuddyAgent.getInstrumentation;


/**
 * Hijacks the RuneLite launcher to inject custom client code.
 */
@Slf4j
public class Launcher {

    public static final String VERSION = loadVersion();
    private static final long CLASSLOADER_POLL_INTERVAL_MS = 500;
    private static final long CLASSLOADER_WAIT_TIMEOUT_MS = 60_000;
    private static final long INJECTOR_POLL_INTERVAL_MS = 25;
    private static final long INJECTOR_WAIT_TIMEOUT_MS = 60_000;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;
    private static final String RUNELITE_PACKAGE = "net.runelite.client.rs";
    private static final String LAUNCHER_CLASS = "net.runelite.launcher.Launcher";

    private final ExecutorService executorService;
    private final BootstrapDownloader bootstrapDownloader; // Class internally caches the bootstrap files for both RuneLite and Kraken

    public Launcher(BootstrapDownloader downloader) {
        this.bootstrapDownloader = downloader;
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "com.kraken.launcher.patcher");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) ->
                    log.error("Uncaught exception on patcher thread {}: ", t.getName(), e));
            return thread;
        });
    }

    public static void main(String[] args) {
        log.info("Starting Kraken Launcher");
        logRuntimeEnvironment();

        try {
            Instrumentation inst = getInstrumentation();
            log.info("ByteBuddy Java Agent, installed successfully {}", inst);
        } catch (IllegalStateException e) {
            // When running directly via IDE, this installs into the current jvm without the need
            // for extra VM Args like "-javaagent:JarFileWithByteBuddyAgent.jar"
            try {
                ByteBuddyAgent.install();
                log.info("ByteBuddy Java Agent, installed successfully {}", getInstrumentation());
            } catch (IllegalStateException ex) {
                log.warn("ByteBuddy Java Agent was not installed. The JVM was not started with the correct -javaagent argument, or the JAR manifest is missing the Premain-Class. " +
                        "Kraken dependency injection will be unavailable until the launcher is started with instrumentation.");
            }
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.warn("Failed to set system look and feel: ", e);
        }

        boolean forceShowUI = Arrays.asList(args).contains("--force-ui");
        boolean configure = Arrays.asList(args).contains("--configure");
        boolean qaBootstrap = Arrays.asList(args).contains("--qa");

        SwingUtilities.invokeLater(() -> {
            LauncherUI gui = new LauncherUI(qaBootstrap);

            if(configure) {
                gui.onStartClicked(true, qaBootstrap);
                return;
            }

            if(forceShowUI) {
                log.info("Force showing UI, --force-ui arg passed");
                gui.setVisible(true);
            } else if(gui.getPreferences().isSkipLauncher()) {
                log.info("Skipping Kraken Launcher UI and starting RuneLite");
                gui.onStartClicked(false, qaBootstrap);
            } else {
                gui.setVisible(true);
            }
        });
    }


    /**
     * Logs the JVM the launcher was started with. RuneLite.exe loads its own bundled JRE from the install
     * directory and ignores JAVA_HOME, so this is the only reliable way to know which runtime a user is on
     * when a bug report comes in. The reflective class path injection depends on java.base/java.net being
     * open, which any of the arguments and environment variables below can take away.
     */
    private static void logRuntimeEnvironment() {
        try {
            log.info("Java: {} ({}), vendor: {}", System.getProperty("java.version"),
                    System.getProperty("java.runtime.version"), System.getProperty("java.vendor"));
            log.info("Java home: {}", System.getProperty("java.home"));
            log.info("JVM: {} {}", System.getProperty("java.vm.name"), System.getProperty("java.vm.version"));
            log.info("OS: {} {} ({})", System.getProperty("os.name"), System.getProperty("os.version"),
                    System.getProperty("os.arch"));
            log.info("JVM arguments: {}", ManagementFactory.getRuntimeMXBean().getInputArguments());
            log.info("Class path: {}", System.getProperty("java.class.path"));

            for (String name : new String[]{"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_HOME"}) {
                String value = System.getenv(name);
                if (value != null) {
                    log.info("Environment {}={}", name, value);
                }
            }

            boolean javaNetOpen = URLClassLoader.class.getModule().isOpen("java.net", Launcher.class.getModule());
            log.info("java.base/java.net open to {}: {}", Launcher.class.getModule(), javaNetOpen);

            if (!javaNetOpen) {
                log.warn("java.base/java.net is not open to this module, so RuneLite's class loader cannot be " +
                        "modified reflectively. The JVM was either started with --illegal-access=deny or is Java 16 " +
                        "or newer without --add-opens=java.base/java.net=ALL-UNNAMED. Re-run the Kraken installer to " +
                        "add the flag to config.json, and check the JVM arguments and environment variables above " +
                        "for whatever removed the access.");
            }
        } catch (Exception e) {
            log.warn("Failed to log the runtime environment: ", e);
        }
    }

    /**
     * Starts the launcher with preferences from the GUI
     * @param preferences The preferences to use for the patching process.
     * @param configure If true, the launcher will start in configure mode.
     * @param qa True if this should use the QA bootstrap
     */
    public static void startWithPreferences(LauncherPreferences preferences, boolean configure, boolean qa) {
        System.setProperty("runelite.launcher.nojvm", "true");
        System.setProperty("runelite.launcher.reflect", "true");

        // Set proxy system property if specified
        if (preferences.getProxy() != null && !preferences.getProxy().isEmpty()) {
            System.setProperty("kraken.proxy", preferences.getProxy());
            log.info("Proxy configured: {}", preferences.getProxy());
        }

        Launcher launcher = new Launcher(new BootstrapDownloader(qa));

        // Skip launcher.start() if RuneLite mode is enabled
        if (preferences.isRuneliteMode()) {
            log.info("RuneLite mode enabled - skipping Kraken bootstrap");
        } else {
            if (!launcher.patch(preferences)) {
                log.info("Kraken Launcher failed to start, see error messages above.");
                return;
            }
        }

        // Apply the SOCKS proxy before RuneLite (and therefore the game client) opens any sockets, and in every
        // mode including RuneLite Mode. The bootstrap downloads in patch() above deliberately run first so they use
        // a direct connection rather than routing through a proxy that may only be reachable for game traffic.
        String proxy = preferences.getProxy();
        if (proxy != null && !proxy.isEmpty()) {
            configureProxy(proxy);
        }

        try {
            // When running from the IDE, the RuneLite.jar is not on the classpath, so it must be dynamically found and added to resolve
            // net.runelite.launcher.Launcher class. When running through Jagex launcher, the config.json file already specifies both RuneLite.jar and KrakenSetup.jar
            // on the classpath, so additional injection is unnecessary and will be skipped.
            Utils.injectRuneLiteLauncher();
            Class<?> launcherClass = Class.forName(LAUNCHER_CLASS);
            String[] args = new String[]{};

            if(configure) {
                log.info("Starting Launcher (Configure)");
                args = new String[]{"--configure"};
            }

            launcherClass.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (Exception e) {
            log.error("Failed to start RuneLite launcher", e);
            launcher.shutdown();
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(launcher::shutdown, "com.kraken.launcher.shutdown"));
    }

    /**
     * Starts the hijack process asynchronously by patching
     * the bootstrap and loading the Kraken client plugin.
     * @param preferences The preferences to use for the patching process.
     * @return True if the patching process was successful, false otherwise.
     */
    public boolean patch(LauncherPreferences preferences) {
        try {
            bootstrapDownloader.downloadKrakenBootstrap();
            bootstrapDownloader.downloadRuneLiteBootstrap();
        } catch (IOException e) {
            log.error("Error fetching one of the bootstrap files, shutting down: ", e);
            showFatalError("The Kraken Client is currently offline. Could not fetch RuneLite or Kraken's bootstrap.");
            return false;
        }

        if (bootstrapDownloader.getKrakenBootstrap() == null || bootstrapDownloader.getRuneliteBootstrap() == null) {
            log.error("Kraken or RuneLite Bootstrap file is null. Cannot patch client classpath with unknown dependencies.");
            showFatalError("The Kraken Client is currently offline. One of the bootstrap files is null.");
            return false;
        }

        SafetyCheckResult safety = checkInjectedClientVersion(bootstrapDownloader, preferences);
        if (!safety.ok) {
            log.error("RuneLite update safety check failed. Halting client startup until the update is verified.");
            showFatalError(safety.message);
            return false;
        }

        log.info("Kraken bootstrap verified, starting client patching process.");
        executorService.execute(() -> injectDependencies(preferences));
        return true;
    }

    /**
     * Loads the version dynamically from the kraken-version properties file
     * @return String semantic version i.e. 1.0.5
     */
    private static String loadVersion() {
        try (InputStream is = Launcher.class.getResourceAsStream("/kraken-version.properties")) {
            if (is == null) {
                return "DEV"; // Fallback if file is missing (e.g. inside IDE without build)
            }
            Properties props = new Properties();
            props.load(is);
            return props.getProperty("version", "Unknown");
        } catch (Exception e) {
            log.error("Failed to load version", e);
            return "Error";
        }
    }

    /**
     * Shuts down the executor service gracefully.
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Prepares the parent/system loader with the launcher and Kraken artifacts so RuneLite can resolve them
     * without reflectively mutating RuneLite's own class loader.
     */
    private void injectDependencies(LauncherPreferences preferences) {
        try {
            ClassLoader classLoader = waitForRuneLiteClassLoader();
            log.info("RuneLite classLoader located");

            if (!(classLoader instanceof URLClassLoader)) {
                throw new IllegalStateException("ClassLoader is not a URLClassLoader");
            }

            URLClassLoader urlClassLoader = (URLClassLoader) classLoader;
            addUrlToClassLoader(urlClassLoader, resolveJarUrl());

            // Enables Launcher to be run via IDE instead of Jagex launcher for testing.
            addUrlToClassLoader(urlClassLoader, ClientWatcher.class.getProtectionDomain().getCodeSource().getLocation());

            for (Artifact artifact : bootstrapDownloader.getKrakenBootstrap().getArtifacts()) {
                log.debug("Adding JAR to RuneLite classpath: {}", artifact.getName());

                // The Kraken client and api change often, so they are re-fetched every launch with the bootstrap
                // kept as the source of truth. They are still SHA-256 verified against the bootstrap hash, they are
                // just not persisted to the long-lived cache. A verification failure aborts injection (fail closed)
                // rather than loading unverified code into the client.
                if (artifact.getName().toLowerCase().startsWith("kraken-client-")) {
                    System.setProperty("kraken-client-version", parseVersion(artifact.getName().toLowerCase(), "kraken-client-"));
                    File verifiedClient = bootstrapDownloader.downloadVerified(artifact);
                    addUrlToClassLoader(urlClassLoader, verifiedClient.toURI().toURL());
                    continue;
                }

                if (artifact.getName().toLowerCase().startsWith("kraken-api-")) {
                    System.setProperty("kraken-api-version", parseVersion(artifact.getName().toLowerCase(), "kraken-api-"));
                    File verifiedApi = bootstrapDownloader.downloadVerified(artifact);
                    addUrlToClassLoader(urlClassLoader, verifiedApi.toURI().toURL());
                    continue;
                }

                try {
                    File cachedArtifact = bootstrapDownloader.cacheArtifact(artifact);
                    addUrlToClassLoader(urlClassLoader, cachedArtifact.toURI().toURL());
                } catch (Exception e) {
                    log.info("No cached artifact {}, falling back to remote URL: {}", artifact.getName(), e.getMessage());
                    addUrlToClassLoader(urlClassLoader, new URL(artifact.getPath()));
                }
            }

            // Wait for the RuneLite injector to be created by Guice, then load the Kraken plugin. Runs on the same
            // managed patcher executor rather than a bare thread so it is named, daemonised and shut down cleanly.
            executorService.execute(() -> awaitInjectorAndStartWatcher(classLoader));
        } catch (InterruptedException e) {
            log.warn("Client patching process interrupted: ", e);
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            log.error("CRITICAL: failed to patch RuneLite client: ", e);
        }
    }

    /**
     * Waits for RuneLite's Guice injector to be created, then uses it to obtain a ClientWatcher and start the Kraken
     * loader plugin. Bounded by {@link #INJECTOR_WAIT_TIMEOUT_MS} so a RuneLite that never publishes an injector
     * degrades to vanilla instead of spinning forever. Runs on the patcher executor.
     */
    private void awaitInjectorAndStartWatcher(ClassLoader classLoader) {
        try {
            Class<?> runeLiteClass = classLoader.loadClass("net.runelite.client.RuneLite");
            Method getInjectorMethod = runeLiteClass.getDeclaredMethod("getInjector");

            Object injector = null;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INJECTOR_WAIT_TIMEOUT_MS);
            while (injector == null) {
                injector = getInjectorMethod.invoke(null);
                if (injector == null) {
                    if (System.nanoTime() >= deadline) {
                        log.error("Timed out after {}ms waiting for RuneLite's Guice injector. Kraken client was not injected.", INJECTOR_WAIT_TIMEOUT_MS);
                        return;
                    }
                    try {
                        Thread.sleep(INJECTOR_POLL_INTERVAL_MS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            Class<?> watcherClass = classLoader.loadClass("com.kraken.launcher.ClientWatcher");
            Class<?> krakenPluginMainClass = classLoader.loadClass("net.runelite.client.plugins.kraken.KrakenLoaderPlugin");

            // Load the Injector INTERFACE to avoid IllegalAccessException on the internal Impl class
            Class<?> injectorInterface = classLoader.loadClass("com.google.inject.Injector");
            Method getInstanceMethod = injectorInterface.getMethod("getInstance", Class.class);
            Object watcherInstance = getInstanceMethod.invoke(injector, watcherClass);

            // Start the watcher
            Method startMethod = watcherClass.getMethod("start", Class.class);
            startMethod.invoke(watcherInstance, krakenPluginMainClass);
            log.info("Kraken Client injected successfully.");
        } catch (ClassNotFoundException e) {
            log.error("Class not found during injection (Check classpath/bootstrap): ", e);
        } catch (Exception e) {
            log.error("Reflection error during injection: ", e);
        }
    }

    /**
     * Configures network traffic to be relayed through a provided SOCKS5 proxy. Applied once, before RuneLite
     * starts, so it also covers RuneLite Mode.
     * @param proxyString The proxy string in the format host:port or host:port:user:pass. IPv6 hosts must be
     *                    wrapped in brackets, e.g. [::1]:1080. The password may contain colons; the host (unless
     *                    bracketed), port, and username may not.
     */
    private static void configureProxy(String proxyString) {
        ProxySpec spec = parseProxy(proxyString);
        if (spec == null) {
            return;
        }

        log.info("Configuring SOCKS5 proxy: {}:{}", spec.host, spec.port);

        // SOCKS handles both TCP and UDP. HTTP/HTTPS proxy properties are deliberately not set - they would take
        // precedence and bypass the SOCKS proxy.
        System.setProperty("socksProxyHost", spec.host);
        System.setProperty("socksProxyPort", spec.port);
        System.setProperty("socksProxyVersion", "5");

        if (!spec.user.isEmpty() && !spec.pass.isEmpty()) {
            System.setProperty("java.net.socks.username", spec.user);
            System.setProperty("java.net.socks.password", spec.pass);

            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        String protocol = getRequestingProtocol();
                        log.info("Requesting proxy protocol: {}", protocol);
                        if (protocol != null && protocol.toLowerCase().contains("socks")) {
                            return new PasswordAuthentication(spec.user, spec.pass.toCharArray());
                        }
                    }
                    return null;
                }
            });

            log.info("SOCKS5 authentication configured for user: {}", spec.user);
        } else {
            log.info("SOCKS5 proxy configured without authentication");
        }
    }

    /**
     * Parses a proxy string of the form host:port or host:port:user:pass into its parts. IPv6 hosts must be
     * bracketed (e.g. [::1]:1080) so their colons are not mistaken for delimiters. The password is taken as the
     * remainder after the username, so it may itself contain colons.
     * @return the parsed spec, or null if the string is malformed (an error is logged in that case).
     */
    private static ProxySpec parseProxy(String proxyString) {
        String remainder = proxyString.trim();
        if (remainder.isEmpty()) {
            return null;
        }

        String host;
        if (remainder.startsWith("[")) {
            int close = remainder.indexOf(']');
            if (close < 0) {
                log.error("Invalid proxy: missing closing ']' for IPv6 host: {}", proxyString);
                return null;
            }
            host = remainder.substring(1, close);
            remainder = remainder.substring(close + 1);
            if (!remainder.startsWith(":")) {
                log.error("Invalid proxy: expected ':port' after IPv6 host: {}", proxyString);
                return null;
            }
            remainder = remainder.substring(1);
        } else {
            int firstColon = remainder.indexOf(':');
            if (firstColon <= 0) {
                log.error("Invalid proxy format. Expected host:port or host:port:user:pass (bracket IPv6 hosts): {}", proxyString);
                return null;
            }
            host = remainder.substring(0, firstColon);
            remainder = remainder.substring(firstColon + 1);
        }

        String port;
        String user = "";
        String pass = "";
        int portEnd = remainder.indexOf(':');
        if (portEnd < 0) {
            port = remainder;
        } else {
            port = remainder.substring(0, portEnd);
            String creds = remainder.substring(portEnd + 1);
            int userEnd = creds.indexOf(':');
            if (userEnd < 0) {
                log.error("Invalid proxy format. Expected host:port or host:port:user:pass: {}", proxyString);
                return null;
            }
            user = creds.substring(0, userEnd);
            pass = creds.substring(userEnd + 1);
        }

        if (!port.matches("\\d+")) {
            log.error("Invalid proxy port '{}' in: {}", port, proxyString);
            return null;
        }

        return new ProxySpec(host, port, user, pass);
    }

    /**
     * Parses a semantic version from a JAR file name in the format <name>-<version>.jar
     * @param name The name of the file to match
     * @param prefix The prefix of the file i.e kraken-client-
     * @return The semantic version i.e 1.2.3
     */
    private String parseVersion(String name, String prefix) {
        String regex = prefix + "(\\d+\\.\\d+\\.\\d+)\\.jar";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(name);

        if (matcher.matches()) {
            return matcher.group(1);
        } else {
            log.info("Version not found in the string. Defaulting to v1.0.0");
            return "1.0.0";
        }
    }

    /**
     * Verifies that the RuneLite client has not changed or been updated. This gives the Kraken team time to verify if
     * the client is safe to use.
     * @return True if RuneLite's injected client hash matches Kraken's (i.e RuneLite has not pushed a new update).
     */
    private SafetyCheckResult checkInjectedClientVersion(BootstrapDownloader downloader, LauncherPreferences preferences) {
        if (preferences.isSkipUpdateCheck()) {
            log.warn("Skipping update check as requested - USE AT YOUR OWN RISK");
            return SafetyCheckResult.ok();
        }

        if (downloader.getRuneliteBootstrap() == null || downloader.getKrakenBootstrap() == null) {
            log.error("Cannot check injected client hash, either Kraken or RuneLite's bootstrap is null");
            return SafetyCheckResult.fail(offlineMessage("bootstrap unavailable"));
        }

        Bootstrap runeliteBootstrap = downloader.getRuneliteBootstrap();
        Bootstrap krakenBootstrap = downloader.getKrakenBootstrap();

        Artifact injectedClient = Arrays.stream(runeliteBootstrap.getArtifacts())
                .filter((a) -> a.getName().contains("injected-client"))
                .findFirst()
                .orElse(null);

        if (injectedClient == null) {
            log.error("Could not locate RuneLite's injected-client artifact in RuneLite's bootstrap");
            return SafetyCheckResult.fail(offlineMessage("injected-client artifact missing"));
        }

        Artifact hook = Arrays.stream(runeliteBootstrap.getArtifacts())
                .filter((a) -> a.getName().contains("rlicn-"))
                .findFirst()
                .orElse(null);

        if (hook == null) {
            log.error("Could not locate RuneLite's rlicn artifact in RuneLite's bootstrap");
            return SafetyCheckResult.fail(offlineMessage("RLICN artifact missing"));
        }

        String krakenHash = krakenBootstrap.getHash();
        log.info("kraken bootstrap hash: {} injected client hash: {}", krakenHash, injectedClient.getHash());
        if (krakenHash == null || !krakenHash.equalsIgnoreCase(injectedClient.getHash())) {
            log.error("Kraken bootstrap hash does not match RuneLite's injected-client hash. kraken: {}, injected-client: {}", krakenHash, injectedClient.getHash());
            return SafetyCheckResult.fail(offlineMessage("injected version mismatch"));
        }

        // The rlicn (DLL hook) artifact must exactly match Kraken's known-good hookHash. A missing hookHash or a
        // changed hash both mean RuneLite shipped an update to the hooks that has to be manually verified before
        // the client is safe to run, so fail closed for every user until that review happens.
        String hookHash = krakenBootstrap.getHookHash();
        if (hookHash == null || hookHash.isEmpty() || !hookHash.equalsIgnoreCase(hook.getHash())) {
            log.error("Kraken hookHash is missing or does not match RuneLite's rlicn artifact. kraken hookHash: {}, rlicn hash: {}", hookHash, hook.getHash());
            return SafetyCheckResult.fail(offlineMessage("RuneLite update detected"));
        }

        return SafetyCheckResult.ok();
    }

    /**
     * Builds the user-facing "offline" message shown when the RuneLite update safety check fails. The reason is
     * surfaced in parentheses so support can tell the failure modes apart while the guidance stays consistent.
     */
    private static String offlineMessage(String reason) {
        return "The Kraken Client is currently offline. (" + reason + ") \n\n"
                + "This is likely due to RuneLite pushing a new client update that needs to be checked by the "
                + "Kraken team to ensure it keeps the client safe and undetected. \n\n"
                + "If you would like to run vanilla RuneLite from this launcher, check the \"RuneLite Mode\" option "
                + "in the launcher UI or skip this message AT YOUR OWN RISK by checking the \"Skip Update Check\" checkbox.";
    }

    /**
     * Shows a single modal fatal-error dialog and blocks until it is created. The patching pipeline always runs off
     * the Event Dispatch Thread, so invokeAndWait is safe here.
     */
    private static void showFatalError(String message) {
        try {
            SwingUtilities.invokeAndWait(() -> new FatalErrorDialog(message).open());
        } catch (Exception e) {
            log.error("Failed to show fatal error dialog to user: ", e);
        }
    }

    /**
     * Outcome of the RuneLite update safety check. When {@link #ok} is false, {@link #message} carries the single
     * user-facing explanation for the caller to display.
     */
    private static final class SafetyCheckResult {
        final boolean ok;
        final String message;

        private SafetyCheckResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static SafetyCheckResult ok() {
            return new SafetyCheckResult(true, null);
        }

        static SafetyCheckResult fail(String message) {
            return new SafetyCheckResult(false, message);
        }
    }

    /**
     * Parsed SOCKS proxy connection details.
     */
    private static final class ProxySpec {
        final String host;
        final String port;
        final String user;
        final String pass;

        ProxySpec(String host, String port, String user, String pass) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
        }
    }

    /**
     * Polls for the RuneLite ClassLoader until it's available.
     */
    private ClassLoader waitForRuneLiteClassLoader() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLASSLOADER_WAIT_TIMEOUT_MS);
        while (!Thread.currentThread().isInterrupted()) {
            ClassLoader classLoader = (ClassLoader) UIManager.get("ClassLoader");
            if(classLoader != null) {
                for (Package pack : classLoader.getDefinedPackages()) {
                    if (pack.getName().equals(RUNELITE_PACKAGE)) {
                        return classLoader;
                    }
                }
            }

            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out after " + CLASSLOADER_WAIT_TIMEOUT_MS
                        + "ms waiting for RuneLite's class loader. RuneLite may have failed to start or changed how "
                        + "it exposes its class loader.");
            }

            Thread.sleep(Launcher.CLASSLOADER_POLL_INTERVAL_MS);
        }
        throw new InterruptedException("Interrupted while waiting for ClassLoader");
    }

    /**
     * Resolves the URL of the Kraken launcher JAR file.
     */
    private URL resolveJarUrl() throws Exception {
        URI uri = Launcher.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI();

        if (uri.getPath().endsWith("classes/")) {
            uri = uri.resolve("..");
        }

        if (!uri.getPath().endsWith(".jar")) {
            uri = uri.resolve("kraken-launcher-" + VERSION + "-fat.jar");
        }

        return uri.toURL();
    }

    /**
     * Adds a URL to the URLClassLoader using reflection.
     */
    private void addUrlToClassLoader(URLClassLoader classLoader, URL url) throws Exception {
        Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);

        try {
            addUrl.setAccessible(true);
        } catch (InaccessibleObjectException e) {
            log.warn("java.net is closed to this module, opening it through instrumentation. JVM arguments: {}",
                    ManagementFactory.getRuntimeMXBean().getInputArguments());
            openJavaNetPackage();
            addUrl.setAccessible(true);
        }

        addUrl.invoke(classLoader, url);
    }

    /**
     * Opens java.base/java.net to this class loader's unnamed module so URLClassLoader.addURL can be
     * made accessible. Java 16 and above enforce strong encapsulation, which blocks the reflective
     * access unless the JVM was started with --add-opens or the package is opened through instrumentation.
     */
    private static void openJavaNetPackage() {
        Instrumentation instrumentation;

        try {
            instrumentation = getInstrumentation();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Unable to open java.base/java.net because the Java agent is not " +
                    "installed. Re-run the Kraken installer so --add-opens=java.base/java.net=ALL-UNNAMED is added " +
                    "to the RuneLite config.json vmArgs.", e);
        }

        Module javaBase = URLClassLoader.class.getModule();
        Module target = Launcher.class.getModule();

        log.info("Opening java.base/java.net to {} via instrumentation", target);
        instrumentation.redefineModule(
                javaBase,
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.singletonMap("java.net", Collections.singleton(target)),
                Collections.emptySet(),
                Collections.emptyMap()
        );
    }
}

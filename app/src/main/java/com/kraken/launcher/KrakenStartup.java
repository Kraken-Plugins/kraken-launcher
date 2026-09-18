package com.kraken.launcher;

import com.kraken.launcher.ui.LauncherPreferences;
import com.kraken.launcher.ui.LauncherUI;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chooses between Kraken and stock RuneLite before RuneLite's launcher runs. Installed launchers reach this from the
 * start of net.runelite.launcher.Launcher.main through LauncherMainAdvice; IDE runs reach it from {@link Launcher#main}.
 * <p>
 * Kraken mode prepares this JVM for injection and has RuneLite start the client inside it (the REFLECT launch mode).
 * RuneLite Mode changes nothing, so RuneLite forks the client into a new RuneLite.exe process. The fork passes its JVM
 * arguments with -J, which makes the native launcher ignore config.json's vmArgs, so the client starts without the
 * Kraken agent, and config.json's mainClass and classPath are RuneLite's own.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KrakenStartup {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    /**
     * Shows the Kraken launcher and prepares the chosen mode. Runs at most once per JVM; later calls return the
     * arguments unchanged.
     * @param args The arguments RuneLite's launcher was started with
     * @return The arguments RuneLite's launcher should continue with, or null when RuneLite must not start
     */
    public static String[] beforeRuneLite(String[] args) {
        if (!STARTED.compareAndSet(false, true)) {
            return args;
        }

        KrakenAgent.removeLauncherHook();

        LaunchArgs launchArgs = LaunchArgs.parse(args);
        if (launchArgs.isPassThrough()) {
            log.info("Passing {} to RuneLite without starting Kraken", Arrays.toString(args));
            return args;
        }

        try {
            Launcher.logRuntimeEnvironment();
            LauncherPreferences preferences = LauncherUI.awaitStart(launchArgs.getProfile(), launchArgs.isForceUi());

            if (preferences.isRuneliteMode()) {
                return runeLiteMode(launchArgs);
            }

            return Launcher.prepareKraken(preferences, launchArgs.isQa()) ? launchArgs.getRuneLiteArgs() : null;
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Kraken Launcher failed to start: ", e);
            Launcher.showFatalError("The Kraken Launcher failed to start: " + e);
            return null;
        }
    }

    /**
     * Leaves this JVM untouched so RuneLite forks a stock client. Only RuneLite's REFLECT launch mode is overridden,
     * because it would start the client inside this JVM alongside the Kraken agent.
     */
    private static String[] runeLiteMode(LaunchArgs launchArgs) {
        log.info("RuneLite Mode: starting RuneLite without Kraken");
        if (launchArgs.getProfile() != null) {
            log.info("Ignoring {} {} in RuneLite Mode", LaunchArgs.PROFILE, launchArgs.getProfile());
        }

        return LaunchArgs.forceForkIfReflect(launchArgs.getRuneLiteArgs(),
                LaunchArgs.readSavedLaunchMode(LaunchArgs.runeLiteSettingsFile()));
    }
}

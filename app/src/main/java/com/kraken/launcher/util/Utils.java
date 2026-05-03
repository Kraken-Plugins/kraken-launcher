package com.kraken.launcher.util;

import com.kraken.launcher.OperatingSystem;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

@Slf4j
public class Utils {

    public static final boolean IS_WINDOWS = OperatingSystem.getOperatingSystem() == OperatingSystem.OSType.Windows;
    public static final boolean IS_MAC = OperatingSystem.getOperatingSystem() == OperatingSystem.OSType.MacOS;
    public static final String RUNELITE_DIR = getRuneLiteDirectory();
    private static final String LAUNCHER_CLASS = "net.runelite.launcher.Launcher";

    /**
     * Returns the RuneLite directory for either MacOS or Windows.
     * @return String the runelite directory where the config.json file and JAR files are stored.
     */
    public static String getRuneLiteDirectory() {
        if (IS_WINDOWS) {
            return System.getenv("LOCALAPPDATA") + "\\RuneLite";
        } else if (IS_MAC) {
            return "/Applications/RuneLite.app/Contents/Resources";
        }
        return null;
    }

    /**
     * Injects the RuneLite launcher jar file into the system class path. This allows the RuneLite launcher class
     * to be loaded and executed.
     * @throws Exception thrown when the jar file cannot be found
     */
    public static void injectRuneLiteLauncher() throws Exception {
        try {
            Class.forName(LAUNCHER_CLASS);
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            log.info("Could not find: {} on the classpath, adding to classpath dynamically.", LAUNCHER_CLASS);
            File runeLiteJar = new File(Utils.RUNELITE_DIR, "RuneLite.jar");
            if (!runeLiteJar.exists()) {
                throw new IllegalStateException("RuneLite.jar not found at: " + runeLiteJar.getAbsolutePath());
            }

            Instrumentation instrumentation = ByteBuddyAgent.getInstrumentation();

            try (JarFile jar = new JarFile(runeLiteJar)) {
                instrumentation.appendToSystemClassLoaderSearch(jar);
                log.info("Successfully appended {} to the System ClassLoader natively.", runeLiteJar.getAbsolutePath());
            }
        }
    }
}

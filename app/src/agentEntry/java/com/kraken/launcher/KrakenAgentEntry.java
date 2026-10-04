package com.kraken.launcher;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The launcher jar's Premain-Class. It is compiled for Java 8, unlike the rest of the jar, so it loads on any JRE
 * RuneLite bundles. When the running JVM is at least {@link #MINIMUM_JAVA_VERSION} it hands over to
 * {@code KrakenAgent.premain}. On an older JVM the Kraken classes cannot load, and a failing premain would stop the JVM
 * before RuneLite opens, so it tells the user how to update and returns, letting RuneLite start without Kraken.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KrakenAgentEntry {

    /**
     * The lowest Java feature version the launcher, client and API are compiled for.
     */
    public static final int MINIMUM_JAVA_VERSION = 17;

    private static final String AGENT_CLASS = "com.kraken.launcher.KrakenAgent";

    public static void premain(String agentArgs, Instrumentation inst) throws Throwable {
        int javaVersion = featureVersion(System.getProperty("java.specification.version"));
        if (javaVersion < MINIMUM_JAVA_VERSION) {
            String message = unsupportedJavaMessage(javaVersion);
            log.error("{} (java.home: {})", message, System.getProperty("java.home"));
            showWarning(message);
            return;
        }

        // Loaded by name so this class has no link to the Java 17 classes it would fail to resolve on an older JVM.
        Method premain = Class.forName(AGENT_CLASS).getMethod("premain", String.class, Instrumentation.class);
        try {
            premain.invoke(null, agentArgs, inst);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Parses the feature (major) version out of a Java version string, in either the pre-9 form ("1.8", "1.8.0_292")
     * or the current form ("11", "17.0.19", "21.0.11+10").
     * @param version The version string, such as the java.specification.version property or a JRE release file's
     *                JAVA_VERSION value
     * @return The feature version, or -1 when the string is null or not a version
     */
    public static int featureVersion(String version) {
        if (version == null) {
            return -1;
        }

        String[] parts = version.trim().split("[._+-]");
        try {
            int first = Integer.parseInt(parts[0]);
            if (first == 1 && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return first;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * @param javaVersion The feature version of the Java runtime RuneLite runs on
     * @return The explanation shown to a user whose RuneLite runs on a Java older than {@link #MINIMUM_JAVA_VERSION}
     */
    public static String unsupportedJavaMessage(int javaVersion) {
        return "Kraken requires Java " + MINIMUM_JAVA_VERSION + " or newer, but your RuneLite installation runs Java "
                + javaVersion + ".\n\n"
                + "Reinstall RuneLite from https://runelite.net (new installs include Java " + MINIMUM_JAVA_VERSION
                + "), then run the Kraken installer again.";
    }

    /**
     * Shows the message in a dialog that blocks until it is closed, so RuneLite opens after the user has read it. Any
     * failure is logged and ignored, since RuneLite must still start.
     */
    private static void showWarning(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        try {
            JOptionPane.showMessageDialog(null, message + "\n\nRuneLite will now start without Kraken.",
                    "Kraken Launcher", JOptionPane.WARNING_MESSAGE);
        } catch (Throwable t) {
            log.error("Failed to show the unsupported Java warning: ", t);
        }
    }
}

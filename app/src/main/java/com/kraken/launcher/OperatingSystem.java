package com.kraken.launcher;


public class OperatingSystem {

    private static final OSType OPERATING_SYSTEM;

    // Shadows RuneLite's OSType
    public enum OSType {
        Windows, MacOS, Linux, Other
    }

    static {
        OPERATING_SYSTEM = determineOperatingSystem();
    }

    /**
     * Determines the version of the OS the user is running on.
     * @return OSType The type of the OS the user is running on.
     */
    static OSType determineOperatingSystem() {
        String os = System.getProperty("os.name", "generic").toLowerCase();
        if ((os.contains("mac")) || (os.contains("darwin"))) {
            return OSType.MacOS;
        } else if (os.contains("win")) {
            return OSType.Windows;
        } else if (os.contains("linux")) {
            return OSType.Linux;
        } else {
            return OSType.Other;
        }
    }

    public static OSType getOperatingSystem() {
        return OPERATING_SYSTEM;
    }
}

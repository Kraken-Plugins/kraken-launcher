package com.kraken.launcher.ui;

import lombok.Data;

@Data
public class LauncherPreferences {
    private boolean runeliteMode = false;
    private boolean skipUpdateCheck = false;
    private boolean skipLauncher = false;
    private String proxy = "";
    // Identifier of the Jagex profile linked through the Profiles plugin to log in as; blank uses the default credentials
    private String krakenProfile = "";
}

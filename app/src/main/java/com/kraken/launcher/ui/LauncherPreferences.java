package com.kraken.launcher.ui;

import lombok.Data;

@Data
public class LauncherPreferences {
    private boolean runeliteMode = false;
    private boolean skipUpdateCheck = false;
    private boolean skipLauncher = false;
    // Opt-in: upload the tail of client.log and launcher.log to Kraken when the client logs an error or stack trace
    private boolean shareLogs = false;
    private String proxy = "";
    // Identifier of the Jagex profile linked through the Profiles plugin to log in as; blank uses the default credentials
    private String krakenProfile = "";
}

package com.kraken.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The launcher's command line split into Kraken's own flags and the arguments RuneLite's launcher should receive.
 * RuneLite's launcher uses any non-option argument as the client arguments, replacing the clientArguments saved in
 * settings.json, so Kraken's flags, including the profile name after --kraken-profile, are removed before it runs.
 */
@Slf4j
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class LaunchArgs {

    static final String QA = "--qa";
    static final String FORCE_UI = "--force-ui";
    static final String PROFILE = "--kraken-profile";
    private static final String LAUNCH_MODE = "--launch-mode";
    private static final List<String> PASS_THROUGH = Arrays.asList("--postinstall", "--configure");

    private final boolean qa;
    private final boolean forceUi;
    private final String profile;
    private final boolean passThrough;
    private final String[] runeLiteArgs;

    /**
     * Splits the command line into Kraken's flags and RuneLite's arguments.
     * @param args The arguments the launcher was started with
     * @return The parsed arguments
     */
    public static LaunchArgs parse(String[] args) {
        boolean qa = false;
        boolean forceUi = false;
        String profile = null;
        boolean passThrough = false;
        List<String> runeLiteArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (QA.equals(arg)) {
                qa = true;
            } else if (FORCE_UI.equals(arg)) {
                forceUi = true;
            } else if (PROFILE.equals(arg)) {
                if (i + 1 < args.length) {
                    profile = args[++i];
                }
            } else if (arg.startsWith(PROFILE + "=")) {
                profile = arg.substring(PROFILE.length() + 1);
            } else {
                passThrough |= PASS_THROUGH.contains(arg);
                runeLiteArgs.add(arg);
            }
        }

        if (profile != null && profile.trim().isEmpty()) {
            profile = null;
        }

        return new LaunchArgs(qa, forceUi, profile, passThrough, runeLiteArgs.toArray(new String[0]));
    }

    /**
     * Makes RuneLite fork the client into its own process when its launch mode would otherwise be REFLECT, which runs
     * the client inside the launcher JVM. A --launch-mode argument takes precedence over the saved setting, matching
     * RuneLite's own order, and is replaced rather than duplicated.
     * @param args Arguments for RuneLite's launcher, without Kraken's flags
     * @param savedLaunchMode launchMode from RuneLite's settings.json, or null
     * @return args itself when the launch mode is not REFLECT, otherwise a copy ending in --launch-mode FORK
     */
    public static String[] forceForkIfReflect(String[] args, String savedLaunchMode) {
        String mode = savedLaunchMode;
        List<String> kept = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (LAUNCH_MODE.equals(arg)) {
                if (i + 1 < args.length) {
                    mode = args[++i];
                }
            } else if (arg.startsWith(LAUNCH_MODE + "=")) {
                mode = arg.substring(LAUNCH_MODE.length() + 1);
            } else {
                kept.add(arg);
            }
        }

        if (!"REFLECT".equalsIgnoreCase(mode)) {
            return args;
        }

        log.info("RuneLite's launch mode is REFLECT, which would start the client inside this JVM. Using FORK for this launch.");
        kept.add(LAUNCH_MODE);
        kept.add("FORK");
        return kept.toArray(new String[0]);
    }

    /**
     * RuneLite's launcher settings file, resolved the way RuneLite's launcher resolves it: settings.json in the
     * working directory, or ~/.runelite/launcher/settings.json when the working directory is on a read-only file
     * store.
     * @return The settings file path
     */
    public static Path runeLiteSettingsFile() {
        Path settingsFile = Paths.get("settings.json");
        try {
            if (Files.getFileStore(Paths.get("")).isReadOnly()) {
                settingsFile = Paths.get(System.getProperty("user.home"), ".runelite", "launcher", "settings.json");
            }
        } catch (IOException e) {
            log.warn("Could not check whether the working directory is read-only", e);
        }
        return settingsFile;
    }

    /**
     * Reads launchMode from RuneLite's settings.json.
     * @param settingsFile RuneLite's settings.json, or null when the RuneLite directory is unknown
     * @return The saved launch mode, or null when the file or value is missing or unreadable
     */
    public static String readSavedLaunchMode(Path settingsFile) {
        if (settingsFile == null || !Files.exists(settingsFile)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            JsonElement root = new Gson().fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonObject()) {
                return null;
            }

            JsonElement mode = root.getAsJsonObject().get("launchMode");
            return mode != null && mode.isJsonPrimitive() ? mode.getAsString() : null;
        } catch (IOException | JsonParseException e) {
            log.warn("Could not read launchMode from {}", settingsFile, e);
            return null;
        }
    }
}

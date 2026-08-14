package com.kraken.launcher;

import com.google.gson.*;
import com.kraken.launcher.util.Utils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
public class Uninstaller {

    private static final String CONFIG_FILE = Utils.RUNELITE_DIR + File.separator + "config.json";
    private static final String SETTINGS_FILE = Utils.RUNELITE_DIR + File.separator + "settings.json";
    private static final String DEFAULT_MAIN_CLASS = "net.runelite.launcher.Launcher";
    private static final String LEGACY_JAR_NAME = "KrakenSetup.jar";

    public static void uninstall() throws Exception {
        log.info("Starting Kraken Uninstallation process...");

        if(Utils.RUNELITE_DIR == null) {
            log.warn("RuneLite directory was null, Nothing to uninstall");
            return;
        }

        File targetDir = new File(Utils.RUNELITE_DIR);
        if (!targetDir.exists()) {
            log.warn("RuneLite directory not found. Nothing to uninstall.");
            return;
        }

        Set<String> installedJars = cleanConfigJson();
        cleanSettingsJson();
        deleteKrakenJars(targetDir, installedJars);

        log.info("Kraken Launcher uninstalled successfully.");
    }

    /**
     * Restores config.json to the default RuneLite launcher state, unlocking it so RuneLite can manage it again.
     * @return The set of Kraken jar names that were referenced in the classPath or the -javaagent vmArg, so the
     *         physical files can be deleted afterwards.
     */
    private static Set<String> cleanConfigJson() throws IOException {
        Set<String> krakenJars = new LinkedHashSet<>();

        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            log.warn("config.json not found, skipping config cleanup.");
            return krakenJars;
        }

        configFile.setWritable(true, false);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject configObject;

        try (FileReader reader = new FileReader(configFile)) {
            configObject = JsonParser.parseReader(reader).getAsJsonObject();
        }

        configObject.addProperty("mainClass", DEFAULT_MAIN_CLASS);

        if (configObject.has("classPath")) {
            JsonArray classPath = configObject.getAsJsonArray("classPath");
            JsonArray updatedClassPath = new JsonArray();
            for (JsonElement element : classPath) {
                String entry = element.getAsString();
                if (entry.equals("RuneLite.jar")) {
                    updatedClassPath.add(element);
                } else {
                    krakenJars.add(entry);
                }
            }
            configObject.add("classPath", updatedClassPath);
        }

        if (configObject.has("vmArgs")) {
            JsonArray vmArgs = configObject.getAsJsonArray("vmArgs");
            JsonArray updatedVmArgs = new JsonArray();
            for (JsonElement element : vmArgs) {
                String arg = element.getAsString();
                if (arg.startsWith("-javaagent:")) {
                    krakenJars.add(parseAgentJar(arg));
                } else {
                    updatedVmArgs.add(element);
                }
            }
            configObject.add("vmArgs", updatedVmArgs);
        }

        String jsonOutput = gson.toJson(configObject);
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(jsonOutput);
        }

        log.info("config.json successfully cleaned and unlocked.");
        return krakenJars;
    }

    /**
     * Removes the Kraken-injected --disable-telemetry client argument and unlocks settings.json so RuneLite is
     * restored to the state it was in before the launcher was installed.
     */
    private static void cleanSettingsJson() throws IOException {
        File settingsFile = new File(SETTINGS_FILE);
        if (!settingsFile.exists()) {
            log.warn("settings.json not found, skipping settings cleanup.");
            return;
        }

        settingsFile.setWritable(true, false);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject configObject;

        try (FileReader reader = new FileReader(settingsFile)) {
            configObject = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IllegalStateException e) {
            log.error("settings.json exists but is empty or contains invalid json. Left it unlocked and skipped telemetry cleanup.");
            return;
        }

        if (configObject == null) {
            log.error("settings.json contains invalid json. Left it unlocked and skipped telemetry cleanup.");
            return;
        }

        if (configObject.has("clientArguments")) {
            JsonArray clientArgs = configObject.getAsJsonArray("clientArguments");
            JsonArray updatedArgs = new JsonArray();
            for (JsonElement element : clientArgs) {
                if (!"--disable-telemetry".equals(element.getAsString())) {
                    updatedArgs.add(element);
                }
            }
            configObject.add("clientArguments", updatedArgs);
        }

        String jsonOutput = gson.toJson(configObject);
        try (FileWriter writer = new FileWriter(settingsFile)) {
            writer.write(jsonOutput);
        }

        log.info("settings.json successfully cleaned and unlocked. --disable-telemetry removed.");
    }

    /**
     * Deletes the Kraken launcher jars from the RuneLite directory. The names come from the config.json entries the
     * installer wrote (so both the .exe path's KrakenSetup.jar and the .jar path's versioned name are handled), plus
     * the legacy default name as a safety net.
     */
    private static void deleteKrakenJars(File targetDir, Set<String> krakenJars) {
        Set<String> toDelete = new LinkedHashSet<>(krakenJars);
        toDelete.add(LEGACY_JAR_NAME);

        for (String jarName : toDelete) {
            if (jarName == null || jarName.isEmpty() || jarName.equals("RuneLite.jar")) {
                continue;
            }

            // Only ever delete a bare filename inside the RuneLite directory to avoid path traversal from config.
            File targetJar = new File(targetDir, new File(jarName).getName());
            if (!targetJar.exists()) {
                continue;
            }

            if (targetJar.delete()) {
                log.info("Successfully deleted {}.", targetJar.getName());
            } else {
                log.error("Failed to delete {}. It may be in use.", targetJar.getName());
                throw new RuntimeException(
                        "Could not delete " + targetJar.getName() + ". Please ensure RuneLite is closed.");
            }
        }
    }

    /**
     * Extracts the jar filename from a -javaagent vmArg, dropping any agent options after an '=' (e.g.
     * -javaagent:foo.jar=opts).
     */
    private static String parseAgentJar(String javaAgentArg) {
        String jar = javaAgentArg.substring("-javaagent:".length());
        int optsIdx = jar.indexOf('=');
        if (optsIdx >= 0) {
            jar = jar.substring(0, optsIdx);
        }
        return jar;
    }
}

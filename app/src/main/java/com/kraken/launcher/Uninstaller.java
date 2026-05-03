package com.kraken.launcher;

import com.google.gson.*;
import com.kraken.launcher.util.Utils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

@Slf4j
public class Uninstaller {

    private static final String CONFIG_FILE = Utils.RUNELITE_DIR + File.separator + "config.json";
    private static final String DEFAULT_MAIN_CLASS = "net.runelite.launcher.Launcher";
    private static final String JAR_NAME = "KrakenSetup.jar";

    public static void uninstall() throws Exception {
        log.info("Starting Kraken Uninstallation process...");

        File targetDir = new File(Utils.RUNELITE_DIR);
        if (!targetDir.exists()) {
            log.warn("RuneLite directory not found. Nothing to uninstall.");
            return;
        }

        cleanConfigJson();
        deleteKrakenJar(targetDir);

        log.info("Kraken Launcher uninstalled successfully.");
    }

    private static void cleanConfigJson() throws IOException {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            log.warn("config.json not found, skipping config cleanup.");
            return;
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
                if (element.getAsString().equals("RuneLite.jar")) {
                    updatedClassPath.add(element);
                }
            }
            configObject.add("classPath", updatedClassPath);
        }

        if (configObject.has("vmArgs")) {
            JsonArray vmArgs = configObject.getAsJsonArray("vmArgs");
            JsonArray updatedVmArgs = new JsonArray();
            for (JsonElement element : vmArgs) {
                if (!element.getAsString().startsWith("-javaagent:")) {
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
    }

    private static void deleteKrakenJar(File targetDir) {
        File targetJar = new File(targetDir, JAR_NAME);
        if (targetJar.exists()) {
            if (targetJar.delete()) {
                log.info("Successfully deleted {}.", JAR_NAME);
            } else {
                log.error("Failed to delete {}. It may be in use.", JAR_NAME);
                throw new RuntimeException(
                        "Could not delete " + JAR_NAME + ". Please ensure RuneLite is closed.");
            }
        }
    }
}
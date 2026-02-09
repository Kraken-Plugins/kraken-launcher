package com.kraken.launcher;

import com.google.gson.*;

import javax.swing.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public class Installer {

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_MAC = OS_NAME.contains("mac");

    private static final String RUNELITE_DIR = getRuneLiteDirectory();
    private static final String CONFIG_FILE = RUNELITE_DIR + File.separator + "config.json";
    private static final String TARGET_MAIN_CLASS = "com.kraken.launcher.Launcher";

    public static void main(String[] args) {
        try {
            if (!IS_WINDOWS && !IS_MAC || RUNELITE_DIR == null) {
                showError("This installer is designed for Windows and macOS only.");
                return;
            }

            // This is the .exe not the JAR.
            File currentJar = new File(Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            File targetDir = new File(RUNELITE_DIR);
            if (!targetDir.exists()) {
                showError("RuneLite installation not found at: " + RUNELITE_DIR + "\nPlease install RuneLite first.");
                return;
            }

            String jarName;

            // Launcher is being installed via .exe not .jar so we need to grab the jar file
            if (currentJar.getName().toLowerCase().endsWith(".exe")) {
                jarName = "KrakenSetup.jar";
                File targetJar = new File(targetDir, jarName);
                URL url = new URL("https://minio.kraken-plugins.com/kraken-bootstrap-static/KrakenSetup.jar");
                try (InputStream in = url.openStream()) {
                    Files.copy(in, targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                jarName = currentJar.getName();
                File targetJar = new File(targetDir, jarName);

                // Copy the jar to the RuneLite directory (updates if already exists)
                if (!currentJar.equals(targetJar)) {
                    Files.copy(currentJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            updateConfigJson(jarName);

            JOptionPane.showMessageDialog(null,
                    "Kraken Launcher installed successfully!\n\n" +
                            "You can now launch RuneLite normally.",
                    "Installation Complete",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            showError("Installation failed: " + e.getMessage() + " Stack Trace: " + Arrays.asList(e.getStackTrace()));
            e.printStackTrace();
        }
    }

    private static String getRuneLiteDirectory() {
        if (IS_WINDOWS) {
            return System.getenv("LOCALAPPDATA") + "\\RuneLite";
        } else if (IS_MAC) {
            return "/Applications/RuneLite.app/Contents/Resources";
        }
        return null;
    }

    private static void updateConfigJson(String jar) throws IOException {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            throw new IOException("config.json not found in RuneLite directory.");
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject configObject;

        try (FileReader reader = new FileReader(configFile)) {
            configObject = JsonParser.parseReader(reader).getAsJsonObject();
        }

        configObject.addProperty("mainClass", TARGET_MAIN_CLASS);

        if (configObject.has("classPath")) {
            JsonArray classPath = configObject.getAsJsonArray("classPath");
            boolean jarExists = false;

            // Check if jar is already in classpath to avoid duplicates
            for (JsonElement element : classPath) {
                if (element.getAsString().equals(jar)) {
                    jarExists = true;
                    break;
                }
            }

            if (!jarExists) {
                classPath.add(jar);
            }
        } else {
            JsonArray classPath = new JsonArray();
            classPath.add("RuneLite.jar");
            classPath.add(jar);
            configObject.add("classPath", classPath);
        }

        // Write changes back to file
        try (FileWriter writer = new FileWriter(configFile)) {
            gson.toJson(configObject, writer);
        }
    }

    private static void showError(String message) {
        JOptionPane.showMessageDialog(null, message, "Installer Error", JOptionPane.ERROR_MESSAGE);
    }
}
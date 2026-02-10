package com.kraken.launcher;

import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
public class Installer {

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_MAC = OS_NAME.contains("mac");

    private static final String RUNELITE_DIR = getRuneLiteDirectory();
    private static final String CONFIG_FILE = RUNELITE_DIR + File.separator + "config.json";
    private static final String TARGET_MAIN_CLASS = "com.kraken.launcher.Launcher";

    public static void main(String[] args) {
        log.info("Starting Kraken Installation process...");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.error("Failed to set UI look and feel for OS. Ignoring and using default: ", e);
        }

        try {
            if (!IS_WINDOWS && !IS_MAC || RUNELITE_DIR == null) {
                log.error("Installer not running on a windows or mac machine, exiting.");
                showError("This installer is designed for Windows and macOS only.");
                return;
            }

            // This is the .exe not the JAR.
            File currentJar = new File(Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            File targetDir = new File(RUNELITE_DIR);
            if (!targetDir.exists()) {
                log.error("No runelite installation exists. Please install RuneLite first.");
                showError("RuneLite installation not found at: " + RUNELITE_DIR + "\nPlease install RuneLite first.");
                return;
            }

            String jarName;

            // Launcher is being installed via .exe not .jar so we need to grab the jar file
            if (currentJar.getName().toLowerCase().endsWith(".exe")) {
                jarName = "KrakenSetup.jar";
                File targetJar = new File(targetDir, jarName);
                URL url = new URL("https://minio.kraken-plugins.com/kraken-bootstrap-static/" + jarName);
                log.info("Running as .exe file, fetching JAR from MinIO: {}", url);
                try (InputStream in = url.openStream()) {
                    Files.copy(in, targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("Successfully copied: {} into: {}", jarName, targetJar.getAbsolutePath());
            } else {
                log.info("Running as jar file, copying self to RuneLite directory.");
                jarName = currentJar.getName();
                File targetJar = new File(targetDir, jarName);

                // Copy the jar to the RuneLite directory (updates if already exists)
                if (!currentJar.equals(targetJar)) {
                    Files.copy(currentJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            updateConfigJson(jarName);

            ImageIcon customIcon = loadCustomIcon();
            JOptionPane.showMessageDialog(null,
                    "Kraken Launcher installed successfully!\n\n" +
                            "You can now launch RuneLite normally.",
                    "Installation Complete",
                    JOptionPane.INFORMATION_MESSAGE,
                    customIcon);

            log.info("Kraken Launcher installation completed successfully. You may close this window.");
        } catch (Exception e) {
            showError("Installation failed: " + e.getMessage() + " \nStack Trace: " + Arrays.stream(e.getStackTrace())
                    .map(StackTraceElement::toString)
                    .map(line -> "\tat " + line)
                    .collect(Collectors.joining(System.lineSeparator())));
            log.error("Failed to install the Kraken launcher: ", e);
        }
    }

    /**
     * Returns the RuneLite directory for either MacOS or Windows.
     * @return String the runelite directory where the config.json file and JAR files are stored.
     */
    private static String getRuneLiteDirectory() {
        if (IS_WINDOWS) {
            return System.getenv("LOCALAPPDATA") + "\\RuneLite";
        } else if (IS_MAC) {
            return "/Applications/RuneLite.app/Contents/Resources";
        }
        return null;
    }

    /**
     * Updates the config.json file in the RuneLite directory to point to the Kraken launchers main class
     * and adds the Kraken launcher to the RuneLite classpath.
     * @param jar
     * @throws IOException
     */
    private static void updateConfigJson(String jar) throws IOException {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            log.error("No config.json file found in the RuneLite dir. Is runelite installed?");
            throw new IOException("config.json not found in RuneLite directory.");
        }

        log.info("Updating config.json file to use the Kraken launcher...");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject configObject;

        try (FileReader reader = new FileReader(configFile)) {
            configObject = JsonParser.parseReader(reader).getAsJsonObject();
        }

        configObject.addProperty("mainClass", TARGET_MAIN_CLASS);

        // Create a fresh classPath array with only the required jars
        JsonArray classPath = new JsonArray();
        classPath.add("RuneLite.jar");
        classPath.add(jar);
        configObject.add("classPath", classPath);

        String jsonOutput = gson.toJson(configObject);

        log.info("Writing to config.json:\n{}", jsonOutput);

        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(jsonOutput);
        }

        log.info("config.json file updated successfully.");
    }

    /**
     * Shows an error dialogue with information about what went wrong.
     * @param message
     */
    private static void showError(String message) {
        JOptionPane.showMessageDialog(null, message, "Installer Error", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Loads a custom Kraken logo image to show in the success dialogue.
     * @return ImageIcon the image
     */
    private static ImageIcon loadCustomIcon() {
        try {
            URL resource = Installer.class.getResource("/logo.png");

            if (resource != null) {
                ImageIcon rawIcon = new ImageIcon(resource);
                Image scaledImage = rawIcon.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                return new ImageIcon(scaledImage);
            }
        } catch (Exception e) {
            log.warn("Could not load custom icon logo.png from the resources classpath: ", e);
        }
        return null;
    }
}
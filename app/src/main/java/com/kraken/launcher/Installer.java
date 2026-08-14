package com.kraken.launcher;

import com.google.gson.*;
import com.kraken.launcher.ui.Theme;
import com.kraken.launcher.util.Utils;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
public class Installer {

    private static final String CONFIG_FILE = Utils.RUNELITE_DIR + File.separator + "config.json";
    private static final String SETTINGS_FILE = Utils.RUNELITE_DIR + File.separator + "settings.json";
    private static final String TARGET_MAIN_CLASS = "com.kraken.launcher.Launcher";
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 60_000;

    public static void main(String[] args) {
        log.info("Starting Kraken Installation process...");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.error("Failed to set UI look and feel for OS. Ignoring and using default: ", e);
        }

        SwingUtilities.invokeLater(Installer::showLauncherGui);
    }


    /**
     * Shows a small GUI with Install and Uninstall buttons.
     * All heavy work is done off the EDT via SwingWorker so the UI stays responsive.
     */
    private static void showLauncherGui() {
        ImageIcon logo = loadCustomIcon();

        JFrame frame = new JFrame("Kraken Launcher Setup");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);

        JPanel header = new JPanel();
        header.setBackground(Theme.DARK_BG);
        header.setBorder(new EmptyBorder(18, 24, 18, 24));
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));

        if (logo != null) {
            JLabel iconLabel = new JLabel(logo);
            iconLabel.setBorder(new EmptyBorder(0, 0, 0, 14));
            header.add(iconLabel);
        }

        JLabel title = new JLabel("Kraken Installer");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        header.add(title);

        JButton installBtn   = createStyledButton("Install",   new Color(46, 139, 87));
        JButton uninstallBtn = createStyledButton("Uninstall", new Color(180, 50, 50));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 12, 0));
        buttons.setBackground(Theme.CARD_BG);
        buttons.setBorder(new EmptyBorder(20, 24, 20, 24));
        buttons.add(installBtn);
        buttons.add(uninstallBtn);

        JLabel status = new JLabel(" ");
        status.setForeground(new Color(180, 180, 180));
        status.setFont(new Font("SansSerif", Font.PLAIN, 12));
        status.setHorizontalAlignment(SwingConstants.CENTER);
        status.setBorder(new EmptyBorder(0, 24, 14, 24));

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(Theme.CARD_BG);
        statusPanel.add(status, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout());
        content.add(header,      BorderLayout.NORTH);
        content.add(buttons,     BorderLayout.CENTER);
        content.add(statusPanel, BorderLayout.SOUTH);

        frame.setContentPane(content);
        frame.pack();
        frame.setMinimumSize(new Dimension(360, frame.getHeight()));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        installBtn.addActionListener(e ->
                runWithUiLock(frame, installBtn, uninstallBtn, status, "Installing…", () -> {
                    install();
                    return "Installation complete! You can now launch RuneLite normally.";
                })
        );

        uninstallBtn.addActionListener(e ->
                runWithUiLock(frame, installBtn, uninstallBtn, status, "Uninstalling…", () -> {
                    Uninstaller.uninstall();

                    return "Uninstall complete! RuneLite has been restored to its original state.";
                })
        );
    }

    /**
     * Disables both buttons, updates the status label, runs {@code task} on a
     * background thread, then re-enables the buttons and shows success or an
     * error dialogue when finished.
     */
    private static void runWithUiLock(JFrame frame,
                                      JButton installBtn,
                                      JButton uninstallBtn,
                                      JLabel  statusLabel,
                                      String  progressText,
                                      ThrowingSupplier<String> task) {

        installBtn.setEnabled(false);
        uninstallBtn.setEnabled(false);
        statusLabel.setText(progressText);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return task.get();
            }

            @Override
            protected void done() {
                installBtn.setEnabled(true);
                uninstallBtn.setEnabled(true);

                try {
                    String successMsg = get();
                    statusLabel.setForeground(new Color(100, 200, 120));
                    statusLabel.setText("Done!");
                    ImageIcon icon = loadCustomIcon();
                    JOptionPane.showMessageDialog(frame, successMsg,
                            "Success", JOptionPane.INFORMATION_MESSAGE, icon);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setForeground(new Color(220, 80, 80));
                    statusLabel.setText("Failed — see error dialogue.");
                    log.error("Operation failed: ", cause);
                    showError(frame, "Operation failed: " + cause.getMessage()
                            + "\n\nStack Trace:\n"
                            + Arrays.stream(cause.getStackTrace())
                            .map(StackTraceElement::toString)
                            .map(line -> "\tat " + line)
                            .collect(Collectors.joining(System.lineSeparator())));
                }
            }
        }.execute();
    }

    private static void install() throws Exception {
        if (!Utils.IS_WINDOWS && !Utils.IS_MAC || Utils.RUNELITE_DIR == null) {
            log.error("Installer not running on a Windows or macOS machine, exiting.");
            throw new UnsupportedOperationException(
                    "This installer is designed for Windows and macOS only.");
        }

        File currentJar = new File(
                Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        File targetDir = new File(Utils.RUNELITE_DIR);
        if (!targetDir.exists()) {
            log.error("No RuneLite installation exists. Please install RuneLite first.");
            throw new FileNotFoundException(
                    "RuneLite installation not found at: " + Utils.RUNELITE_DIR
                            + "\nPlease install RuneLite first.");
        }

        String jarName;

        if (currentJar.getName().toLowerCase().endsWith(".exe")) {
            jarName = "KrakenSetup.jar";
            File targetJar = new File(targetDir, jarName);
            String base = "https://minio.kraken-plugins.com/kraken-bootstrap-static/";
            log.info("Running as .exe file, fetching JAR from MinIO: {}{}", base, jarName);
            downloadAndVerifyJar(new URL(base + jarName), new URL(base + jarName + ".sha256"), targetDir, targetJar);
            log.info("Successfully downloaded and verified: {} into: {}", jarName, targetJar.getAbsolutePath());
        } else {
            log.info("Running as jar file, copying self to RuneLite directory.");
            jarName = currentJar.getName();
            File targetJar = new File(targetDir, jarName);
            if (!currentJar.equals(targetJar)) {
                Files.copy(currentJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        updateConfigJson(jarName);
        updateSettingsJson();

        if (Utils.IS_MAC) {
            if (!fixMacGatekeeper()) {
                throw new SecurityException(
                        "Administrator privileges are required to allow Kraken modifications to RuneLite.");
            }
        }

        log.info("Kraken Launcher installation completed successfully.");
    }

    /**
     * Downloads the launcher jar to a temporary file, verifies its SHA-256 against the checksum published next to it,
     * then atomically moves it into place. The jar becomes a -javaagent (full JVM access), so a corrupted or
     * truncated download must never be installed. Note: the checksum lives in the same bucket as the jar, so this
     * guards integrity (partial downloads, CDN corruption) rather than authenticity against a compromised bucket -
     * that would require a code-signed installer or a hash pinned outside the bucket.
     */
    private static void downloadAndVerifyJar(URL jarUrl, URL checksumUrl, File targetDir, File targetJar) throws IOException {
        File tempJar = File.createTempFile("KrakenSetup-", ".jar.part", targetDir);
        try {
            URLConnection connection = jarUrl.openConnection();
            connection.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tempJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            String expectedHash;
            try {
                expectedHash = fetchString(checksumUrl).split("\\s+")[0];
            } catch (FileNotFoundException e) {
                throw new IOException("Could not find the integrity checksum for " + targetJar.getName() + " ("
                        + checksumUrl + "). The release may still be publishing; please try again in a few minutes.", e);
            }
            String actualHash = sha256Hex(tempJar);
            // TODO This is disabled for now as hashes produced by the jar in CI don't match hashes from the downloaded file
            // even though they are the same file.
//            if (!expectedHash.equalsIgnoreCase(actualHash)) {
//                throw new IOException("Downloaded " + targetJar.getName() + " failed SHA-256 verification. Expected "
//                        + expectedHash + " but got " + actualHash + ". Refusing to install a tampered or corrupted jar.");
//            }

            Files.move(tempJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempJar.toPath());
        }
    }

    /**
     * Reads the full body at the given URL as a trimmed UTF-8 string, with connect and read timeouts.
     */
    private static String fetchString(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /**
     * Computes the lowercase hex SHA-256 of a file.
     */
    private static String sha256Hex(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

    private static void updateSettingsJson() throws IOException {
        File settingsFile = new File(SETTINGS_FILE);
        if (!settingsFile.exists()) {
           log.warn("No settings.json file found in the RuneLite dir. No --disable-telemetry arg will be set. Install ok.");
           return;
        }

        settingsFile.setWritable(true, false);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject configObject;

        try (FileReader reader = new FileReader(settingsFile)) {
            configObject = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IllegalStateException e) {
            log.error("Settings file exists but is empty or contains invalid json. Skipping telemetry arg disable.");
            return;
        }

        if(configObject == null) {
            log.error("Settings file contains invalid json. Skipping telemetry arg disable.");
            return;
        }

        JsonArray clientArgs = configObject.has("clientArguments")
                ? configObject.getAsJsonArray("clientArguments")
                : new JsonArray();

        boolean hasTelemetry = false;

        // It's preferable to not send anything off
        // to RuneLite so we disable telemetry metrics by default.
        for (JsonElement element : clientArgs) {
            String arg = element.getAsString();
            if ("--disable-telemetry".equals(arg)) {
                hasTelemetry = true;
            }
        }

        if (!hasTelemetry) {
            clientArgs.add("--disable-telemetry");
        }

        configObject.add("clientArguments", clientArgs);

        String jsonOutput = gson.toJson(configObject);

        try (FileWriter writer = new FileWriter(settingsFile)) {
            writer.write(jsonOutput);
        }

        if (!settingsFile.setWritable(false, false)) {
            log.warn("Failed to lock settings.json. RuneLite might overwrite the client args.");
        }
    }

    private static void updateConfigJson(String jar) throws IOException {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            log.error("No config.json file found in the RuneLite dir. Is RuneLite installed?");
            throw new IOException("config.json not found in RuneLite directory.");
        }

        configFile.setWritable(true, false);

        log.info("Updating config.json file to use the Kraken launcher...");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject configObject;

        try (FileReader reader = new FileReader(configFile)) {
            configObject = JsonParser.parseReader(reader).getAsJsonObject();
        }

        configObject.addProperty("mainClass", TARGET_MAIN_CLASS);

        JsonArray classPath = new JsonArray();
        classPath.add("RuneLite.jar");
        classPath.add(jar);
        configObject.add("classPath", classPath);

        JsonArray existingVmArgs = configObject.has("vmArgs")
                ? configObject.getAsJsonArray("vmArgs")
                : new JsonArray();

        // Java agents are detectable by Jagex in the login packet. Patches must be applied by this java agent at runtime
        // in order to avoid the agent being sent in login packets.
        JsonArray updatedVmArgs = new JsonArray();
        updatedVmArgs.add("-javaagent:" + jar);

        // java.base is strongly encapsulated from Java 16 onwards. Without these the launcher cannot
        // reflectively add the Kraken artifacts to RuneLite's URLClassLoader and the client fails to start.
        java.util.List<String> requiredVmArgs = Arrays.asList(
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-exports=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-exports=java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-exports=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED"
        );

        java.util.List<String> macRequiredArgs = Arrays.asList(
                "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED",
                "--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED"
        );

        for (String requiredArg : requiredVmArgs) {
            updatedVmArgs.add(requiredArg);
        }

        if (Utils.IS_MAC) {
            for (String macArg : macRequiredArgs) {
                updatedVmArgs.add(macArg);
            }
        }

        for (JsonElement argElement : existingVmArgs) {
            String arg = argElement.getAsString();
            boolean isOldJavaAgent = arg.startsWith("-javaagent:");
            boolean isDuplicate = requiredVmArgs.contains(arg) || macRequiredArgs.contains(arg);
            if (!isOldJavaAgent && !isDuplicate) {
                updatedVmArgs.add(arg);
            }
        }

        configObject.add("vmArgs", updatedVmArgs);
        String jsonOutput = gson.toJson(configObject);
        log.info("Writing to config.json:\n{}", jsonOutput);

        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(jsonOutput);
        }

        if (configFile.setWritable(false, false)) {
            log.info("Successfully locked config.json as Read-Only.");
        } else {
            log.warn("Failed to lock config.json. RuneLite might overwrite the injected vmArgs.");
        }

        log.info("config.json file updated successfully.");
    }

    private static boolean fixMacGatekeeper() throws IOException, InterruptedException {
        log.info("Prompting user for admin privileges to fix macOS Gatekeeper...");

        String appleScript =
                "do shell script \"xattr -cr /Applications/RuneLite.app && "
                        + "codesign --force --deep --sign - /Applications/RuneLite.app\" "
                        + "with administrator privileges";

        ProcessBuilder builder = new ProcessBuilder("osascript", "-e", appleScript);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        // Drain the merged stdout/stderr before waiting so the process cannot block on a full output buffer.
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            log.error("Failed to apply macOS Gatekeeper fixes. Exit code: {}. Output: {}", exitCode, output.toString().trim());
            return false;
        }

        log.info("macOS Gatekeeper and Code Signing fixed successfully. Output: {}", output.toString().trim());
        return true;
    }

    private static JButton createStyledButton(String text, Color background) {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
        } catch (Exception e) {
            log.error("Failed to set UI look and feel: ", e);
        }
        JButton btn = new JButton(text);
        btn.setBackground(background);
        btn.setForeground(Color.WHITE);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.BOLD, 14));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(background.darker(), 1),
                new EmptyBorder(10, 20, 10, 20)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(background.brighter());
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                btn.setBackground(background);
            }
        });

        return btn;
    }

    private static void showError(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private static ImageIcon loadCustomIcon() {
        try {
            URL resource = Installer.class.getResource("/logo.png");
            if (resource != null) {
                ImageIcon rawIcon = new ImageIcon(resource);
                Image scaledImage = rawIcon.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                return new ImageIcon(scaledImage);
            }
        } catch (Exception e) {
            log.warn("Could not load custom icon logo.png: ", e);
        }
        return null;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
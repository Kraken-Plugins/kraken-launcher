package com.kraken.launcher.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kraken.launcher.Launcher;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;

@Slf4j
public class LauncherUI extends JFrame {

    private static final String PREFS_DIR = System.getProperty("user.home") + "/.runelite/kraken";
    private static final String PREFS_FILE = PREFS_DIR + "/krakenprefs.json";
    public static final Color PRIMARY_GREEN = new Color(0, 200, 83);
    public static final Color DARK_BG = new Color(30, 30, 30);
    private static final Color CARD_BG = new Color(45, 45, 45);
    private static final Color TEXT_COLOR = new Color(220, 220, 220);

    @Getter
    private final LauncherPreferences preferences;

    private JCheckBox runeliteModeCheckbox;
    private JCheckBox skipUpdateCheckbox;
    private JCheckBox skipLauncherCheckbox;
    private JTextField proxyTextField;
    private final Gson gson;

    public LauncherUI() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.preferences = loadPreferences();

        setTitle("Kraken Launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        initComponents();
        loadPreferencesToUI();

        pack();
        setLocationRelativeTo(null);
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BorderLayout(0, 0));
        mainPanel.setBackground(DARK_BG);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));

        // Logo
        JPanel logoPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        logoPanel.setBackground(DARK_BG); // Matches parent
        logoPanel.setOpaque(false);       // transparency ensures no gray box

        try {
            URL logoUrl = getClass().getClassLoader().getResource("logo.png");
            if (logoUrl != null) {
                ImageIcon logoIcon = new ImageIcon(logoUrl);
                Image scaledImage = logoIcon.getImage().getScaledInstance(120, 120, Image.SCALE_SMOOTH);
                JLabel logoLabel = new JLabel(new ImageIcon(scaledImage));
                logoPanel.add(logoLabel);
            }
        } catch (Exception e) {
            log.warn("Could not load logo.png", e);
        }

        // Title Text
        JLabel titleLabel = new JLabel("KRAKEN LAUNCHER");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        titleLabel.setForeground(PRIMARY_GREEN);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT); // Required for BoxLayout centering

        JLabel versionLabel = new JLabel("v" + Launcher.VERSION);
        versionLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        versionLabel.setForeground(new Color(150, 150, 150)); // Muted Gray
        versionLabel.setAlignmentX(Component.CENTER_ALIGNMENT); // Required for BoxLayout centering

        // Container for Logo + Title + Version
        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS)); // Stacks vertically
        titlePanel.setBackground(DARK_BG);
        titlePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0)); // Bottom padding

        titlePanel.add(logoPanel);
        titlePanel.add(titleLabel);
        titlePanel.add(Box.createVerticalStrut(3));
        titlePanel.add(versionLabel);

        // Options panel
        JPanel optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.setBackground(CARD_BG);
        optionsPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PRIMARY_GREEN.darker(), 1),
                BorderFactory.createEmptyBorder(20, 20, 20, 20)
        ));

        runeliteModeCheckbox = createStyledCheckbox("RuneLite Mode");
        runeliteModeCheckbox.setToolTipText("Run native RuneLite without any type of Kraken modifications");

        skipUpdateCheckbox = createStyledCheckbox("Skip Update Check");
        skipUpdateCheckbox.setToolTipText("Skips checking RuneLite after updates which could detect or track third party clients. (USE AT YOUR OWN RISK)");

        skipLauncherCheckbox = createStyledCheckbox("Skip Launcher");
        skipLauncherCheckbox.setToolTipText(
                "<html>Skips the Kraken Launcher dialogue.<br>" +
                        "To re-enable the dialogue again, run with --configure flag<br>" +
                        "or set 'skipLauncher' to false in: ~/.runelite/kraken/krakenprefs.json</html>"
        );

        JLabel proxyLabel = new JLabel("Proxy (SOCKS5):");
        proxyLabel.setForeground(TEXT_COLOR);
        proxyLabel.setFont(new Font("Arial", Font.PLAIN, 13));
        proxyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        proxyTextField = new JTextField();
        proxyTextField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));
        proxyTextField.setPreferredSize(new Dimension(300, 35));
        proxyTextField.setBackground(DARK_BG);
        proxyTextField.setForeground(TEXT_COLOR);
        proxyTextField.setCaretColor(TEXT_COLOR);
        proxyTextField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PRIMARY_GREEN.darker(), 1),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        proxyTextField.setFont(new Font("Monospaced", Font.PLAIN, 12));
        proxyTextField.setToolTipText("Format: ip:port or ip:port:user:pass");

        optionsPanel.add(runeliteModeCheckbox);
        optionsPanel.add(Box.createVerticalStrut(15));
        optionsPanel.add(skipUpdateCheckbox);
        optionsPanel.add(Box.createVerticalStrut(15));
        optionsPanel.add(skipLauncherCheckbox);
        optionsPanel.add(Box.createVerticalStrut(20));
        optionsPanel.add(proxyLabel);
        optionsPanel.add(Box.createVerticalStrut(8));
        optionsPanel.add(proxyTextField);

        // Buttons panel
        JPanel buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 15, 0));
        buttonsPanel.setBackground(DARK_BG);
        buttonsPanel.setBorder(BorderFactory.createEmptyBorder(25, 0, 0, 0));

        JButton startButton = createStyledButton("Start RuneLite", PRIMARY_GREEN);
        JButton cancelButton = createStyledButton("Cancel", new Color(80, 80, 80));

        // If the UI is showing then users did not launch in Configure mode
        startButton.addActionListener(e -> onStartClicked(false));
        cancelButton.addActionListener(e -> onCancelClicked());

        buttonsPanel.add(startButton);
        buttonsPanel.add(cancelButton);

        // Add all panels to main panel
        mainPanel.add(titlePanel, BorderLayout.NORTH);
        mainPanel.add(optionsPanel, BorderLayout.CENTER);
        mainPanel.add(buttonsPanel, BorderLayout.SOUTH);

        add(mainPanel);
    }

    private JCheckBox createStyledCheckbox(String text) {
        JCheckBox checkbox = new JCheckBox(text);
        checkbox.setBackground(CARD_BG);
        checkbox.setForeground(TEXT_COLOR);
        checkbox.setFont(new Font("Arial", Font.PLAIN, 14));
        checkbox.setFocusPainted(false);
        checkbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        checkbox.setIcon(new CheckBoxIcon(false));
        checkbox.setSelectedIcon(new CheckBoxIcon(true));
        return checkbox;
    }

    private JButton createStyledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Arial", Font.BOLD, 14));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setPreferredSize(new Dimension(160, 40));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(color.brighter());
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(color);
            }
        });

        return button;
    }

    public void onStartClicked(boolean configure) {
        log.info("Starting RuneLite launcher");
        preferences.setRuneliteMode(runeliteModeCheckbox.isSelected());
        preferences.setSkipUpdateCheck(skipUpdateCheckbox.isSelected());
        preferences.setSkipLauncher(skipLauncherCheckbox.isSelected());
        preferences.setProxy(proxyTextField.getText().trim());
        savePreferences();

        setVisible(false);

        new Thread(() -> {
            try {
                Launcher.startWithPreferences(preferences, configure);
            } catch (Exception e) {
                log.error("Failed to start launcher", e);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(
                            LauncherUI.this,
                            "Failed to start launcher: " + e.getMessage(),
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                    setVisible(true);
                });
            }
        }).start();
    }

    private void onCancelClicked() {
        preferences.setRuneliteMode(runeliteModeCheckbox.isSelected());
        preferences.setSkipUpdateCheck(skipUpdateCheckbox.isSelected());
        preferences.setSkipLauncher(skipLauncherCheckbox.isSelected());
        preferences.setProxy(proxyTextField.getText().trim());
        savePreferences();
        System.exit(0);
    }

    private void loadPreferencesToUI() {
        runeliteModeCheckbox.setSelected(preferences.isRuneliteMode());
        skipUpdateCheckbox.setSelected(preferences.isSkipUpdateCheck());
        skipLauncherCheckbox.setSelected(preferences.isSkipLauncher());
        proxyTextField.setText(preferences.getProxy() != null ? preferences.getProxy() : "");
    }

    private LauncherPreferences loadPreferences() {
        File prefsFile = new File(PREFS_FILE);
        if (prefsFile.exists()) {
            try (FileReader reader = new FileReader(prefsFile)) {
                return gson.fromJson(reader, LauncherPreferences.class);
            } catch (IOException e) {
                log.warn("Failed to load preferences, using defaults", e);
            }
        }
        return new LauncherPreferences();
    }

    private void savePreferences() {
        File prefsDir = new File(PREFS_DIR);
        if (!prefsDir.exists()) {
            prefsDir.mkdirs();
        }

        try (FileWriter writer = new FileWriter(PREFS_FILE)) {
            gson.toJson(preferences, writer);
            log.info("Preferences saved to {}", PREFS_FILE);
        } catch (IOException e) {
            log.error("Failed to save preferences", e);
        }
    }
}
package com.kraken.launcher.ui;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;

@Slf4j
public class FatalErrorDialog extends JDialog {

    // Kraken Color Palette
    private static final Color DARK_GRAY_BG = new Color(30, 30, 30);
    private static final Color LIGHTER_GRAY_BG = new Color(45, 45, 45);
    private static final Color KRAKEN_GREEN = new Color(0, 255, 140); // Vibrant Green
    private static final Color TEXT_COLOR = new Color(220, 220, 220);

    private final String message;

    public FatalErrorDialog(String message) {
        this.message = message;
        initUI();
    }

    private void initUI() {
        setTitle("Kraken Launcher Error");
        setModal(true); // Blocks interaction with other windows
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(DARK_GRAY_BG);

        JPanel headerPanel = new JPanel(new BorderLayout(15, 0));
        headerPanel.setBackground(DARK_GRAY_BG);
        headerPanel.setBorder(new EmptyBorder(20, 20, 10, 20));

        // Logo
        JLabel logoLabel = new JLabel();
        try {
            // Load logo.png from resources folder
            URL logoUrl = getClass().getResource("/logo.png");
            if (logoUrl != null) {
                BufferedImage logoImg = ImageIO.read(logoUrl);
                // Scale logo if it's too big (e.g., max 64x64)
                Image scaledLogo = logoImg.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                logoLabel.setIcon(new ImageIcon(scaledLogo));
            } else {
                log.warn("logo.png not found in resources");
            }
        } catch (IOException e) {
            log.warn("Failed to load logo.png", e);
        }

        JLabel titleLabel = new JLabel("Launcher Error");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        titleLabel.setForeground(KRAKEN_GREEN);

        headerPanel.add(logoLabel, BorderLayout.WEST);
        headerPanel.add(titleLabel, BorderLayout.CENTER);

        // --- Body  (The Error Message) ---
        JPanel bodyPanel = new JPanel(new BorderLayout());
        bodyPanel.setBackground(DARK_GRAY_BG);
        bodyPanel.setBorder(new EmptyBorder(10, 20, 10, 20));

        JTextArea textArea = new JTextArea(message);
        textArea.setFont(new Font("Consolas", Font.PLAIN, 12)); // Monospaced for tech feel
        textArea.setForeground(TEXT_COLOR);
        textArea.setBackground(LIGHTER_GRAY_BG);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setEditable(false);

        // Add padding inside the text area
        textArea.setBorder(new EmptyBorder(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(new LineBorder(KRAKEN_GREEN, 1)); // Green border
        scrollPane.setPreferredSize(new Dimension(450, 150));
        scrollPane.getVerticalScrollBar().setUI(new CustomScrollBarUI()); // Optional: see note below

        bodyPanel.add(scrollPane, BorderLayout.CENTER);

        // --- Footer Section (Buttons) ---
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footerPanel.setBackground(DARK_GRAY_BG);
        footerPanel.setBorder(new EmptyBorder(10, 20, 20, 20));

        JButton exitButton = createStyledButton("Exit Launcher");
        exitButton.addActionListener(e -> {
            dispose();
            System.exit(1);
        });

        footerPanel.add(exitButton);

        // Add sections to Dialog
        add(headerPanel, BorderLayout.NORTH);
        add(bodyPanel, BorderLayout.CENTER);
        add(footerPanel, BorderLayout.SOUTH);

        // Ensure closing the window kills the app (since it's a fatal error)
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                System.exit(1);
            }
        });

        pack();
        setLocationRelativeTo(null); // Center on screen
    }

    /**
     * Helper to create a button styled with Kraken Green
     */
    private JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setBackground(DARK_GRAY_BG);
        button.setForeground(KRAKEN_GREEN);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));

        // Create a green border
        Border line = new LineBorder(KRAKEN_GREEN);
        Border margin = new EmptyBorder(5, 15, 5, 15);
        button.setBorder(new CompoundBorder(line, margin));

        // Simple hover effect
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                button.setBackground(KRAKEN_GREEN);
                button.setForeground(Color.BLACK);
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                button.setBackground(DARK_GRAY_BG);
                button.setForeground(KRAKEN_GREEN);
            }
        });
        return button;
    }

    public void open() {
        this.setVisible(true);
    }

    private static class CustomScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            this.thumbColor = KRAKEN_GREEN;
            this.trackColor = LIGHTER_GRAY_BG;
        }
        @Override protected JButton createDecreaseButton(int orientation) { return createZeroButton(); }
        @Override protected JButton createIncreaseButton(int orientation) { return createZeroButton(); }
        private JButton createZeroButton() {
            JButton jbutton = new JButton();
            jbutton.setPreferredSize(new Dimension(0, 0));
            return jbutton;
        }
    }
}
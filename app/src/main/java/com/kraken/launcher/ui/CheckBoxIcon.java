package com.kraken.launcher.ui;

import javax.swing.*;
import java.awt.*;

import static com.kraken.launcher.ui.LauncherUI.DARK_BG;
import static com.kraken.launcher.ui.LauncherUI.PRIMARY_GREEN;

public class CheckBoxIcon implements Icon {
    private final boolean selected;

    public CheckBoxIcon(boolean selected) {
        this.selected = selected;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw box
        g2.setColor(DARK_BG);
        g2.fillRoundRect(x, y, 18, 18, 4, 4);
        g2.setColor(PRIMARY_GREEN);
        g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(x, y, 18, 18, 4, 4);

        // Draw checkmark if selected
        if (selected) {
            g2.setColor(PRIMARY_GREEN);
            g2.setStroke(new BasicStroke(2.5f));
            g2.drawLine(x + 4, y + 9, x + 7, y + 13);
            g2.drawLine(x + 7, y + 13, x + 14, y + 5);
        }
    }

    @Override
    public int getIconWidth() {
        return 18;
    }

    @Override
    public int getIconHeight() {
        return 18;
    }
}

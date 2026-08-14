package com.kraken.launcher.ui;

import java.awt.Color;

/**
 * Shared Kraken UI colours so the launcher, installer and error dialog stay visually consistent and the values are
 * declared in one place rather than duplicated across each Swing class.
 */
public final class Theme {

    private Theme() {
    }

    public static final Color DARK_BG = new Color(30, 30, 30);
    public static final Color CARD_BG = new Color(45, 45, 45);
    public static final Color TEXT = new Color(220, 220, 220);
    public static final Color PRIMARY_GREEN = new Color(0, 200, 83);
    public static final Color ACCENT_GREEN = new Color(0, 255, 140);
}

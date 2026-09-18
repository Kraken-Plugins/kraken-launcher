package com.kraken.launcher;

/**
 * Stands in for RuneLite's net.runelite.launcher.Launcher in LauncherMainAdviceTest.
 */
public class FakeRuneLiteLauncher {

    public static String[] received;

    public static void main(String[] args) {
        received = args;
    }
}

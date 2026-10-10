package com.kraken.launcher;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LaunchArgsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readsAndRemovesKrakensFlags() {
        LaunchArgs args = LaunchArgs.parse(new String[]{
                "--qa", "--debug", "--force-ui", "--kraken-profile", "MyCharacter", "--hw-accel", "OFF"});

        assertTrue(args.isQa());
        assertTrue(args.isForceUi());
        assertEquals("MyCharacter", args.getProfile());
        assertFalse(args.isPassThrough());
        assertArrayEquals(new String[]{"--debug", "--hw-accel", "OFF"}, args.getRuneLiteArgs());
    }

    @Test
    public void readsTheProfileWrittenWithAnEqualsSign() {
        LaunchArgs args = LaunchArgs.parse(new String[]{"--kraken-profile=MyCharacter"});

        assertEquals("MyCharacter", args.getProfile());
        assertArrayEquals(new String[0], args.getRuneLiteArgs());
    }

    @Test
    public void aProfileFlagWithoutANameIsDropped() {
        LaunchArgs args = LaunchArgs.parse(new String[]{"--debug", "--kraken-profile"});

        assertNull(args.getProfile());
        assertArrayEquals(new String[]{"--debug"}, args.getRuneLiteArgs());
        assertNull(LaunchArgs.parse(new String[]{"--kraken-profile="}).getProfile());
        assertNull(LaunchArgs.parse(new String[]{}).getProfile());
    }

    @Test
    public void runeLitesOwnFlowsPassThrough() {
        assertTrue(LaunchArgs.parse(new String[]{"--postinstall"}).isPassThrough());
        assertTrue(LaunchArgs.parse(new String[]{"--configure"}).isPassThrough());
        assertFalse(LaunchArgs.parse(new String[]{"--debug"}).isPassThrough());
    }

    @Test
    public void reflectFromSettingsBecomesFork() {
        assertArrayEquals(new String[]{"--debug", "--launch-mode", "FORK"},
                LaunchArgs.forceForkIfReflect(new String[]{"--debug"}, "REFLECT"));
    }

    @Test
    public void reflectOnTheCommandLineIsReplacedNotDuplicated() {
        assertArrayEquals(new String[]{"--debug", "--launch-mode", "FORK"},
                LaunchArgs.forceForkIfReflect(new String[]{"--launch-mode", "REFLECT", "--debug"}, "AUTO"));
        assertArrayEquals(new String[]{"--launch-mode", "FORK"},
                LaunchArgs.forceForkIfReflect(new String[]{"--launch-mode=reflect"}, null));
    }

    @Test
    public void theCommandLineOverridesTheSavedMode() {
        String[] args = {"--launch-mode", "JVM"};

        assertSame(args, LaunchArgs.forceForkIfReflect(args, "REFLECT"));
    }

    @Test
    public void otherLaunchModesAreLeftAlone() {
        for (String mode : new String[]{null, "AUTO", "FORK", "JVM"}) {
            String[] args = {"--debug"};
            assertSame(args, LaunchArgs.forceForkIfReflect(args, mode));
        }
    }

    @Test
    public void settingsLiveInTheWritableWorkingDirectory() {
        assertEquals(Paths.get("settings.json"), LaunchArgs.runeLiteSettingsFile());
    }

    @Test
    public void readsTheSavedLaunchMode() throws Exception {
        Path settings = tmp.newFile("settings.json").toPath();
        Files.write(settings, "{\"debug\": false, \"launchMode\": \"REFLECT\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals("REFLECT", LaunchArgs.readSavedLaunchMode(settings));
    }

    @Test
    public void aMissingOrBrokenSettingsFileHasNoSavedMode() throws Exception {
        assertNull(LaunchArgs.readSavedLaunchMode(null));
        assertNull(LaunchArgs.readSavedLaunchMode(tmp.getRoot().toPath().resolve("missing.json")));

        Path broken = tmp.newFile("broken.json").toPath();
        Files.write(broken, "{ not json".getBytes(StandardCharsets.UTF_8));
        assertNull(LaunchArgs.readSavedLaunchMode(broken));

        Path noMode = tmp.newFile("nomode.json").toPath();
        Files.write(noMode, "{\"debug\": false}".getBytes(StandardCharsets.UTF_8));
        assertNull(LaunchArgs.readSavedLaunchMode(noMode));
    }
}

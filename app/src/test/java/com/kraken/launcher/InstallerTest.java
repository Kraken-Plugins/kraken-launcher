package com.kraken.launcher;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InstallerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String STOCK_CONFIG = "{"
            + "\"mainClass\": \"net.runelite.launcher.Launcher\","
            + "\"classPath\": [\"RuneLite.jar\"],"
            + "\"vmArgs\": [\"-XX:+DisableAttachMechanism\", \"-Drunelite.launcher.nojvm=true\", \"-Xmx768m\"]"
            + "}";

    private static final String OLD_KRAKEN_CONFIG = "{"
            + "\"mainClass\": \"com.kraken.launcher.Launcher\","
            + "\"classPath\": [\"RuneLite.jar\", \"KrakenSetup.jar\"],"
            + "\"vmArgs\": [\"-javaagent:KrakenSetup.jar\", \"--add-opens=java.base/java.net=ALL-UNNAMED\","
            + " \"-XX:+DisableAttachMechanism\", \"-Drunelite.launcher.nojvm=true\", \"-Xmx768m\"]"
            + "}";

    @Test
    public void keepsRuneLitesMainClassAndClassPath() {
        JsonObject config = Installer.applyKrakenConfig(parse(OLD_KRAKEN_CONFIG), "KrakenSetup.jar", false);

        assertEquals("net.runelite.launcher.Launcher", config.get("mainClass").getAsString());
        assertEquals(List.of("RuneLite.jar"), strings(config.getAsJsonArray("classPath")));
    }

    @Test
    public void loadsTheAgentFirstThenTheRequiredArgsThenRuneLitesArgs() {
        JsonObject config = Installer.applyKrakenConfig(parse(STOCK_CONFIG), "KrakenSetup.jar", false);

        List<String> expected = new ArrayList<>();
        expected.add("-javaagent:KrakenSetup.jar");
        expected.addAll(Installer.REQUIRED_VM_ARGS);
        expected.add("-XX:+DisableAttachMechanism");
        expected.add("-Drunelite.launcher.nojvm=true");
        expected.add("-Xmx768m");
        assertEquals(expected, strings(config.getAsJsonArray("vmArgs")));
    }

    @Test
    public void anOlderInstallEndsUpLikeAFreshOne() {
        JsonObject fromOld = Installer.applyKrakenConfig(parse(OLD_KRAKEN_CONFIG), "KrakenSetup.jar", false);
        JsonObject fromStock = Installer.applyKrakenConfig(parse(STOCK_CONFIG), "KrakenSetup.jar", false);

        assertEquals(fromStock, fromOld);
    }

    @Test
    public void applyingTwiceChangesNothing() {
        JsonObject once = Installer.applyKrakenConfig(parse(STOCK_CONFIG), "KrakenSetup.jar", false);
        JsonObject twice = Installer.applyKrakenConfig(
                Installer.applyKrakenConfig(parse(STOCK_CONFIG), "KrakenSetup.jar", false), "KrakenSetup.jar", false);

        assertEquals(once, twice);
    }

    @Test
    public void addsTheMacArgsOnlyOnMac() {
        List<String> windows = strings(Installer.applyKrakenConfig(parse(STOCK_CONFIG), "KrakenSetup.jar", false)
                .getAsJsonArray("vmArgs"));
        List<String> mac = strings(Installer.applyKrakenConfig(parse(STOCK_CONFIG), "KrakenSetup.jar", true)
                .getAsJsonArray("vmArgs"));

        for (String arg : Installer.MAC_REQUIRED_VM_ARGS) {
            assertFalse(windows.contains(arg));
            assertTrue(mac.contains(arg));
        }
    }

    @Test
    public void handlesAConfigWithoutVmArgs() {
        JsonObject config = Installer.applyKrakenConfig(
                parse("{\"mainClass\": \"net.runelite.launcher.Launcher\", \"classPath\": [\"RuneLite.jar\"]}"),
                "KrakenSetup.jar", false);

        assertEquals("-javaagent:KrakenSetup.jar", config.getAsJsonArray("vmArgs").get(0).getAsString());
    }

    @Test
    public void migratesAnOldLayoutConfigAndLocksIt() throws Exception {
        File configFile = tmp.newFile("config.json");
        Files.write(configFile.toPath(), OLD_KRAKEN_CONFIG.getBytes(StandardCharsets.UTF_8));

        assertTrue(Installer.migrateConfigJson(configFile, "KrakenSetup.jar", false));

        JsonObject migrated = parse(new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8));
        assertEquals("net.runelite.launcher.Launcher", migrated.get("mainClass").getAsString());
        assertEquals(List.of("RuneLite.jar"), strings(migrated.getAsJsonArray("classPath")));
        assertEquals(
                strings(Installer.applyKrakenConfig(parse(STOCK_CONFIG), "KrakenSetup.jar", false).getAsJsonArray("vmArgs")),
                strings(migrated.getAsJsonArray("vmArgs")));
        assertFalse(configFile.canWrite());
    }

    @Test
    public void leavesACurrentLayoutConfigAlone() throws Exception {
        File configFile = tmp.newFile("config.json");
        String currentLayout = new GsonBuilder().setPrettyPrinting().create()
                .toJson(Installer.applyKrakenConfig(parse(STOCK_CONFIG), "KrakenSetup.jar", false));
        Files.write(configFile.toPath(), currentLayout.getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(configFile.toPath());

        assertFalse(Installer.migrateConfigJson(configFile, "KrakenSetup.jar", false));

        assertArrayEquals(before, Files.readAllBytes(configFile.toPath()));
    }

    @Test
    public void aMissingConfigIsNotMigrated() throws Exception {
        File missing = new File(tmp.getRoot(), "missing-config.json");

        assertFalse(Installer.migrateConfigJson(missing, "KrakenSetup.jar", false));
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }
}

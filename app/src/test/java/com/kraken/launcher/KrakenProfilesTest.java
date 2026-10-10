package com.kraken.launcher;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KrakenProfilesTest {

    /**
     * A profiles.txt produced by the Profiles plugin's ProfileStore holding one Jagex profile (MyCharacter, session
     * sess-123, character 123456789) and one legacy username/password profile.
     */
    private static final String PLUGIN_PROFILES_FILE = "AAECAwQFBgcICQoLDA0ODz7CpuY9nxTUsQWrnxA4OVCitYQsL/T0xWKIpdxHk2Ew3hsFlqq1tpPRHULPS59Xl6gdHXSDXHhAp1UEJgge/5f36Eg493fI0oz1UQUJhgKVTTjjNp4egiURbB+tS5SU7LylUeV+3wEzFbbSP3fWpTXQeALIOUJaoutMTws+O7NGZkgB/G+U89wicCxDDzfgn+d3qfN0tqgB2+EDbpgPjo582V6MjztBGZuudHLk3Nz2HyTLc1nbTUE8T5fDDbpPlkffZ89XYEQmZHGnrRSNJx+R9I9k3+anEJv0GyEldM+0BaDgcTKBlEJa5WaNkwq7GxKDHXSphNsEZXllfOJGrnW6pmTZB5x3UmowPJmwMpXRMUjtjg3jm5t4/Mkjb8GSTzgZc7sAR/yQ+yz41Jq8eZ4MgtTHHIBl0nFf8WXO/UBR7gYwVhWr/AEeMkRIBnzBdw==";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readsOnlyJagexProfilesFromThePluginFile() throws Exception {
        Path file = tmp.newFile("profiles.txt").toPath();
        Files.write(file, PLUGIN_PROFILES_FILE.getBytes(StandardCharsets.UTF_8));

        List<KrakenProfiles.Profile> profiles = KrakenProfiles.load(file);

        assertEquals(1, profiles.size());
        assertEquals("MyCharacter", profiles.get(0).identifier);
        assertEquals("MyCharacter", profiles.get(0).characterName);
        assertEquals("sess-123", profiles.get(0).sessionId);
        assertEquals("123456789", profiles.get(0).characterId);
    }

    @Test
    public void missingOrUnreadableFileYieldsNoProfiles() throws Exception {
        assertTrue(KrakenProfiles.load(tmp.getRoot().toPath().resolve("missing.txt")).isEmpty());

        Path corrupt = tmp.newFile("corrupt.txt").toPath();
        Files.write(corrupt, "not a profiles file".getBytes(StandardCharsets.UTF_8));
        assertTrue(KrakenProfiles.load(corrupt).isEmpty());
    }

    @Test
    public void replacesEnvironmentVariablesSeenByTheClient() throws Exception {
        Map<String, String> vars = new HashMap<>();
        vars.put("JX_DISPLAY_NAME", "BronzeWraith");

        KrakenProfiles.putEnv(vars);

        assertEquals("BronzeWraith", System.getenv("JX_DISPLAY_NAME"));
        assertEquals("BronzeWraith", System.getenv().get("JX_DISPLAY_NAME"));
    }
}

package com.kraken.launcher;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KrakenProfilesTest {

    /**
     * A profiles.txt produced by the Profiles plugin's ProfileStore holding one Jagex profile (RuneWraith, session
     * sess-123, character 307826219) and one legacy username/password profile.
     */
    private static final String PLUGIN_PROFILES_FILE = "AAECAwQFBgcICQoLDA0ODz7CpuY9nxTUsQWrnxA4OVADFsy3MCDZpOMZl68CDrIC5dashlC1Y2zBMZVmcxxkOd2UUHowUA8EYijsQ8jPLaJrTD7TU1b/A5TcTfxZVOchRK52//x7KRHc6DvTtrZyaoAM2FgoNBT0KGNArib0hVKu6IvWlxhPKPjBxnwpUR6r6Zp20vLzzB+7QJV9deUCFfOQtuO5gBy8hGuTGYlqF7Brvp/KTTvJGLm+dbiEJAWlLl7TxDQsL6URMfUjZkqPXEx+LrpHMLHE/oyvmllfykelgp1QCKHmLqG1pzjsqfU292JKlwXh98jholtIe9PWoMyub9sQcqhhSXBiOZBiZIZ7I16OwDgRhDIMcXp8p1jT0qtYNsbxDyqVrecQ3F+frYTGiDexfy17RU+ZvgZTpKmKdCkArIMqmUynaAXD7a/iN9i79TTYo9ZsmjnMdjyPjg==";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readsOnlyJagexProfilesFromThePluginFile() throws Exception {
        Path file = tmp.newFile("profiles.txt").toPath();
        Files.write(file, PLUGIN_PROFILES_FILE.getBytes(StandardCharsets.UTF_8));

        List<KrakenProfiles.Profile> profiles = KrakenProfiles.load(file);

        assertEquals(1, profiles.size());
        assertEquals("RuneWraith", profiles.get(0).identifier);
        assertEquals("RuneWraith", profiles.get(0).characterName);
        assertEquals("sess-123", profiles.get(0).sessionId);
        assertEquals("307826219", profiles.get(0).characterId);
    }

    @Test
    public void missingOrUnreadableFileYieldsNoProfiles() throws Exception {
        assertTrue(KrakenProfiles.load(tmp.getRoot().toPath().resolve("missing.txt")).isEmpty());

        Path corrupt = tmp.newFile("corrupt.txt").toPath();
        Files.write(corrupt, "not a profiles file".getBytes(StandardCharsets.UTF_8));
        assertTrue(KrakenProfiles.load(corrupt).isEmpty());
    }

    @Test
    public void readsTheProfileNameFromTheCommandLine() {
        assertEquals("RuneWraith", KrakenProfiles.fromArgs(new String[]{"--qa", "--kraken-profile", "RuneWraith"}));
        assertNull(KrakenProfiles.fromArgs(new String[]{"--kraken-profile"}));
        assertNull(KrakenProfiles.fromArgs(new String[]{}));
    }
}

package com.kraken.launcher;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Reads the Jagex accounts linked through the Kraken Profiles plugin and lets the launcher start RuneLite logged in
 * as one of them. The plugin stores profiles as an AES encrypted JSON array; the key and layout here must stay in
 * sync with ProfileStore in the Profiles plugin.
 * <p>
 * Activating a profile writes a RuneLite credentials file for it and points the client at that file through the
 * runelite.credentials.path system property, which the injected client resolves relative to the RuneLite directory.
 * Environment variables set by the Jagex launcher still take precedence inside the client, so the selection only
 * has an effect when RuneLite is started directly. Legacy username/password profiles cannot be expressed as
 * credentials and are ignored.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KrakenProfiles {

    public static final String PROFILE_ARG = "--kraken-profile";
    static final Path RUNELITE_DIR = Paths.get(System.getProperty("user.home"), ".runelite");
    public static final Path PROFILES_FILE = RUNELITE_DIR.resolve("kraken").resolve("plugins").resolve("Profile").resolve("profiles.txt");
    private static final String CREDENTIALS_PATH = "kraken/plugins/Profile/credentials.properties";
    private static final String CREDENTIALS_PATH_PROPERTY = "runelite.credentials.path";
    private static final String BASE64_KEY = "TVQydDUzcXBNTjZDZ1BHUXFtR2lwRDFvcXUwWWJMMWU=";
    private static final int IV_LENGTH = 16;
    private static final Gson GSON = new Gson();

    /**
     * Returns the value following --kraken-profile, or null when the flag is absent or has no value.
     */
    public static String fromArgs(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (PROFILE_ARG.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    /**
     * Names of the linked Jagex profiles. Empty when nothing is linked or the file cannot be read.
     */
    public static List<String> names() {
        return load(PROFILES_FILE).stream().map(p -> p.identifier).collect(Collectors.toList());
    }

    /**
     * Makes RuneLite log in as the named profile. A blank or unknown name leaves the client on its default
     * credentials so the launcher keeps working when nothing is linked.
     */
    public static void activate(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            return;
        }

        String wanted = identifier.trim();
        Profile profile = load(PROFILES_FILE).stream()
                .filter(p -> wanted.equalsIgnoreCase(p.identifier))
                .findFirst()
                .orElse(null);

        if (profile == null) {
            log.warn("No linked Jagex profile named '{}' in {}. Starting with the default credentials.", wanted, PROFILES_FILE);
            return;
        }

        Properties props = new Properties();
        props.setProperty("JX_SESSION_ID", orEmpty(profile.sessionId));
        props.setProperty("JX_CHARACTER_ID", orEmpty(profile.characterId));
        props.setProperty("JX_DISPLAY_NAME", orEmpty(profile.characterName));
        props.setProperty("JX_ACCESS_TOKEN", "");
        props.setProperty("JX_REFRESH_TOKEN", "");

        Path file = RUNELITE_DIR.resolve(CREDENTIALS_PATH);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Do not share this file with anyone");
            }
        } catch (IOException e) {
            log.error("Failed to write credentials for profile '{}' to {}. Starting with the default credentials.", wanted, file, e);
            return;
        }

        System.setProperty(CREDENTIALS_PATH_PROPERTY, CREDENTIALS_PATH);
        log.info("Starting RuneLite as Kraken profile '{}' ({})", profile.identifier, profile.characterName);
    }

    /**
     * Decrypts and parses the plugin's profiles file, keeping only Jagex profiles with a name.
     */
    static List<Profile> load(Path file) {
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        try {
            String encrypted = Files.readString(file).trim();
            if (encrypted.isEmpty()) {
                return Collections.emptyList();
            }
            List<Profile> profiles = GSON.fromJson(decrypt(encrypted), new TypeToken<List<Profile>>() {}.getType());
            if (profiles == null) {
                return Collections.emptyList();
            }
            return profiles.stream()
                    .filter(p -> p != null && p.isJagexAccount && p.identifier != null && !p.identifier.isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to read Kraken profiles from {}", file, e);
            return Collections.emptyList();
        }
    }

    private static String decrypt(String base64IvAndCiphertext) throws Exception {
        byte[] combined = Base64.getDecoder().decode(base64IvAndCiphertext);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(BASE64_KEY), "AES"),
                new IvParameterSpec(Arrays.copyOf(combined, IV_LENGTH)));
        return new String(cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH), StandardCharsets.UTF_8);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * The fields the launcher needs from the Profiles plugin's Profile model. Gson ignores the rest.
     */
    static final class Profile {
        String identifier;
        boolean isJagexAccount;
        String characterName;
        String sessionId;
        String characterId;
    }
}

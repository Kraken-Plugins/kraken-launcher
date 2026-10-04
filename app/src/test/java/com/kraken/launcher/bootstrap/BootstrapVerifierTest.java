package com.kraken.launcher.bootstrap;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootstrapVerifierTest {

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /**
     * The raw 32-byte public key, which is the trailing bytes of the X.509 encoding.
     */
    private static String pub(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        return Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    private static String sign(byte[] message, KeyPair keyPair) throws Exception {
        Signature engine = Signature.getInstance("Ed25519");
        engine.initSign(keyPair.getPrivate());
        engine.update(message);
        return Base64.getEncoder().encodeToString(engine.sign()) + "\n";
    }

    private static String hexToBase64(String hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }

    @Test
    public void validSignaturePasses() throws Exception {
        KeyPair keyPair = keyPair();
        byte[] msg = "{\"artifacts\":[],\"hash\":\"deadbeef\"}\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(BootstrapVerifier.verify(msg, sign(msg, keyPair), pub(keyPair)));
    }

    /**
     * RFC 8032 section 7.1, TEST 2. BootstrapSigner publishes keys and signatures in this raw form.
     */
    @Test
    public void rfc8032TestVectorPasses() {
        String publicKey = hexToBase64("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
        String signature = hexToBase64("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
                + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00");
        assertTrue(BootstrapVerifier.verify(new byte[]{0x72}, signature, publicKey));
    }

    @Test
    public void tamperedBootstrapFails() throws Exception {
        KeyPair keyPair = keyPair();
        byte[] msg = "{\"artifacts\":[],\"hash\":\"deadbeef\"}\n".getBytes(StandardCharsets.UTF_8);
        String sig = sign(msg, keyPair);
        byte[] evil = "{\"artifacts\":[],\"hash\":\"EVILHASH\"}\n".getBytes(StandardCharsets.UTF_8);
        assertFalse(BootstrapVerifier.verify(evil, sig, pub(keyPair)));
    }

    @Test
    public void signatureFromAnotherKeyFails() throws Exception {
        byte[] msg = "{\"artifacts\":[]}\n".getBytes(StandardCharsets.UTF_8);
        String sig = sign(msg, keyPair());
        assertFalse(BootstrapVerifier.verify(msg, sig, pub(keyPair())));
    }

    @Test
    public void wrongLengthPublicKeyFails() throws Exception {
        KeyPair keyPair = keyPair();
        byte[] msg = "{}".getBytes(StandardCharsets.UTF_8);
        String x509Encoded = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        assertFalse(BootstrapVerifier.verify(msg, sign(msg, keyPair), x509Encoded));
    }

    @Test
    public void missingOrMalformedSignatureFails() throws Exception {
        byte[] msg = "{}".getBytes(StandardCharsets.UTF_8);
        String anyPub = pub(keyPair());
        assertFalse(BootstrapVerifier.verify(msg, null, anyPub));
        assertFalse(BootstrapVerifier.verify(msg, "", anyPub));
        assertFalse(BootstrapVerifier.verify(msg, "!!!not-base64!!!", anyPub));
        assertFalse(BootstrapVerifier.verify(null, "abc", anyPub));
    }
}

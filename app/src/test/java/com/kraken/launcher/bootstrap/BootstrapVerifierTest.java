package com.kraken.launcher.bootstrap;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootstrapVerifierTest {

    private static final EdDSAParameterSpec ED = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    private static byte[] seed() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    private static String pub(byte[] seed) {
        return Base64.getEncoder().encodeToString(new EdDSAPrivateKeySpec(seed, ED).getA().toByteArray());
    }

    private static String sign(byte[] message, byte[] seed) throws Exception {
        EdDSAPrivateKey key = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, ED));
        Signature engine = new EdDSAEngine(MessageDigest.getInstance(ED.getHashAlgorithm()));
        engine.initSign(key);
        engine.update(message);
        return Base64.getEncoder().encodeToString(engine.sign()) + "\n";
    }

    @Test
    public void validSignaturePasses() throws Exception {
        byte[] seed = seed();
        byte[] msg = "{\"artifacts\":[],\"hash\":\"deadbeef\"}\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(BootstrapVerifier.verify(msg, sign(msg, seed), pub(seed)));
    }

    @Test
    public void tamperedBootstrapFails() throws Exception {
        byte[] seed = seed();
        byte[] msg = "{\"artifacts\":[],\"hash\":\"deadbeef\"}\n".getBytes(StandardCharsets.UTF_8);
        String sig = sign(msg, seed);
        byte[] evil = "{\"artifacts\":[],\"hash\":\"EVILHASH\"}\n".getBytes(StandardCharsets.UTF_8);
        assertFalse(BootstrapVerifier.verify(evil, sig, pub(seed)));
    }

    @Test
    public void signatureFromAnotherKeyFails() throws Exception {
        byte[] msg = "{\"artifacts\":[]}\n".getBytes(StandardCharsets.UTF_8);
        String sig = sign(msg, seed());
        assertFalse(BootstrapVerifier.verify(msg, sig, pub(seed())));
    }

    @Test
    public void missingOrMalformedSignatureFails() {
        byte[] msg = "{}".getBytes(StandardCharsets.UTF_8);
        String anyPub = pub(seed());
        assertFalse(BootstrapVerifier.verify(msg, null, anyPub));
        assertFalse(BootstrapVerifier.verify(msg, "", anyPub));
        assertFalse(BootstrapVerifier.verify(msg, "!!!not-base64!!!", anyPub));
        assertFalse(BootstrapVerifier.verify(null, "abc", anyPub));
    }
}

package com.kraken.launcher.bootstrap;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;

/**
 * Verifies the detached Ed25519 signature over {@code bootstrap.json} before the launcher trusts any artifact
 * it names. The launcher installs the Kraken jar as a {@code -javaagent}, so an unverified bootstrap is a supply
 * chain hole: anyone able to write to the MinIO bucket, or to tamper with the download, could point the launcher
 * at a malicious jar and gain full instrumentation access. Pinning the public key here makes CI's private key the
 * only trusted source.
 *
 * <p>The signature is produced by the plugins CI ({@code BootstrapSigner}) over the exact bytes of bootstrap.json.
 * Verification fails closed: a missing, malformed, or non-matching signature is treated as untrusted.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BootstrapVerifier {

    /**
     * Base64 of the 32-byte Ed25519 public key whose private half signs the bootstrap in CI.
     *
     * <p>Generate the keypair once with the plugins generator ({@code BootstrapSigner --generate-key}), store the
     * private seed as the {@code BOOTSTRAP_SIGNING_KEY} CI secret, and paste the printed public key here.
     */
    private static final String PUBLIC_KEY_BASE64 = "NSjLI5vUdJKc0oD6I3u72ZAu5walES9VXRYxF38l5Q0=";

    private static final EdDSAParameterSpec ED25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    /**
     * Verifies a detached signature against the bootstrap bytes using the pinned public key.
     *
     * @param bootstrapBytes   the exact bytes of the downloaded bootstrap.json.
     * @param base64Signature  the base64 signature downloaded from {@code bootstrap.json.sig}.
     * @return true when the signature is valid for the pinned public key.
     */
    public static boolean verify(byte[] bootstrapBytes, String base64Signature) {
        return verify(bootstrapBytes, base64Signature, PUBLIC_KEY_BASE64);
    }

    /**
     * Verifies a detached signature against a caller-supplied base64 public key. The pinned-key
     * {@link #verify(byte[], String)} delegates here; tests can exercise the full crypto path with a generated
     * key without touching the production key.
     *
     * @param bootstrapBytes  the exact bytes of the downloaded bootstrap.json.
     * @param base64Signature the base64 signature downloaded from {@code bootstrap.json.sig}.
     * @param base64PublicKey base64 of the 32-byte Ed25519 public key to verify against.
     * @return true when the signature is valid for the given public key.
     */
    static boolean verify(byte[] bootstrapBytes, String base64Signature, String base64PublicKey) {
        if (bootstrapBytes == null || bootstrapBytes.length == 0 || base64Signature == null || base64Signature.isBlank()) {
            log.error("Bootstrap signature verification failed: missing bootstrap bytes or signature.");
            return false;
        }

        try {
            byte[] signature = Base64.getDecoder().decode(base64Signature.trim());
            EdDSAPublicKey publicKey = new EdDSAPublicKey(
                    new EdDSAPublicKeySpec(Base64.getDecoder().decode(base64PublicKey.trim()), ED25519));

            Signature engine = new EdDSAEngine(MessageDigest.getInstance(ED25519.getHashAlgorithm()));
            engine.initVerify(publicKey);
            engine.update(bootstrapBytes);

            boolean valid = engine.verify(signature);
            if (!valid) {
                log.error("Bootstrap signature did not match the pinned Kraken public key.");
            }
            return valid;
        } catch (Exception e) {
            log.error("Bootstrap signature verification threw an exception; treating as invalid.", e);
            return false;
        }
    }
}

package com.ledgercore.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Security primitives: the RS256 signing key pair and the Argon2id password encoder.
 *
 * <p>In production the signing key is supplied through {@code JWT_PRIVATE_KEY}
 * ({@code ledger.security.jwt.private-key}), so access tokens stay valid across restarts
 * and across instances. Without it an RSA key pair is generated at startup, which suits
 * local/dev runs and tests only: every restart then invalidates outstanding access tokens.</p>
 */
@Configuration
public class SecurityKeyConfig {

    /** Smallest RSA modulus accepted for a configured signing key. */
    static final int MIN_KEY_BITS = 2048;

    private static final Logger log = LoggerFactory.getLogger(SecurityKeyConfig.class);

    /** RSA key pair used to sign and verify access-token JWTs (RS256). */
    @Bean
    public KeyPair jwtSigningKeyPair(AuthProperties props) {
        String configured = props.getJwt().getPrivateKey();
        if (configured == null || configured.isBlank()) {
            log.warn("JWT_PRIVATE_KEY is not set: signing access tokens with a key generated for "
                    + "this run only. Outstanding access tokens stop working at the next restart. "
                    + "Set JWT_PRIVATE_KEY in production.");
            return generateEphemeral();
        }
        return fromPkcs8(configured);
    }

    /**
     * Builds the key pair from a PKCS#8 RSA private key given as base64 (DER) or PEM. The
     * public key is derived from the private key, so only one value has to be configured.
     *
     * @throws IllegalStateException if the value is not a usable RSA private key. The message
     *                               never contains the key material.
     */
    static KeyPair fromPkcs8(String encoded) {
        if (encoded.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException("JWT_PRIVATE_KEY is in PKCS#1 form (BEGIN RSA PRIVATE "
                    + "KEY); a PKCS#8 key (BEGIN PRIVATE KEY) is required.");
        }
        try {
            String base64 = encoded
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\n", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("JWT_PRIVATE_KEY must be an RSA private key with "
                        + "CRT parameters.");
            }
            if (crt.getModulus().bitLength() < MIN_KEY_BITS) {
                throw new IllegalStateException("JWT_PRIVATE_KEY must be an RSA key of at least "
                        + MIN_KEY_BITS + " bits.");
            }
            PublicKey publicKey = factory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new KeyPair(publicKey, privateKey);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            // Deliberately no cause and no input in the message: both could expose key material.
            throw new IllegalStateException("JWT_PRIVATE_KEY is not a valid PKCS#8 RSA private key "
                    + "(expected base64 DER or PEM). A key in the older PKCS#1 form has to be "
                    + "converted first: openssl pkcs8 -topk8 -nocrypt.");
        }
    }

    private static KeyPair generateEphemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation failed", e);
        }
    }

    /**
     * Argon2id password encoder (Requirement 1.4): salted, irreversible password hashing.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}

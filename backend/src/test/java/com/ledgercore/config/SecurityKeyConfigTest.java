package com.ledgercore.config;

import com.ledgercore.auth.AuthPrincipal;
import com.ledgercore.auth.JwtService;
import com.ledgercore.common.error.DomainException;
import com.ledgercore.user.Role;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signing key must come from configuration when it is provided, so that a restart does
 * not invalidate access tokens, and a bad key must stop the application rather than be
 * silently replaced.
 */
class SecurityKeyConfigTest {

    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    void configuredKeyYieldsTheSameKeyPairOnEveryStart() throws Exception {
        String configured = base64Pkcs8(rsa(2048));

        KeyPair first = new SecurityKeyConfig().jwtSigningKeyPair(propsWithKey(configured));
        KeyPair second = new SecurityKeyConfig().jwtSigningKeyPair(propsWithKey(configured));

        assertThat(second.getPrivate()).isEqualTo(first.getPrivate());
        assertThat(second.getPublic()).isEqualTo(first.getPublic());
    }

    @Test
    void tokenIssuedBeforeARestartIsAcceptedAfterIt() throws Exception {
        String configured = base64Pkcs8(rsa(2048));
        UUID userId = UUID.randomUUID();

        String token = jwtServiceFor(propsWithKey(configured)).issueAccessToken(userId, Role.TELLER);
        // A second, independent wiring stands in for the process after a restart.
        AuthPrincipal principal = jwtServiceFor(propsWithKey(configured)).verifyAccessToken(token);

        assertThat(principal).isEqualTo(new AuthPrincipal(userId, Role.TELLER));
    }

    @Test
    void derivedPublicKeyMatchesTheOriginalPair() throws Exception {
        KeyPair original = rsa(2048);

        KeyPair loaded = SecurityKeyConfig.fromPkcs8(base64Pkcs8(original));

        assertThat(loaded.getPublic()).isEqualTo(original.getPublic());
    }

    @Test
    void pemEncodedKeyIsAccepted() throws Exception {
        KeyPair original = rsa(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(original.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        assertThat(SecurityKeyConfig.fromPkcs8(pem).getPublic()).isEqualTo(original.getPublic());
        // Single-line form with escaped line breaks, as some environment stores keep it.
        assertThat(SecurityKeyConfig.fromPkcs8(pem.replace("\n", "\\n")).getPublic())
                .isEqualTo(original.getPublic());
    }

    @Test
    void withoutAConfiguredKeyEachStartGetsADifferentKey() {
        JwtService beforeRestart = jwtServiceFor(new AuthProperties());
        JwtService afterRestart = jwtServiceFor(new AuthProperties());

        String token = beforeRestart.issueAccessToken(UUID.randomUUID(), Role.CUSTOMER);

        assertThat(beforeRestart.verifyAccessToken(token).role()).isEqualTo(Role.CUSTOMER);
        assertThatThrownBy(() -> afterRestart.verifyAccessToken(token))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void tokenSignedWithADifferentConfiguredKeyIsRejected() throws Exception {
        JwtService ours = jwtServiceFor(propsWithKey(base64Pkcs8(rsa(2048))));
        JwtService theirs = jwtServiceFor(propsWithKey(base64Pkcs8(rsa(2048))));

        String forged = theirs.issueAccessToken(UUID.randomUUID(), Role.ADMIN);

        assertThatThrownBy(() -> ours.verifyAccessToken(forged)).isInstanceOf(DomainException.class);
    }

    @Test
    void malformedKeyStopsStartupWithoutEchoingTheValue() {
        String garbage = "not-a-real-key-" + UUID.randomUUID();

        assertThatThrownBy(() -> new SecurityKeyConfig().jwtSigningKeyPair(propsWithKey(garbage)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(garbage));
    }

    @Test
    void keySmallerThanTheMinimumIsRejected() throws Exception {
        String weak = base64Pkcs8(rsa(1024));

        assertThatThrownBy(() -> SecurityKeyConfig.fromPkcs8(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(SecurityKeyConfig.MIN_KEY_BITS));
    }

    @Test
    void pkcs1KeyIsRejectedWithAnExplanation() {
        assertThatThrownBy(() -> SecurityKeyConfig.fromPkcs8(
                "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void bareBase64Pkcs1KeyIsRejectedWithTheConversionHint() throws Exception {
        // What `openssl genpkey -outform DER` writes: the RSAPrivateKey structure without its
        // PKCS#8 wrapper, which for keys of this size is a fixed 26-byte header.
        byte[] pkcs8 = rsa(2048).getPrivate().getEncoded();
        String pkcs1 = Base64.getEncoder().encodeToString(Arrays.copyOfRange(pkcs8, 26, pkcs8.length));

        assertThatThrownBy(() -> SecurityKeyConfig.fromPkcs8(pkcs1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pkcs8 -topk8")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(pkcs1));
    }

    private static JwtService jwtServiceFor(AuthProperties props) {
        return new JwtService(new SecurityKeyConfig().jwtSigningKeyPair(props), props, CLOCK);
    }

    private static AuthProperties propsWithKey(String privateKey) {
        AuthProperties props = new AuthProperties();
        props.getJwt().setPrivateKey(privateKey);
        return props;
    }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String base64Pkcs8(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }
}

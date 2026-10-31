package com.juniverse.webhook.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.juniverse.webhook.security.WebhookSignature.Verification;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pruebas de la firma HMAC de los webhooks. */
class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_secret_0123456789";
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"order.paid\"}".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_800_000_000L;

    private static String header(String secret, long timestamp, byte[] body) {
        return WebhookSignature.header(List.of(secret), timestamp, body);
    }

    private static String independentHmac(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signsTimestampDotBodyWithHmacSha256() throws Exception {
        String expected = independentHmac("clave", "1700000000.{\"a\":1}");
        assertEquals(expected, WebhookSignature.sign("clave", 1_700_000_000L, "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(64, expected.length(), "SHA-256 en hexadecimal son 64 caracteres");
    }

    @Test
    void knownVectorFromRfc4231() throws Exception {
        // RFC 4231, caso 2: valida la implementación de HMAC-SHA256 contra un vector oficial
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", independentHmac("Jefe", "what do ya want for nothing?"));
    }

    @Test
    void differentSecretsTimestampsOrBodiesGiveDifferentSignatures() {
        String base = WebhookSignature.sign(SECRET, NOW, BODY);
        assertNotEquals(base, WebhookSignature.sign("otro_secreto_distinto_123456", NOW, BODY));
        assertNotEquals(base, WebhookSignature.sign(SECRET, NOW + 1, BODY));
        assertNotEquals(base, WebhookSignature.sign(SECRET, NOW, "{\"id\":\"evt_1\",\"type\":\"order.PAID\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsAValidRecentMessage() {
        assertEquals(Verification.VALID, WebhookSignature.verify(List.of(SECRET), String.valueOf(NOW), header(SECRET, NOW, BODY), BODY, NOW + 10, 300));
    }

    @Test
    void rejectsATamperedBody() {
        byte[] tampered = "{\"id\":\"evt_1\",\"type\":\"order.paid\",\"extra\":true}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Verification.BAD_SIGNATURE, WebhookSignature.verify(List.of(SECRET), String.valueOf(NOW), header(SECRET, NOW, BODY), tampered, NOW, 300));
    }

    @Test
    void rejectsAWrongSecret() {
        assertEquals(Verification.BAD_SIGNATURE, WebhookSignature.verify(List.of("el_secreto_del_receptor_es_otro"), String.valueOf(NOW),
                header(SECRET, NOW, BODY), BODY, NOW, 300));
    }

    @Test
    void rejectsReplayedOldMessagesEvenWithAValidSignature() {
        assertEquals(Verification.BAD_TIMESTAMP, WebhookSignature.verify(List.of(SECRET), String.valueOf(NOW), header(SECRET, NOW, BODY), BODY, NOW + 301, 300));
        assertEquals(Verification.VALID, WebhookSignature.verify(List.of(SECRET), String.valueOf(NOW), header(SECRET, NOW, BODY), BODY, NOW + 300, 300));
    }

    @Test
    void rejectsMessagesFromTheFuture() {
        assertEquals(Verification.BAD_TIMESTAMP, WebhookSignature.verify(List.of(SECRET), String.valueOf(NOW + 1000), header(SECRET, NOW + 1000, BODY), BODY, NOW, 300));
    }

    @Test
    void changingTheTimestampBreaksTheSignature() {
        // un atacante que quiera "rejuvenecer" un mensaje viejo cambia el timestamp, pero entonces la firma ya no coincide
        assertEquals(Verification.BAD_SIGNATURE, WebhookSignature.verify(List.of(SECRET), String.valueOf(NOW + 5), header(SECRET, NOW, BODY), BODY, NOW + 5, 300));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "12.5", "ayer"})
    void invalidTimestampsAreRejected(String timestamp) {
        assertEquals(Verification.BAD_TIMESTAMP, WebhookSignature.verify(List.of(SECRET), timestamp, header(SECRET, NOW, BODY), BODY, NOW, 300));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v1=", "v1=zzzz", "sha256=abcd", "garbage", "v2=abcdef"})
    void malformedSignatureHeadersAreRejected(String signature) {
        assertEquals(Verification.BAD_SIGNATURE, WebhookSignature.verify(List.of(SECRET), String.valueOf(NOW), signature, BODY, NOW, 300));
    }

    @Test
    void duringRotationEitherSignatureOrEitherSecretWorks() {
        String oldSecret = "whsec_secreto_viejo_0123456789";
        String newSecret = "whsec_secreto_nuevo_0123456789";
        String both = WebhookSignature.header(List.of(newSecret, oldSecret), NOW, BODY);
        assertTrue(both.contains(",") && both.startsWith("v1="));

        assertEquals(Verification.VALID, WebhookSignature.verify(List.of(oldSecret), String.valueOf(NOW), both, BODY, NOW, 300), "receptor que aún usa el secreto viejo");
        assertEquals(Verification.VALID, WebhookSignature.verify(List.of(newSecret), String.valueOf(NOW), both, BODY, NOW, 300), "receptor ya actualizado");
        assertEquals(Verification.VALID, WebhookSignature.verify(List.of("otro", newSecret), String.valueOf(NOW), both, BODY, NOW, 300));
        assertEquals(Verification.BAD_SIGNATURE, WebhookSignature.verify(List.of("ninguno_de_los_dos_secretos"), String.valueOf(NOW), both, BODY, NOW, 300));
    }
}

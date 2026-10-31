package com.juniverse.webhook.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.juniverse.webhook.config.WebhookProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pruebas de {@link BackoffPolicy} y {@link TargetValidator}. */
class BackoffAndTargetTest {

    @Test
    void delaysDoubleUntilTheCap() {
        BackoffPolicy policy = new BackoffPolicy(1_000, 60_000);
        assertEquals(1_000, policy.baseDelay(1));
        assertEquals(2_000, policy.baseDelay(2));
        assertEquals(4_000, policy.baseDelay(3));
        assertEquals(32_000, policy.baseDelay(6));
        assertEquals(60_000, policy.baseDelay(7), "se limita al máximo");
        assertEquals(60_000, policy.baseDelay(500), "sin desbordar con muchos intentos");
        assertEquals(1_000, policy.baseDelay(0));
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        BackoffPolicy policy = new BackoffPolicy(1_000, 60_000);
        for (int i = 0; i < 1_000; i++) {
            long delay = policy.delayWithJitter(3);
            assertTrue(delay >= 3_200 && delay <= 4_800, "fuera de rango: " + delay);
        }
    }

    private static TargetValidator validator(boolean allowPrivate) {
        return new TargetValidator(new WebhookProperties(1000, 50, 5000, 8, 1000, 60_000, 60_000, allowPrivate, 300, 24, "x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/hook", "http://127.0.0.1:8080/hook", "http://10.0.0.5/hook", "http://192.168.1.10/hook",
            "http://172.16.0.1/hook", "http://169.254.169.254/latest/meta-data/", "http://0.0.0.0/hook", "http://[::1]/hook", "http://[fd00::1]/hook",
            "http://100.64.0.1/hook", "http://224.0.0.1/hook"})
    void blocksInternalTargetsWhenPrivateIsNotAllowed(String url) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> validator(false).validate(url));
        assertTrue(e.getMessage().contains("redes privadas"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://ejemplo.com/hook", "file:///etc/passwd", "gopher://ejemplo.com", "javascript:alert(1)", "//sin-esquema.com/x", "no es una url", "http://", "http://usuario:clave@93.184.216.34/hook"})
    void blocksOtherSchemesAndMalformedUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> validator(false).validate(url));
        assertThrows(IllegalArgumentException.class, () -> validator(true).validate(url), "esto se rechaza aunque se permitan destinos privados");
    }

    @Test
    void acceptsPublicTargets() {
        assertDoesNotThrow(() -> validator(false).validate("https://93.184.216.34/webhooks"));
        assertDoesNotThrow(() -> validator(false).validate("http://8.8.8.8:8443/hook"));
    }

    @Test
    void privateTargetsAreAcceptedOnlyWhenExplicitlyAllowed() {
        assertDoesNotThrow(() -> validator(true).validate("http://localhost:8080/webhooks/orders"));
    }
}

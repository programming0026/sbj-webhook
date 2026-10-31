package com.juniverse.webhook.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades {@code webhook.*}.
 *
 * @param dispatchIntervalMs cada cuántos milisegundos el despachador busca entregas pendientes
 * @param batchSize máximo de entregas que se procesan en cada vuelta
 * @param requestTimeoutMs tiempo máximo de espera de la respuesta del receptor
 * @param maxAttempts intentos totales de una entrega antes de darla por fallida
 * @param backoffBaseMs espera antes del primer reintento (se duplica en cada intento)
 * @param backoffMaxMs espera máxima entre reintentos
 * @param staleClaimMs tiempo tras el cual una entrega "en vuelo" se considera abandonada (por una caída) y se vuelve a intentar
 * @param allowPrivateTargets si es {@code false}, no se aceptan destinos en redes privadas ni en el propio equipo (protección SSRF)
 * @param toleranceSeconds antigüedad máxima aceptada por el receptor para un mensaje (protección contra repeticiones)
 * @param rotationGraceHours horas durante las que sigue firmando también con el secreto anterior tras rotarlo
 * @param receiverSecret secreto con el que el receptor de ejemplo verifica las firmas
 */
@ConfigurationProperties(prefix = "webhook")
public record WebhookProperties(long dispatchIntervalMs, int batchSize, long requestTimeoutMs, int maxAttempts, long backoffBaseMs,
        long backoffMaxMs, long staleClaimMs, boolean allowPrivateTargets, long toleranceSeconds, long rotationGraceHours, String receiverSecret) {
}

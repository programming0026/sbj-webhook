package com.juniverse.webhook.provider;

/**
 * Registro de un intento de entrega (para el historial que se muestra al suscriptor).
 *
 * @param attemptNo número de intento (desde 1)
 * @param statusCode código HTTP recibido, o {@code null} si no hubo respuesta
 * @param error descripción del fallo, o {@code null}
 * @param durationMs duración del intento
 * @param at momento del intento (milisegundos desde 1970)
 */
public record AttemptRecord(int attemptNo, Integer statusCode, String error, long durationMs, long at) {
}

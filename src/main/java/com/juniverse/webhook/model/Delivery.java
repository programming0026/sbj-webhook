package com.juniverse.webhook.model;

/**
 * Entrega de un evento a una suscripción. Cada evento genera una entrega por suscriptor interesado, y cada entrega puede
 * necesitar varios intentos.
 *
 * @param id identificador de la entrega
 * @param subscriptionId suscripción destinataria
 * @param eventId identificador del evento (el mismo para todos los suscriptores y en todos los reintentos: el receptor lo usa para descartar duplicados)
 * @param eventType tipo de evento
 * @param payload cuerpo exacto que se envía (se guarda ya serializado para firmar siempre los mismos bytes)
 * @param status PENDING, IN_FLIGHT, DELIVERED o FAILED
 * @param attempts intentos realizados
 * @param nextAttemptAt cuándo se hará el siguiente intento (milisegundos desde 1970)
 * @param lastStatusCode último código HTTP recibido, o {@code null}
 * @param lastError último error de red o del receptor, o {@code null}
 * @param createdAt creación (milisegundos desde 1970)
 * @param deliveredAt momento de la entrega correcta, o {@code null}
 */
public record Delivery(String id, String subscriptionId, String eventId, String eventType, String payload, String status, int attempts,
        long nextAttemptAt, Integer lastStatusCode, String lastError, long createdAt, Long deliveredAt) {
}

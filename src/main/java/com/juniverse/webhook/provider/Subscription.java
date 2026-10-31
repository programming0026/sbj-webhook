package com.juniverse.webhook.provider;

import java.util.List;

/**
 * Suscripción: "quiero que me avisen de estos eventos en esta URL". El secreto es lo que permite al receptor comprobar que los
 * avisos vienen realmente de este sistema.
 *
 * @param id identificador
 * @param url dirección a la que se envían los avisos
 * @param description descripción opcional
 * @param secret secreto de firma vigente
 * @param previousSecret secreto anterior (solo durante la rotación), o {@code null}
 * @param previousSecretExpiresAt cuándo deja de usarse el secreto anterior (milisegundos desde 1970)
 * @param events tipos de evento solicitados, o {@code *} para todos
 * @param active si recibe avisos
 * @param createdAt alta (milisegundos desde 1970)
 */
public record Subscription(String id, String url, String description, String secret, String previousSecret, long previousSecretExpiresAt,
        List<String> events, boolean active, long createdAt) {

    /**
     * Indica si la suscripción quiere un tipo de evento.
     *
     * @param eventType tipo de evento
     * @return {@code true} si lo solicitó (o pidió todos)
     */
    public boolean wants(String eventType) {
        return events.contains("*") || events.contains(eventType);
    }

    /**
     * Secretos con los que debe firmarse ahora: el vigente y, mientras dure la rotación, el anterior.
     *
     * @param nowMillis hora actual en milisegundos
     * @return uno o dos secretos
     */
    public List<String> signingSecrets(long nowMillis) {
        return previousSecret != null && previousSecretExpiresAt > nowMillis ? List.of(secret, previousSecret) : List.of(secret);
    }
}

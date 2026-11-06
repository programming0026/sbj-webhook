package com.juniverse.webhook.dto;

import com.juniverse.webhook.model.Subscription;
import java.util.List;

/**
 * Suscripción tal como se muestra. El secreto solo aparece al crear o rotar: después se enmascara.
 *
 * @param id identificador
 * @param url destino
 * @param description descripción
 * @param events eventos
 * @param active si recibe avisos
 * @param secret secreto completo (solo al crear o rotar) o {@code null}
 * @param secretHint últimos caracteres del secreto, para reconocerlo
 * @param rotating si hay un secreto anterior aún vigente
 */
public record SubscriptionView(String id, String url, String description, List<String> events, boolean active, String secret, String secretHint, boolean rotating) {

    public static SubscriptionView of(Subscription s, boolean revealSecret, long now) {
        return new SubscriptionView(s.id(), s.url(), s.description(), s.events(), s.active(), revealSecret ? s.secret() : null,
                "…" + s.secret().substring(s.secret().length() - 4), s.previousSecret() != null && s.previousSecretExpiresAt() > now);
    }
}

package com.juniverse.webhook.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.juniverse.webhook.model.Subscription;

/** Acceso a las suscripciones. */
@Repository
public class SubscriptionRepository {

    private final JdbcClient jdbc;

    /**
     * Crea el repositorio.
     *
     * @param jdbc cliente JDBC
     */
    public SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Subscription map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Subscription(rs.getString("id"), rs.getString("url"), rs.getString("description"), rs.getString("secret"),
                rs.getString("previous_secret"), rs.getLong("previous_secret_expires_at"), List.of(rs.getString("events").split(",")),
                rs.getBoolean("active"), rs.getLong("created_at"));
    }

    /**
     * Inserta una suscripción.
     *
     * @param s suscripción
     */
    public void insert(Subscription s) {
        jdbc.sql("""
                insert into subscriptions (id, url, description, secret, previous_secret, previous_secret_expires_at, events, active, created_at)
                values (:id, :url, :description, :secret, :previous, :expires, :events, :active, :createdAt)""")
                .param("id", s.id()).param("url", s.url()).param("description", s.description()).param("secret", s.secret())
                .param("previous", s.previousSecret()).param("expires", s.previousSecretExpiresAt())
                .param("events", String.join(",", s.events())).param("active", s.active()).param("createdAt", s.createdAt()).update();
    }

    /**
     * Busca una suscripción.
     *
     * @param id identificador
     * @return la suscripción, si existe
     */
    public Optional<Subscription> findById(String id) {
        return jdbc.sql("select * from subscriptions where id = :id").param("id", id).query(SubscriptionRepository::map).optional();
    }

    /**
     * Lista todas las suscripciones.
     *
     * @return suscripciones, de la más nueva a la más antigua
     */
    public List<Subscription> findAll() {
        return jdbc.sql("select * from subscriptions order by created_at desc").query(SubscriptionRepository::map).list();
    }

    /**
     * Suscripciones activas interesadas en un tipo de evento.
     *
     * @param eventType tipo de evento
     * @return suscripciones destinatarias
     */
    public List<Subscription> findActiveFor(String eventType) {
        return jdbc.sql("select * from subscriptions where active = true").query(SubscriptionRepository::map).list().stream()
                .filter(s -> s.wants(eventType)).toList();
    }

    /**
     * Activa o desactiva una suscripción.
     *
     * @param id identificador
     * @param active nuevo estado
     */
    public void setActive(String id, boolean active) {
        jdbc.sql("update subscriptions set active = :active where id = :id").param("active", active).param("id", id).update();
    }

    /**
     * Cambia el secreto conservando el anterior durante un tiempo.
     *
     * @param id identificador
     * @param newSecret secreto nuevo
     * @param previous secreto que deja de ser el principal
     * @param previousExpiresAt cuándo deja de firmarse con el anterior
     */
    public void rotate(String id, String newSecret, String previous, long previousExpiresAt) {
        jdbc.sql("update subscriptions set secret = :secret, previous_secret = :previous, previous_secret_expires_at = :expires where id = :id")
                .param("secret", newSecret).param("previous", previous).param("expires", previousExpiresAt).param("id", id).update();
    }

    /**
     * Elimina una suscripción y su historial.
     *
     * @param id identificador
     * @return filas eliminadas
     */
    public int delete(String id) {
        jdbc.sql("delete from attempts where delivery_id in (select id from deliveries where subscription_id = :id)").param("id", id).update();
        jdbc.sql("delete from deliveries where subscription_id = :id").param("id", id).update();
        return jdbc.sql("delete from subscriptions where id = :id").param("id", id).update();
    }
}

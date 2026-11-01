package com.juniverse.webhook.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.juniverse.webhook.model.AttemptRecord;
import com.juniverse.webhook.model.Delivery;

/** Acceso a las entregas y a su historial de intentos. */
@Repository
public class DeliveryRepository {

    private final JdbcClient jdbc;

    /**
     * Crea el repositorio.
     *
     * @param jdbc cliente JDBC
     */
    public DeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Delivery map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int code = rs.getInt("last_status_code");
        boolean noCode = rs.wasNull();
        long delivered = rs.getLong("delivered_at");
        boolean notDelivered = rs.wasNull();
        return new Delivery(rs.getString("id"), rs.getString("subscription_id"), rs.getString("event_id"), rs.getString("event_type"),
                rs.getString("payload"), rs.getString("status"), rs.getInt("attempts"), rs.getLong("next_attempt_at"),
                noCode ? null : code, rs.getString("last_error"), rs.getLong("created_at"), notDelivered ? null : delivered);
    }

    /**
     * Crea una entrega pendiente.
     *
     * @param d entrega
     */
    public void insert(Delivery d) {
        jdbc.sql("""
                insert into deliveries (id, subscription_id, event_id, event_type, payload, status, attempts, next_attempt_at, created_at)
                values (:id, :subscriptionId, :eventId, :eventType, :payload, 'PENDING', 0, :next, :createdAt)""")
                .param("id", d.id()).param("subscriptionId", d.subscriptionId()).param("eventId", d.eventId()).param("eventType", d.eventType())
                .param("payload", d.payload()).param("next", d.nextAttemptAt()).param("createdAt", d.createdAt()).update();
    }

    /**
     * Busca una entrega.
     *
     * @param id identificador
     * @return la entrega, si existe
     */
    public Optional<Delivery> findById(String id) {
        return jdbc.sql("select * from deliveries where id = :id").param("id", id).query(DeliveryRepository::map).optional();
    }

    /**
     * Lista entregas con filtros opcionales.
     *
     * @param subscriptionId suscripción, o {@code null}
     * @param status estado, o {@code null}
     * @param limit máximo de resultados
     * @return entregas, de la más nueva a la más antigua
     */
    public List<Delivery> search(String subscriptionId, String status, int limit) {
        StringBuilder sql = new StringBuilder("select * from deliveries where 1 = 1");
        if (subscriptionId != null) {
            sql.append(" and subscription_id = :subscriptionId");
        }
        if (status != null) {
            sql.append(" and status = :status");
        }
        sql.append(" order by created_at desc, id limit :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (subscriptionId != null) {
            spec = spec.param("subscriptionId", subscriptionId);
        }
        if (status != null) {
            spec = spec.param("status", status);
        }
        return spec.query(DeliveryRepository::map).list();
    }

    /**
     * Entregas pendientes cuyo momento de intento ya llegó.
     *
     * @param now hora actual en milisegundos
     * @param limit máximo de resultados
     * @return entregas listas para intentarse, las más antiguas primero
     */
    public List<Delivery> findDue(long now, int limit) {
        return jdbc.sql("select * from deliveries where status = 'PENDING' and next_attempt_at <= :now order by next_attempt_at, created_at limit :limit")
                .param("now", now).param("limit", limit).query(DeliveryRepository::map).list();
    }

    /**
     * Reclama una entrega para procesarla: solo uno de varios despachadores concurrentes lo consigue (la actualización condicional es atómica).
     *
     * @param id entrega
     * @param now hora actual en milisegundos
     * @return {@code true} si esta llamada la reclamó
     */
    public boolean claim(String id, long now) {
        return jdbc.sql("update deliveries set status = 'IN_FLIGHT', claimed_at = :now where id = :id and status = 'PENDING'")
                .param("now", now).param("id", id).update() == 1;
    }

    /**
     * Devuelve a PENDING las entregas "en vuelo" abandonadas (el proceso se cayó a mitad de un envío).
     *
     * @param olderThan reclamadas antes de este momento (milisegundos)
     * @return cuántas se recuperaron
     */
    public int releaseStale(long olderThan) {
        return jdbc.sql("update deliveries set status = 'PENDING' where status = 'IN_FLIGHT' and claimed_at < :olderThan").param("olderThan", olderThan).update();
    }

    /**
     * Marca la entrega como correcta.
     *
     * @param id entrega
     * @param attempts intentos totales
     * @param statusCode código recibido
     * @param now momento
     */
    public void markDelivered(String id, int attempts, int statusCode, long now) {
        jdbc.sql("update deliveries set status = 'DELIVERED', attempts = :attempts, last_status_code = :code, last_error = null, delivered_at = :now where id = :id")
                .param("attempts", attempts).param("code", statusCode).param("now", now).param("id", id).update();
    }

    /**
     * Programa un reintento.
     *
     * @param id entrega
     * @param attempts intentos totales
     * @param statusCode código recibido, o {@code null}
     * @param error descripción del fallo
     * @param nextAttemptAt momento del siguiente intento
     */
    public void markRetry(String id, int attempts, Integer statusCode, String error, long nextAttemptAt) {
        jdbc.sql("update deliveries set status = 'PENDING', attempts = :attempts, last_status_code = :code, last_error = :error, next_attempt_at = :next where id = :id")
                .param("attempts", attempts).param("code", statusCode).param("error", error).param("next", nextAttemptAt).param("id", id).update();
    }

    /**
     * Marca la entrega como fallida de forma definitiva.
     *
     * @param id entrega
     * @param attempts intentos totales
     * @param statusCode código recibido, o {@code null}
     * @param error descripción del fallo
     */
    public void markFailed(String id, int attempts, Integer statusCode, String error) {
        jdbc.sql("update deliveries set status = 'FAILED', attempts = :attempts, last_status_code = :code, last_error = :error where id = :id")
                .param("attempts", attempts).param("code", statusCode).param("error", error).param("id", id).update();
    }

    /**
     * Pone una entrega fallida otra vez en cola, empezando de cero.
     *
     * @param id entrega
     * @param now momento
     * @return {@code true} si estaba FAILED y se reencoló
     */
    public boolean requeue(String id, long now) {
        return jdbc.sql("update deliveries set status = 'PENDING', attempts = 0, last_error = null, next_attempt_at = :now where id = :id and status = 'FAILED'")
                .param("now", now).param("id", id).update() == 1;
    }

    /**
     * Anota un intento en el historial.
     *
     * @param deliveryId entrega
     * @param attempt intento
     */
    public void addAttempt(String deliveryId, AttemptRecord attempt) {
        jdbc.sql("insert into attempts (delivery_id, attempt_no, status_code, error, duration_ms, at) values (:id, :no, :code, :error, :duration, :at)")
                .param("id", deliveryId).param("no", attempt.attemptNo()).param("code", attempt.statusCode()).param("error", attempt.error())
                .param("duration", attempt.durationMs()).param("at", attempt.at()).update();
    }

    /**
     * Historial de intentos de una entrega.
     *
     * @param deliveryId entrega
     * @return intentos en orden
     */
    public List<AttemptRecord> attempts(String deliveryId) {
        return jdbc.sql("select attempt_no, status_code, error, duration_ms, at from attempts where delivery_id = :id order by attempt_no, id").param("id", deliveryId)
                .query((rs, n) -> {
                    int code = rs.getInt("status_code");
                    boolean noCode = rs.wasNull();
                    return new AttemptRecord(rs.getInt("attempt_no"), noCode ? null : code, rs.getString("error"), rs.getLong("duration_ms"), rs.getLong("at"));
                }).list();
    }
}

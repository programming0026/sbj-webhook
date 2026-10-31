package com.juniverse.webhook.receiver;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Eventos ya recibidos. La clave primaria es el identificador del evento: es lo que garantiza que un mensaje duplicado se procese una
 * sola vez, incluso si dos copias llegan al mismo tiempo (la base de datos rechaza la segunda).
 */
@Repository
public class ReceivedEventStore {

    /**
     * Evento recibido.
     *
     * @param eventId identificador del evento
     * @param eventType tipo de evento
     * @param payload cuerpo recibido
     * @param receivedAt momento de recepción (milisegundos desde 1970)
     */
    public record Received(String eventId, String eventType, String payload, long receivedAt) {
    }

    private final JdbcClient jdbc;

    /**
     * Crea el almacén.
     *
     * @param jdbc cliente JDBC
     */
    public ReceivedEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Registra un evento si es la primera vez que se ve.
     *
     * @param eventId identificador
     * @param eventType tipo
     * @param payload cuerpo
     * @param now momento
     * @return {@code true} si era nuevo; {@code false} si ya se había recibido
     */
    public boolean insertIfNew(String eventId, String eventType, String payload, long now) {
        try {
            jdbc.sql("insert into received_events (event_id, event_type, payload, received_at) values (:id, :type, :payload, :at)")
                    .param("id", eventId).param("type", eventType).param("payload", payload).param("at", now).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * Eventos recibidos.
     *
     * @return del más reciente al más antiguo
     */
    public List<Received> all() {
        return jdbc.sql("select event_id, event_type, payload, received_at from received_events order by received_at desc, event_id")
                .query((rs, n) -> new Received(rs.getString("event_id"), rs.getString("event_type"), rs.getString("payload"), rs.getLong("received_at"))).list();
    }

    /** Borra todo (para pruebas y demostraciones). */
    public void clear() {
        jdbc.sql("delete from received_events").update();
    }
}

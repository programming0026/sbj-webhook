package com.juniverse.webhook.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import com.juniverse.webhook.model.Delivery;
import com.juniverse.webhook.model.Subscription;
import com.juniverse.webhook.repository.DeliveryRepository;
import com.juniverse.webhook.repository.SubscriptionRepository;

/**
 * Convierte un evento del negocio en entregas pendientes, una por suscriptor interesado. Se llama DENTRO de la transacción del
 * cambio de negocio (por ejemplo, al crear el pedido): o se guardan el pedido y sus entregas, o ninguno. Es el mismo principio del
 * patrón <i>Transactional Outbox</i>: nunca se envía nada por HTTP dentro de la transacción; solo se anota lo que hay que enviar.
 */
@Component
public class EventPublisher {

    private final SubscriptionRepository subscriptions;
    private final DeliveryRepository deliveries;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * Crea el publicador.
     *
     * @param subscriptions suscripciones
     * @param deliveries entregas
     * @param jsonMapper serializador JSON
     * @param clock reloj
     */
    public EventPublisher(SubscriptionRepository subscriptions, DeliveryRepository deliveries, JsonMapper jsonMapper, Clock clock) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * Publica un evento para todas las suscripciones activas interesadas.
     *
     * @param eventType tipo de evento, por ejemplo {@code order.paid}
     * @param data datos del evento
     * @return identificador del evento
     */
    @Transactional
    public String publish(String eventType, Object data) {
        String eventId = "evt_" + UUID.randomUUID();
        String payload = envelope(eventId, eventType, data);
        for (Subscription subscription : subscriptions.findActiveFor(eventType)) {
            enqueue(subscription, eventId, eventType, payload);
        }
        return eventId;
    }

    /**
     * Publica un evento solo para una suscripción.
     *
     * @param subscription destinataria
     * @param eventType tipo de evento
     * @param data datos del evento
     * @return identificador de la entrega
     */
    @Transactional
    public String publishTo(Subscription subscription, String eventType, Object data) {
        String eventId = "evt_" + UUID.randomUUID();
        return enqueue(subscription, eventId, eventType, envelope(eventId, eventType, data));
    }

    private String enqueue(Subscription subscription, String eventId, String eventType, String payload) {
        long now = clock.millis();
        Delivery delivery = new Delivery(UUID.randomUUID().toString(), subscription.id(), eventId, eventType, payload, "PENDING", 0, now, null, null, now, null);
        deliveries.insert(delivery);
        return delivery.id();
    }

    /** El cuerpo se serializa UNA vez; los reintentos envían exactamente los mismos bytes, así la firma es siempre la misma para el mismo evento. */
    private String envelope(String eventId, String eventType, Object data) {
        return jsonMapper.writeValueAsString(Map.of("id", eventId, "type", eventType, "created", clock.instant().getEpochSecond(), "data", data == null ? List.of() : data));
    }
}

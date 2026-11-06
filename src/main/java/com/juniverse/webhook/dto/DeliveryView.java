package com.juniverse.webhook.dto;

import com.juniverse.webhook.model.AttemptRecord;
import com.juniverse.webhook.model.Delivery;
import java.util.List;

/**
 * Entrega para mostrar, con su historial de intentos.
 *
 * @param delivery datos de la entrega
 * @param attempts historial de intentos
 */
public record DeliveryView(Delivery delivery, List<AttemptRecord> attempts) {
}

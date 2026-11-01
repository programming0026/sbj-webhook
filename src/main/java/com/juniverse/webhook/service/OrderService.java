package com.juniverse.webhook.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los pedidos son el "negocio" que genera eventos. Cada cambio de estado guarda el pedido y las entregas de webhook pendientes en la
 * MISMA transacción: si el pedido se guarda, se enviará su aviso; si falla, no se enviará un aviso de algo que no ocurrió.
 */
@Service
public class OrderService {

    private final JdbcClient jdbc;
    private final EventPublisher publisher;
    private final Clock clock;

    /**
     * Crea el servicio.
     *
     * @param jdbc cliente JDBC
     * @param publisher publicador de eventos
     * @param clock reloj
     */
    public OrderService(JdbcClient jdbc, EventPublisher publisher, Clock clock) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Crea un pedido y emite {@code order.created}.
     *
     * @param customer cliente
     * @param amount importe
     * @return datos del pedido
     */
    @Transactional
    public Map<String, Object> create(String customer, BigDecimal amount) {
        String id = "ord_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("insert into orders (id, customer, amount, status, created_at) values (:id, :customer, :amount, 'CREATED', :at)")
                .param("id", id).param("customer", customer).param("amount", amount).param("at", clock.millis()).update();
        Map<String, Object> order = view(id, customer, amount, "CREATED");
        publisher.publish("order.created", order);
        return order;
    }

    /**
     * Marca un pedido como pagado y emite {@code order.paid}.
     *
     * @param id pedido
     * @return datos del pedido
     */
    @Transactional
    public Map<String, Object> pay(String id) {
        return transition(id, "CREATED", "PAID", "order.paid");
    }

    /**
     * Cancela un pedido y emite {@code order.cancelled}.
     *
     * @param id pedido
     * @return datos del pedido
     */
    @Transactional
    public Map<String, Object> cancel(String id) {
        return transition(id, "CREATED", "CANCELLED", "order.cancelled");
    }

    private Map<String, Object> transition(String id, String from, String to, String event) {
        var row = jdbc.sql("select customer, amount, status from orders where id = :id").param("id", id)
                .query((rs, n) -> new Object[] {rs.getString("customer"), rs.getBigDecimal("amount"), rs.getString("status")}).optional()
                .orElseThrow(() -> new NoSuchElementException("No existe el pedido " + id));
        if (!from.equals(row[2])) {
            throw new IllegalStateException("El pedido está en estado " + row[2] + " y no puede pasar a " + to);
        }
        jdbc.sql("update orders set status = :status where id = :id").param("status", to).param("id", id).update();
        Map<String, Object> order = view(id, (String) row[0], (java.math.BigDecimal) row[1], to);
        publisher.publish(event, order);
        return order;
    }

    private static Map<String, Object> view(String id, String customer, BigDecimal amount, String status) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", id);
        order.put("customer", customer);
        order.put("amount", amount);
        order.put("status", status);
        return order;
    }
}

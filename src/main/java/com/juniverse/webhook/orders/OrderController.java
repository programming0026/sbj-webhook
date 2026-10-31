package com.juniverse.webhook.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operaciones de negocio que disparan webhooks. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /**
     * Datos de un pedido nuevo.
     *
     * @param customer cliente
     * @param amount importe
     */
    public record CreateOrderRequest(@NotBlank(message = "El cliente es obligatorio") String customer,
            @NotNull(message = "El importe es obligatorio") @DecimalMin(value = "0.01", message = "El importe debe ser mayor que cero") BigDecimal amount) {
    }

    private final OrderService service;

    /**
     * Crea el controlador.
     *
     * @param service servicio de pedidos
     */
    public OrderController(OrderService service) {
        this.service = service;
    }

    /**
     * Crea un pedido (emite {@code order.created}).
     *
     * @param request datos validados
     * @return 201 con el pedido
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(201).body(service.create(request.customer(), request.amount()));
    }

    /**
     * Paga un pedido (emite {@code order.paid}).
     *
     * @param id pedido
     * @return el pedido
     */
    @PostMapping("/{id}/pay")
    public Map<String, Object> pay(@PathVariable String id) {
        return service.pay(id);
    }

    /**
     * Cancela un pedido (emite {@code order.cancelled}).
     *
     * @param id pedido
     * @return el pedido
     */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        return service.cancel(id);
    }
}

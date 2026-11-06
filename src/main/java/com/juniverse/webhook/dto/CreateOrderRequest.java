package com.juniverse.webhook.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Datos de un pedido nuevo.
 *
 * @param customer cliente
 * @param amount importe
 */
public record CreateOrderRequest(@NotBlank(message = "El cliente es obligatorio") String customer,
        @NotNull(message = "El importe es obligatorio") @DecimalMin(value = "0.01", message = "El importe debe ser mayor que cero") BigDecimal amount) {
}

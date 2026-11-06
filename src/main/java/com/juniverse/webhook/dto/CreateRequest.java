package com.juniverse.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Datos para suscribirse.
 *
 * @param url dirección que recibirá los avisos
 * @param description descripción opcional
 * @param events tipos de evento deseados ({@code order.created}, {@code order.paid}, {@code order.cancelled}, {@code ping} o {@code *})
 * @param secret secreto propio opcional (mínimo 24 caracteres); si se omite el servidor genera uno seguro
 */
public record CreateRequest(
        @NotBlank(message = "La URL es obligatoria") @Size(max = 500, message = "La URL no puede superar los 500 caracteres") String url,
        @Size(max = 200) String description,
        @NotEmpty(message = "Indica al menos un tipo de evento") List<String> events,
        @Size(min = 24, max = 100, message = "El secreto debe tener entre 24 y 100 caracteres") String secret) {
}

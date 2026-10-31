package com.juniverse.webhook.provider;

import com.juniverse.webhook.config.WebhookProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API de administración de webhooks: suscripciones, historial de entregas y reenvío manual. */
@RestController
@RequestMapping("/api/webhooks")
public class SubscriptionController {

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

        static SubscriptionView of(Subscription s, boolean revealSecret, long now) {
            return new SubscriptionView(s.id(), s.url(), s.description(), s.events(), s.active(), revealSecret ? s.secret() : null,
                    "…" + s.secret().substring(s.secret().length() - 4), s.previousSecret() != null && s.previousSecretExpiresAt() > now);
        }
    }

    /**
     * Entrega para mostrar, con su historial de intentos.
     *
     * @param delivery datos de la entrega
     * @param attempts historial de intentos
     */
    public record DeliveryView(Delivery delivery, List<AttemptRecord> attempts) {
    }

    private final SubscriptionService service;
    private final DeliveryRepository deliveries;
    private final WebhookProperties properties;
    private final java.time.Clock clock;

    /**
     * Crea el controlador.
     *
     * @param service servicio de suscripciones
     * @param deliveries entregas
     * @param properties propiedades del webhook
     * @param clock reloj
     */
    public SubscriptionController(SubscriptionService service, DeliveryRepository deliveries, WebhookProperties properties, java.time.Clock clock) {
        this.service = service;
        this.deliveries = deliveries;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Crea una suscripción. Es la única respuesta que contiene el secreto completo: hay que guardarlo.
     *
     * @param request datos validados
     * @return 201 con la suscripción
     */
    @PostMapping("/subscriptions")
    public ResponseEntity<SubscriptionView> create(@Valid @RequestBody CreateRequest request) {
        Subscription created = service.create(request.url(), request.description(), request.events(), request.secret());
        return ResponseEntity.created(URI.create("/api/webhooks/subscriptions/" + created.id())).body(SubscriptionView.of(created, true, clock.millis()));
    }

    /**
     * Lista las suscripciones (sin secretos).
     *
     * @return suscripciones
     */
    @GetMapping("/subscriptions")
    public List<SubscriptionView> list() {
        return service.list().stream().map(s -> SubscriptionView.of(s, false, clock.millis())).toList();
    }

    /**
     * Elimina una suscripción y su historial.
     *
     * @param id identificador
     * @return 204
     */
    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Rota el secreto: durante el periodo de gracia los mensajes llevan las firmas de ambos.
     *
     * @param id identificador
     * @return la suscripción con el secreto nuevo
     */
    @PostMapping("/subscriptions/{id}/rotate-secret")
    public SubscriptionView rotate(@PathVariable String id) {
        return SubscriptionView.of(service.rotateSecret(id), true, clock.millis());
    }

    /**
     * Envía un evento de prueba a la suscripción.
     *
     * @param id identificador
     * @return identificador de la entrega creada
     */
    @PostMapping("/subscriptions/{id}/ping")
    public Map<String, String> ping(@PathVariable String id) {
        return Map.of("deliveryId", service.ping(id));
    }

    /**
     * Historial de entregas.
     *
     * @param subscriptionId filtro por suscripción
     * @param status filtro por estado (PENDING, IN_FLIGHT, DELIVERED, FAILED)
     * @param limit máximo de resultados (1 a 200)
     * @return entregas
     */
    @GetMapping("/deliveries")
    public List<Delivery> deliveries(@RequestParam(required = false) String subscriptionId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return deliveries.search(subscriptionId, status == null ? null : status.toUpperCase(), Math.min(Math.max(limit, 1), 200));
    }

    /**
     * Detalle de una entrega con cada intento.
     *
     * @param id entrega
     * @return la entrega y su historial
     */
    @GetMapping("/deliveries/{id}")
    public DeliveryView delivery(@PathVariable String id) {
        Delivery delivery = deliveries.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("No existe la entrega " + id));
        return new DeliveryView(delivery, deliveries.attempts(id));
    }

    /**
     * Reenvía una entrega fallida (por ejemplo, tras arreglar el receptor).
     *
     * @param id entrega
     * @return confirmación
     */
    @PostMapping("/deliveries/{id}/redeliver")
    public Map<String, String> redeliver(@PathVariable String id) {
        deliveries.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("No existe la entrega " + id));
        if (!deliveries.requeue(id, clock.millis())) {
            throw new IllegalStateException("Solo se pueden reenviar entregas en estado FAILED");
        }
        return Map.of("status", "queued");
    }

    /**
     * Política vigente de reintentos (informativa).
     *
     * @return intentos máximos y esperas
     */
    @GetMapping("/policy")
    public Map<String, Object> policy() {
        return Map.of("maxAttempts", properties.maxAttempts(), "backoffBaseMs", properties.backoffBaseMs(), "backoffMaxMs", properties.backoffMaxMs(),
                "requestTimeoutMs", properties.requestTimeoutMs());
    }
}

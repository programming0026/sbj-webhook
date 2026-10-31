package com.juniverse.webhook.provider;

import com.juniverse.webhook.config.WebhookProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Gestión de suscripciones: alta, baja, rotación del secreto y envío de un evento de prueba. */
@Service
public class SubscriptionService {

    /** Tipos de evento que este proveedor emite. */
    public static final Set<String> EVENT_TYPES = Set.of("order.created", "order.paid", "order.cancelled", "ping");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SubscriptionRepository repository;
    private final TargetValidator targetValidator;
    private final EventPublisher publisher;
    private final WebhookProperties properties;
    private final Clock clock;

    /**
     * Crea el servicio.
     *
     * @param repository suscripciones
     * @param targetValidator protección SSRF
     * @param publisher publicador de eventos
     * @param properties propiedades del webhook
     * @param clock reloj
     */
    public SubscriptionService(SubscriptionRepository repository, TargetValidator targetValidator, EventPublisher publisher,
            WebhookProperties properties, Clock clock) {
        this.repository = repository;
        this.targetValidator = targetValidator;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Genera un secreto aleatorio y seguro (256 bits).
     *
     * @return secreto con el prefijo {@code whsec_}
     */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Crea una suscripción.
     *
     * @param url destino
     * @param description descripción opcional
     * @param events tipos de evento (o {@code *})
     * @param secret secreto propio opcional; si es nulo se genera uno
     * @return la suscripción creada (es la única vez que se puede ver el secreto completo)
     * @throws IllegalArgumentException si la URL o los eventos no son válidos
     */
    public Subscription create(String url, String description, List<String> events, String secret) {
        targetValidator.validate(url);
        for (String event : events) {
            if (!event.equals("*") && !EVENT_TYPES.contains(event)) {
                throw new IllegalArgumentException("Tipo de evento desconocido: " + event + ". Disponibles: " + EVENT_TYPES.stream().sorted().toList());
            }
        }
        Subscription subscription = new Subscription(UUID.randomUUID().toString(), url.strip(), description, secret == null ? newSecret() : secret,
                null, 0, List.copyOf(events), true, clock.millis());
        repository.insert(subscription);
        return subscription;
    }

    /**
     * Busca una suscripción.
     *
     * @param id identificador
     * @return la suscripción
     * @throws NoSuchElementException si no existe
     */
    public Subscription get(String id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("No existe la suscripción " + id));
    }

    /**
     * Lista las suscripciones.
     *
     * @return todas
     */
    public List<Subscription> list() {
        return repository.findAll();
    }

    /**
     * Elimina una suscripción.
     *
     * @param id identificador
     * @throws NoSuchElementException si no existe
     */
    public void delete(String id) {
        if (repository.delete(id) == 0) {
            throw new NoSuchElementException("No existe la suscripción " + id);
        }
    }

    /**
     * Rota el secreto: genera uno nuevo y mantiene el anterior un tiempo, durante el cual cada mensaje lleva DOS firmas. Así el
     * receptor puede actualizar su configuración sin perder ningún mensaje por el camino.
     *
     * @param id identificador
     * @return la suscripción con el secreto nuevo
     * @throws NoSuchElementException si no existe
     */
    public Subscription rotateSecret(String id) {
        Subscription current = get(id);
        long expires = clock.millis() + properties.rotationGraceHours() * 3_600_000L;
        repository.rotate(id, newSecret(), current.secret(), expires);
        return get(id);
    }

    /**
     * Envía un evento de prueba a una suscripción (sin importar a qué eventos esté suscrita).
     *
     * @param id identificador
     * @return identificador de la entrega creada
     * @throws NoSuchElementException si no existe
     */
    public String ping(String id) {
        return publisher.publishTo(get(id), "ping", java.util.Map.of("message", "Evento de prueba"));
    }
}

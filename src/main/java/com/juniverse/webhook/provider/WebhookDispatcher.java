package com.juniverse.webhook.provider;

import com.juniverse.webhook.config.WebhookProperties;
import com.juniverse.webhook.security.WebhookSignature;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El <b>despachador</b>: entrega los avisos pendientes. Cada vuelta:
 * <ol>
 *   <li>recupera las entregas "en vuelo" abandonadas por una caída;</li>
 *   <li>toma las entregas cuyo momento ya llegó y las reclama (atómicamente);</li>
 *   <li>las envía en paralelo con hilos virtuales de Java 21 (cada envío espera la red, justo para lo que sirven);</li>
 *   <li>según la respuesta: 2xx = entregada; 410 = el receptor pide que no se le envíe más (se desactiva la suscripción);
 *       cualquier otra cosa (5xx, 4xx, tiempo agotado, red caída) = reintento con retroceso exponencial hasta agotar los intentos.</li>
 * </ol>
 * Garantía: <b>al menos una vez</b>. Si el receptor procesó el mensaje pero su respuesta se perdió, se reintentará y llegará duplicado;
 * por eso cada mensaje lleva un identificador único (X-Webhook-Id) y el receptor debe descartar los repetidos.
 */
@Component
public class WebhookDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final DeliveryRepository deliveries;
    private final SubscriptionRepository subscriptions;
    private final TargetValidator targetValidator;
    private final WebhookProperties properties;
    private final BackoffPolicy backoff;
    private final Clock clock;
    private final HttpClient http;

    /**
     * Crea el despachador.
     *
     * @param deliveries entregas
     * @param subscriptions suscripciones
     * @param targetValidator protección SSRF
     * @param properties propiedades del webhook
     * @param clock reloj
     */
    public WebhookDispatcher(DeliveryRepository deliveries, SubscriptionRepository subscriptions, TargetValidator targetValidator,
            WebhookProperties properties, Clock clock) {
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.targetValidator = targetValidator;
        this.properties = properties;
        this.backoff = new BackoffPolicy(properties.backoffBaseMs(), properties.backoffMaxMs());
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER) // una URL pública podría redirigir a una interna (SSRF)
                .build();
    }

    /** Vuelta periódica del despachador. */
    @Scheduled(fixedDelayString = "${webhook.dispatch-interval-ms}")
    public void tick() {
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            LOG.error("Falló una vuelta del despachador (se reintentará)", e);
        }
    }

    /**
     * Procesa las entregas que ya toca intentar.
     *
     * @return cuántas entregas se intentaron
     */
    public int dispatchDue() {
        long now = clock.millis();
        int recovered = deliveries.releaseStale(now - properties.staleClaimMs());
        if (recovered > 0) {
            LOG.warn("{} entrega(s) abandonadas se vuelven a encolar", recovered);
        }
        List<Delivery> due = deliveries.findDue(now, properties.batchSize());
        if (due.isEmpty()) {
            return 0;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            int claimed = 0;
            for (Delivery delivery : due) {
                if (deliveries.claim(delivery.id(), now)) {
                    claimed++;
                    futures.add(executor.submit(() -> attempt(delivery)));
                }
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    LOG.error("Error inesperado al entregar", e.getCause());
                }
            }
            return claimed;
        }
    }

    /**
     * Hace un intento de entrega y registra el resultado.
     *
     * @param delivery entrega reclamada
     */
    void attempt(Delivery delivery) {
        int attemptNo = delivery.attempts() + 1;
        Subscription subscription = subscriptions.findById(delivery.subscriptionId()).orElse(null);
        if (subscription == null || !subscription.active()) {
            deliveries.addAttempt(delivery.id(), new AttemptRecord(attemptNo, null, "suscripción eliminada o desactivada", 0, clock.millis()));
            deliveries.markFailed(delivery.id(), attemptNo, null, "suscripción eliminada o desactivada");
            return;
        }
        long start = System.nanoTime();
        Integer status = null;
        String error = null;
        try {
            targetValidator.validate(subscription.url());
            status = send(subscription, delivery);
        } catch (IOException | IllegalArgumentException e) {
            error = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "sin detalle" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrumpido";
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        long now = clock.millis();
        if (status != null && status >= 400) {
            error = "El receptor respondió HTTP " + status;
        }
        deliveries.addAttempt(delivery.id(), new AttemptRecord(attemptNo, status, error, durationMs, now));

        if (status != null && status >= 200 && status < 300) {
            deliveries.markDelivered(delivery.id(), attemptNo, status, now);
        } else if (status != null && status == 410) {
            LOG.warn("El receptor de {} respondió 410 Gone: se desactiva la suscripción", subscription.url());
            subscriptions.setActive(subscription.id(), false);
            deliveries.markFailed(delivery.id(), attemptNo, status, "El receptor respondió 410 Gone: suscripción desactivada");
        } else if (attemptNo >= properties.maxAttempts()) {
            LOG.warn("Entrega {} fallida tras {} intentos: {}", delivery.id(), attemptNo, error);
            deliveries.markFailed(delivery.id(), attemptNo, status, error);
        } else {
            deliveries.markRetry(delivery.id(), attemptNo, status, error, now + backoff.delayWithJitter(attemptNo));
        }
    }

    private int send(Subscription subscription, Delivery delivery) throws IOException, InterruptedException {
        long nowMillis = clock.millis();
        long timestamp = clock.instant().getEpochSecond();
        byte[] body = delivery.payload().getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(subscription.url()))
                .timeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Juniverse-Webhooks/1.0")
                .header(WebhookSignature.HEADER_ID, delivery.eventId())
                .header(WebhookSignature.HEADER_EVENT, delivery.eventType())
                .header(WebhookSignature.HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(WebhookSignature.HEADER_SIGNATURE, WebhookSignature.header(subscription.signingSecrets(nowMillis), timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}

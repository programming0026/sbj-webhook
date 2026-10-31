package com.juniverse.webhook.receiver;

import com.juniverse.webhook.config.WebhookProperties;
import com.juniverse.webhook.security.WebhookSignature;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El <b>receptor</b>: el endpoint que recibe los webhooks. Las reglas de oro de un buen receptor están aquí:
 * <ol>
 *   <li><b>Verificar la firma</b> sobre los bytes EXACTOS del cuerpo, con comparación en tiempo constante.</li>
 *   <li><b>Rechazar mensajes antiguos</b> (protección contra repeticiones).</li>
 *   <li><b>Ser idempotente</b>: el mismo evento puede llegar varias veces; se procesa una sola.</li>
 *   <li><b>Responder rápido con 2xx</b>: guardar y contestar; el trabajo pesado va en segundo plano. Si tardas, el proveedor
 *       agota el tiempo y reintenta.</li>
 *   <li>Responder <b>401</b> si la firma no es válida (sin decir por qué) y <b>400</b> si faltan cabeceras.</li>
 * </ol>
 */
@RestController
public class ReceiverController {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiverController.class);
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final ReceiverState state;
    private final ReceivedEventStore store;
    private final WebhookProperties properties;
    private final Clock clock;

    /**
     * Crea el controlador.
     *
     * @param state secretos y modo simulado
     * @param store eventos recibidos
     * @param properties propiedades del webhook
     * @param clock reloj
     */
    public ReceiverController(ReceiverState state, ReceivedEventStore store, WebhookProperties properties, Clock clock) {
        this.state = state;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Endpoint de webhooks. Recibe el cuerpo como bytes (no como objeto) para poder verificar la firma antes de interpretarlo.
     *
     * @param body cuerpo exacto
     * @param eventId cabecera X-Webhook-Id
     * @param eventType cabecera X-Webhook-Event
     * @param timestamp cabecera X-Webhook-Timestamp
     * @param signature cabecera X-Webhook-Signature
     * @return 200 si se aceptó (o era un duplicado), 400 si faltan cabeceras, 401 si la firma o la fecha no son válidas
     * @throws InterruptedException si se interrumpe la espera simulada
     */
    @PostMapping("/webhooks/orders")
    public ResponseEntity<Map<String, String>> receive(@RequestBody byte[] body,
            @RequestHeader(value = WebhookSignature.HEADER_ID, required = false) String eventId,
            @RequestHeader(value = WebhookSignature.HEADER_EVENT, required = false) String eventType,
            @RequestHeader(value = WebhookSignature.HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(value = WebhookSignature.HEADER_SIGNATURE, required = false) String signature) throws InterruptedException {
        state.requestStarted();
        try {
            return process(body, eventId, eventType, timestamp, signature);
        } finally {
            state.requestFinished();
        }
    }

    private ResponseEntity<Map<String, String>> process(byte[] body, String eventId, String eventType, String timestamp, String signature)
            throws InterruptedException {

        // --- Simulación de fallos (SOLO demostración) ---
        switch (state.mode()) {
            case ERROR_500 -> {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "fallo simulado"));
            }
            case GONE_410 -> {
                return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "ya no quiero más avisos"));
            }
            // Receptor lento: tarda más de lo que el proveedor espera, pero AL FINAL procesa el mensaje, como un receptor real
            // sobrecargado. El proveedor ya habrá reintentado, así que este mensaje llegará duplicado: la idempotencia lo absorbe.
            case TIMEOUT -> Thread.sleep(properties.requestTimeoutMs() * 4);
            case OK -> { }
        }

        if (eventId == null || eventType == null || timestamp == null || signature == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "faltan cabeceras X-Webhook-*"));
        }
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of("error", "cuerpo demasiado grande"));
        }
        WebhookSignature.Verification result = WebhookSignature.verify(state.secrets(), timestamp, signature, body, clock.instant().getEpochSecond(),
                properties.toleranceSeconds());
        if (result != WebhookSignature.Verification.VALID) {
            LOG.warn("Webhook {} rechazado: {}", eventId, result);
            // El mismo mensaje para cualquier motivo: no se le dice al atacante qué parte falló
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "firma inválida"));
        }
        // Solo después de verificar se interpreta el contenido
        boolean isNew = store.insertIfNew(eventId, eventType, new String(body, java.nio.charset.StandardCharsets.UTF_8), clock.millis());
        if (!isNew) {
            LOG.info("Webhook {} duplicado: ya se había procesado", eventId);
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }
        LOG.info("Webhook {} ({}) recibido", eventId, eventType);
        return ResponseEntity.ok(Map.of("status", "received"));
    }

    /**
     * Eventos recibidos hasta ahora.
     *
     * @return lista de eventos
     */
    @GetMapping("/api/receiver/events")
    public List<ReceivedEventStore.Received> events() {
        return store.all();
    }

    /**
     * Configura el comportamiento simulado (solo demostración): {@code {"mode":"ERROR_500"}}.
     *
     * @param body objeto con el campo {@code mode}
     * @return modo actual
     */
    @PostMapping("/api/receiver/chaos")
    public Map<String, String> chaos(@RequestBody Map<String, String> body) {
        state.setMode(ReceiverState.Mode.valueOf(body.getOrDefault("mode", "OK")));
        return Map.of("mode", state.mode().name());
    }

    /**
     * Configura los secretos que acepta el receptor (en un receptor real vienen de la configuración).
     *
     * @param body objeto con el campo {@code secrets}
     * @return cuántos secretos acepta
     */
    @PostMapping("/api/receiver/secrets")
    public Map<String, Integer> secrets(@RequestBody Map<String, List<String>> body) {
        state.setSecrets(body.getOrDefault("secrets", List.of()));
        return Map.of("accepted", state.secrets().size());
    }
}

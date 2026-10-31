package com.juniverse.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import com.juniverse.webhook.receiver.ReceivedEventStore;
import com.juniverse.webhook.receiver.ReceiverState;
import com.juniverse.webhook.security.WebhookSignature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * El flujo completo por HTTP real: la aplicación entrega webhooks a su propio receptor. Los tiempos están acortados (reintentos cada
 * ~50 ms, tiempo máximo de 500 ms) para que las pruebas sean rápidas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "webhook.dispatch-interval-ms=50", "webhook.request-timeout-ms=500", "webhook.max-attempts=3", "webhook.backoff-base-ms=50",
        "webhook.backoff-max-ms=200", "webhook.stale-claim-ms=1000", "webhook.allow-private-targets=true"})
@DisplayName("Webhooks: del evento a la entrega")
class WebhookFlowTest {

    private static final String SECRET = "whsec_test_flow_secret_0123456789";

    @LocalServerPort
    private int port;
    @Autowired private ReceiverState receiver;
    @Autowired private ReceivedEventStore received;
    @Autowired private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        // Aislamiento: la prueba anterior pudo dejar envíos en curso. Se eliminan primero las suscripciones (así nada nuevo se envía) y se
        // espera a que termine lo que ya estaba "en vuelo"; si no, una entrega tardía aparecería en el receptor de la prueba siguiente.
        jdbc.update("delete from subscriptions");
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.queryForObject("select count(*) from deliveries where status = 'IN_FLIGHT'", Integer.class) == 0);
        receiver.setMode(ReceiverState.Mode.OK);
        // un receptor lento puede seguir procesando un mensaje mucho después de que el proveedor se rindiera: se espera a que termine
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> receiver.activeRequests() == 0);
        jdbc.update("delete from attempts");
        jdbc.update("delete from deliveries");
        received.clear();
        receiver.setMode(ReceiverState.Mode.OK);
        receiver.setSecrets(List.of(SECRET));
    }

    // ------------------------------------------------------------------ utilidades
    private HttpResponse<String> call(String method, String path, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return call("POST", path, json, Map.of());
    }

    private String subscribe(String events, String secret) throws Exception {
        String body = "{\"url\":\"http://localhost:" + port + "/webhooks/orders\",\"description\":\"prueba\",\"events\":" + events
                + (secret == null ? "" : ",\"secret\":\"" + secret + "\"") + "}";
        HttpResponse<String> response = post("/api/webhooks/subscriptions", body);
        assertEquals(201, response.statusCode(), response.body());
        return JsonPath.read(response.body(), "$.id");
    }

    private String createOrder(String customer) throws Exception {
        HttpResponse<String> response = post("/api/orders", "{\"customer\":\"" + customer + "\",\"amount\":99.90}");
        assertEquals(201, response.statusCode(), response.body());
        return JsonPath.read(response.body(), "$.id");
    }

    private String deliveryStatus(String subscriptionId) throws Exception {
        List<String> statuses = JsonPath.read(call("GET", "/api/webhooks/deliveries?subscriptionId=" + subscriptionId, null, Map.of()).body(), "$[*].status");
        return statuses.isEmpty() ? "NONE" : statuses.get(0);
    }

    private String firstDeliveryId(String subscriptionId) throws Exception {
        return JsonPath.read(call("GET", "/api/webhooks/deliveries?subscriptionId=" + subscriptionId, null, Map.of()).body(), "$[0].id");
    }

    private void awaitStatus(String subscriptionId, String expected) {
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> expected.equals(deliveryStatus(subscriptionId)));
    }

    // ------------------------------------------------------------------ camino feliz
    @Test
    @DisplayName("un evento se entrega firmado al receptor, que lo acepta una sola vez")
    void happyPath() throws Exception {
        String subscription = subscribe("[\"*\"]", SECRET);
        String orderId = createOrder("ana");

        awaitStatus(subscription, "DELIVERED");
        var events = received.all();
        assertEquals(1, events.size());
        assertEquals("order.created", events.get(0).eventType());
        assertEquals(orderId, JsonPath.read(events.get(0).payload(), "$.data.id"));
        assertEquals("ana", JsonPath.read(events.get(0).payload(), "$.data.customer"));
        assertTrue(events.get(0).eventId().startsWith("evt_"));

        String detail = call("GET", "/api/webhooks/deliveries/" + firstDeliveryId(subscription), null, Map.of()).body();
        assertEquals(1, (int) JsonPath.read(detail, "$.delivery.attempts"));
        assertEquals(200, (int) JsonPath.read(detail, "$.attempts[0].statusCode"));
    }

    @Test
    @DisplayName("el secreto solo se ve al crear la suscripción; después se enmascara")
    void secretIsOnlyShownOnce() throws Exception {
        HttpResponse<String> created = post("/api/webhooks/subscriptions", "{\"url\":\"http://localhost:" + port + "/webhooks/orders\",\"events\":[\"*\"]}");
        String secret = JsonPath.read(created.body(), "$.secret");
        assertTrue(secret.startsWith("whsec_") && secret.length() > 40, "el servidor genera un secreto largo y aleatorio");

        String listing = call("GET", "/api/webhooks/subscriptions", null, Map.of()).body();
        assertFalse(listing.contains(secret));
        assertEquals("…" + secret.substring(secret.length() - 4), JsonPath.read(listing, "$[0].secretHint"));
    }

    @Test
    @DisplayName("solo se envían los eventos a los que la suscripción se apuntó")
    void filtersByEventType() throws Exception {
        String onlyPaid = subscribe("[\"order.paid\"]", SECRET);
        String orderId = createOrder("luis");
        Thread.sleep(500);
        assertEquals("NONE", deliveryStatus(onlyPaid), "order.created no le interesa");

        assertEquals(200, post("/api/orders/" + orderId + "/pay", null).statusCode());
        awaitStatus(onlyPaid, "DELIVERED");
        assertEquals("order.paid", received.all().get(0).eventType());
    }

    @Test
    @DisplayName("varios suscriptores reciben el mismo evento con el mismo identificador")
    void severalSubscribersGetTheSameEvent() throws Exception {
        String first = subscribe("[\"*\"]", SECRET);
        String second = subscribe("[\"order.created\"]", SECRET);
        createOrder("eva");
        awaitStatus(first, "DELIVERED");
        awaitStatus(second, "DELIVERED");
        List<String> eventIds = jdbc.queryForList("select event_id from deliveries", String.class);
        assertEquals(2, eventIds.size());
        assertEquals(1, eventIds.stream().distinct().count(), "una sola identidad de evento para ambas entregas");
        assertEquals(1, received.all().size(), "el receptor deduplicó por id de evento");
    }

    // ------------------------------------------------------------------ reintentos
    @Test
    @DisplayName("si el receptor falla, se reintenta con espera y el historial guarda cada intento")
    void retriesUntilItWorks() throws Exception {
        String subscription = subscribe("[\"*\"]", SECRET);
        receiver.setMode(ReceiverState.Mode.ERROR_500);
        createOrder("ana");

        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> jdbc.queryForObject("select coalesce(max(attempts), 0) from deliveries", Integer.class) >= 2);
        assertTrue(received.all().isEmpty());

        receiver.setMode(ReceiverState.Mode.OK);
        awaitStatus(subscription, "DELIVERED");

        String detail = call("GET", "/api/webhooks/deliveries/" + firstDeliveryId(subscription), null, Map.of()).body();
        List<Integer> codes = JsonPath.read(detail, "$.attempts[*].statusCode");
        assertEquals(500, codes.get(0));
        assertEquals(200, codes.get(codes.size() - 1));
        assertEquals(1, received.all().size());
    }

    @Test
    @DisplayName("agotados los intentos la entrega queda FAILED; tras arreglar el receptor se puede reenviar")
    void failsThenCanBeRedelivered() throws Exception {
        String subscription = subscribe("[\"*\"]", SECRET);
        receiver.setMode(ReceiverState.Mode.ERROR_500);
        createOrder("ana");

        awaitStatus(subscription, "FAILED");
        String id = firstDeliveryId(subscription);
        assertEquals(3, (int) JsonPath.read(call("GET", "/api/webhooks/deliveries/" + id, null, Map.of()).body(), "$.delivery.attempts"));

        receiver.setMode(ReceiverState.Mode.OK);
        assertEquals(200, post("/api/webhooks/deliveries/" + id + "/redeliver", null).statusCode());
        awaitStatus(subscription, "DELIVERED");
        assertEquals(1, received.all().size());
        assertEquals(409, post("/api/webhooks/deliveries/" + id + "/redeliver", null).statusCode(), "una entrega ya entregada no se reenvía");
        assertEquals(404, post("/api/webhooks/deliveries/no-existe/redeliver", null).statusCode());
    }

    @Test
    @DisplayName("un receptor lento (más del tiempo máximo) cuenta como fallo y se reintenta")
    void timeoutIsAFailure() throws Exception {
        String subscription = subscribe("[\"*\"]", SECRET);
        receiver.setMode(ReceiverState.Mode.TIMEOUT);
        createOrder("ana");

        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> jdbc.queryForObject("select coalesce(max(attempts), 0) from deliveries", Integer.class) >= 1);
        String detail = call("GET", "/api/webhooks/deliveries/" + firstDeliveryId(subscription), null, Map.of()).body();
        assertNull(JsonPath.read(detail, "$.attempts[0].statusCode"));
        assertTrue(((String) JsonPath.read(detail, "$.attempts[0].error")).contains("Timeout"), detail);
    }

    @Test
    @DisplayName("receptor lento: procesa el mensaje DESPUÉS de que el proveedor se rindió; el reintento llega duplicado y se descarta")
    void slowReceiverStillProcessesAndTheRetryIsDeduplicated() throws Exception {
        String subscription = subscribe("[\"*\"]", SECRET);
        receiver.setMode(ReceiverState.Mode.TIMEOUT);
        createOrder("ana");

        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> jdbc.queryForObject("select coalesce(max(attempts), 0) from deliveries", Integer.class) >= 1);
        receiver.setMode(ReceiverState.Mode.OK);          // el receptor "se recupera": el reintento va rápido
        awaitStatus(subscription, "DELIVERED");
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> receiver.activeRequests() == 0); // termina también la petición lenta original

        assertEquals(1, received.all().size(), "el mismo evento llegó dos veces (la lenta y el reintento) pero se registró una sola vez");
        assertTrue(jdbc.queryForObject("select attempts from deliveries", Integer.class) >= 2);
    }

    @Test
    @DisplayName("410 Gone: el receptor pide que no se le avise más y la suscripción se desactiva sin reintentos")
    void goneDeactivatesTheSubscription() throws Exception {
        String subscription = subscribe("[\"*\"]", SECRET);
        receiver.setMode(ReceiverState.Mode.GONE_410);
        createOrder("ana");

        awaitStatus(subscription, "FAILED");
        assertEquals(1, (int) JsonPath.read(call("GET", "/api/webhooks/deliveries/" + firstDeliveryId(subscription), null, Map.of()).body(), "$.delivery.attempts"));
        assertEquals(false, JsonPath.read(call("GET", "/api/webhooks/subscriptions", null, Map.of()).body(), "$[0].active"));

        receiver.setMode(ReceiverState.Mode.OK);
        createOrder("luis");
        Thread.sleep(500);
        assertEquals(1, JsonPath.<List<String>>read(call("GET", "/api/webhooks/deliveries?subscriptionId=" + subscription, null, Map.of()).body(), "$[*].id").size(),
                "una suscripción desactivada no recibe eventos nuevos");
    }

    @Test
    @DisplayName("una entrega 'en vuelo' abandonada por una caída se recupera sola")
    void abandonedClaimsAreRecovered() throws Exception {
        String subscription = subscribe("[\"*\"]", SECRET);
        long longAgo = System.currentTimeMillis() - 60_000;
        jdbc.update("insert into deliveries (id, subscription_id, event_id, event_type, payload, status, attempts, next_attempt_at, claimed_at, created_at) "
                + "values ('stuck', ?, 'evt_stuck', 'ping', '{\"id\":\"evt_stuck\",\"type\":\"ping\",\"created\":1,\"data\":{}}', 'IN_FLIGHT', 0, ?, ?, ?)",
                subscription, longAgo, longAgo, longAgo);

        awaitStatus(subscription, "DELIVERED");
        assertEquals("evt_stuck", received.all().get(0).eventId());
    }

    @Test
    @DisplayName("20 pedidos: cada uno se entrega exactamente una vez, con un solo intento")
    void manyEventsAreDeliveredExactlyOnce() throws Exception {
        subscribe("[\"*\"]", SECRET);
        for (int i = 0; i < 20; i++) {
            createOrder("cliente" + i);
        }
        Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> received.all().size() == 20);
        assertEquals(20, jdbc.queryForObject("select count(*) from deliveries where status = 'DELIVERED'", Integer.class));
        assertEquals(20, received.all().stream().map(ReceivedEventStore.Received::eventId).distinct().count());
        assertEquals(20, jdbc.queryForObject("select sum(attempts) from deliveries", Integer.class), "ningún reintento innecesario: un intento por entrega");
    }

    // ------------------------------------------------------------------ rotación
    @Test
    @DisplayName("rotar el secreto: durante la gracia los mensajes llevan dos firmas y funcionan con el secreto viejo y con el nuevo")
    void secretRotation() throws Exception {
        String subscription = subscribe("[\"*\"]", SECRET);
        String newSecret = JsonPath.read(post("/api/webhooks/subscriptions/" + subscription + "/rotate-secret", null).body(), "$.secret");
        assertNotNull(newSecret);
        assertEquals(true, JsonPath.read(call("GET", "/api/webhooks/subscriptions", null, Map.of()).body(), "$[0].rotating"));

        // 1) el receptor todavía no actualizó su configuración (sigue con el secreto viejo): sigue funcionando
        createOrder("ana");
        awaitStatus(subscription, "DELIVERED");
        assertEquals(1, received.all().size());

        // 2) el receptor ya usa el secreto nuevo: también funciona
        jdbc.update("delete from deliveries");
        received.clear();
        receiver.setSecrets(List.of(newSecret));
        createOrder("luis");
        awaitStatus(subscription, "DELIVERED");
        assertEquals(1, received.all().size());
    }

    // ------------------------------------------------------------------ el receptor visto desde fuera
    private HttpResponse<String> deliverManually(String id, String type, long timestamp, String secret, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.header(List.of(secret), timestamp, bytes);
        return call("POST", "/webhooks/orders", body, Map.of(WebhookSignature.HEADER_ID, id, WebhookSignature.HEADER_EVENT, type,
                WebhookSignature.HEADER_TIMESTAMP, String.valueOf(timestamp), WebhookSignature.HEADER_SIGNATURE, signature));
    }

    @Test
    @DisplayName("el receptor acepta un mensaje bien firmado y lo registra una sola vez aunque llegue repetido (idempotencia)")
    void receiverIsIdempotent() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String body = "{\"id\":\"evt_manual\",\"type\":\"order.paid\",\"data\":{}}";

        HttpResponse<String> first = deliverManually("evt_manual", "order.paid", now, SECRET, body);
        assertEquals(200, first.statusCode());
        assertEquals("received", JsonPath.read(first.body(), "$.status"));
        HttpResponse<String> second = deliverManually("evt_manual", "order.paid", now, SECRET, body);
        assertEquals(200, second.statusCode(), "el duplicado también recibe 2xx para que el proveedor deje de reintentar");
        assertEquals("duplicate", JsonPath.read(second.body(), "$.status"));
        assertEquals(1, received.all().size());
    }

    @Test
    @DisplayName("el receptor rechaza: firma de otro secreto, mensaje antiguo, cuerpo alterado y cabeceras ausentes")
    void receiverRejections() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String body = "{\"id\":\"evt_x\",\"type\":\"order.paid\",\"data\":{}}";

        assertEquals(401, deliverManually("evt_1", "order.paid", now, "un_secreto_que_no_es_el_correcto_123", body).statusCode());
        assertEquals(401, deliverManually("evt_2", "order.paid", now - 3_600, SECRET, body).statusCode(), "ataque de repetición: firma válida pero de hace una hora");

        // el cuerpo se altera después de firmar
        byte[] original = body.getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.header(List.of(SECRET), now, original);
        HttpResponse<String> tampered = call("POST", "/webhooks/orders", body.replace("order.paid", "order.refund"), Map.of(WebhookSignature.HEADER_ID, "evt_3",
                WebhookSignature.HEADER_EVENT, "order.paid", WebhookSignature.HEADER_TIMESTAMP, String.valueOf(now), WebhookSignature.HEADER_SIGNATURE, signature));
        assertEquals(401, tampered.statusCode());
        assertEquals("firma inválida", JsonPath.read(tampered.body(), "$.error"), "no se revela qué parte falló");

        assertEquals(400, call("POST", "/webhooks/orders", body, Map.of()).statusCode());
        assertEquals(0, received.all().size(), "nada de lo rechazado se registró");
    }

    // ------------------------------------------------------------------ API de administración
    @Test
    @DisplayName("la API valida las suscripciones: URL, eventos y secreto")
    void subscriptionValidation() throws Exception {
        assertEquals(400, post("/api/webhooks/subscriptions", "{\"url\":\"\",\"events\":[]}").statusCode());
        assertEquals(400, post("/api/webhooks/subscriptions", "{\"url\":\"ftp://x.com/hook\",\"events\":[\"*\"]}").statusCode());
        assertEquals(400, post("/api/webhooks/subscriptions", "{\"url\":\"http://localhost:" + port + "/webhooks/orders\",\"events\":[\"order.inventado\"]}").statusCode());
        assertEquals(400, post("/api/webhooks/subscriptions", "{\"url\":\"http://localhost:" + port + "/webhooks/orders\",\"events\":[\"*\"],\"secret\":\"corto\"}").statusCode());
        assertEquals(404, call("DELETE", "/api/webhooks/subscriptions/no-existe", null, Map.of()).statusCode());
        assertEquals(404, call("GET", "/api/webhooks/deliveries/no-existe", null, Map.of()).statusCode());
        assertEquals(400, post("/api/orders", "{\"customer\":\"\",\"amount\":0}").statusCode());
    }

    @Test
    @DisplayName("ping de prueba, estados del pedido y borrado de la suscripción con su historial")
    void pingOrdersAndDelete() throws Exception {
        String subscription = subscribe("[\"order.paid\"]", SECRET);
        String ping = JsonPath.read(post("/api/webhooks/subscriptions/" + subscription + "/ping", null).body(), "$.deliveryId");
        assertNotNull(ping);
        awaitStatus(subscription, "DELIVERED");
        assertEquals("ping", received.all().get(0).eventType(), "el ping llega aunque no esté suscrita a 'ping'");

        String orderId = createOrder("ana");
        assertEquals(200, post("/api/orders/" + orderId + "/cancel", null).statusCode());
        assertEquals(409, post("/api/orders/" + orderId + "/pay", null).statusCode(), "un pedido cancelado no puede pagarse");
        assertEquals(404, post("/api/orders/ord_no_existe/pay", null).statusCode());

        assertEquals(204, call("DELETE", "/api/webhooks/subscriptions/" + subscription, null, Map.of()).statusCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from deliveries where subscription_id = ?", Integer.class, subscription));
    }

    @Test
    @DisplayName("si el negocio falla, no se crea ningún aviso (misma transacción)")
    void failedBusinessOperationCreatesNoDelivery() throws Exception {
        subscribe("[\"*\"]", SECRET);
        long before = jdbc.queryForObject("select count(*) from deliveries", Long.class);
        assertEquals(404, post("/api/orders/ord_no_existe/pay", null).statusCode());
        assertEquals(before, jdbc.queryForObject("select count(*) from deliveries", Long.class));
    }
}

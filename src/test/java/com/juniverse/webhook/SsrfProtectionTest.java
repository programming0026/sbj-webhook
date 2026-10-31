package com.juniverse.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Con la protección SSRF activa (como en producción), la API rechaza destinos internos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "webhook.allow-private-targets=false")
@DisplayName("Protección SSRF en la API")
class SsrfProtectionTest {

    @LocalServerPort
    private int port;

    private int subscribe(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/subscriptions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + url + "\",\"events\":[\"*\"]}")).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    @Test
    @DisplayName("localhost, redes privadas y metadatos de la nube se rechazan; un destino público se acepta")
    void blocksInternalTargets() throws Exception {
        assertEquals(400, subscribe("http://localhost:6379/"));
        assertEquals(400, subscribe("http://127.0.0.1:8080/webhooks/orders"));
        assertEquals(400, subscribe("http://192.168.0.10/hook"));
        assertEquals(400, subscribe("http://169.254.169.254/latest/meta-data/"));
        assertEquals(400, subscribe("file:///etc/passwd"));
        assertEquals(201, subscribe("https://93.184.216.34/webhooks"));
    }
}

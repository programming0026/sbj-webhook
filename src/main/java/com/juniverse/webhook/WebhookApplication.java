package com.juniverse.webhook;

import com.juniverse.webhook.config.WebhookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <b>Webhooks</b>: en lugar de que un cliente pregunte una y otra vez "¿pasó algo?" (polling), el sistema que tiene la
 * información <b>llama al cliente</b> mediante una petición HTTP POST en cuanto ocurre un evento.
 *
 * <p>El ejemplo contiene las dos mitades en una misma aplicación para poder verlas funcionar juntas:</p>
 * <ul>
 *   <li><b>Proveedor (quien envía)</b>: gestiona suscripciones, genera eventos (pedido creado, pagado, cancelado), los firma
 *       y los entrega con reintentos, registro de intentos y reenvío manual.</li>
 *   <li><b>Receptor (quien recibe)</b>: un endpoint que verifica la firma y la antigüedad del mensaje, descarta duplicados y
 *       responde rápido.</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookApplication {

    /**
     * Arranca la aplicación.
     *
     * @param args argumentos de la línea de comandos
     */
    public static void main(String[] args) {
        SpringApplication.run(WebhookApplication.class, args);
    }
}

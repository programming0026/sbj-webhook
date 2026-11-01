package com.juniverse.webhook.model;

import com.juniverse.webhook.config.WebhookProperties;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Estado del receptor de ejemplo: los secretos que acepta y, solo para demostraciones y pruebas, un interruptor para simular
 * fallos. <b>El interruptor no existiría en un receptor real.</b>
 */
@Component
public class ReceiverState {

    /** Comportamiento simulado del receptor. */
    public enum Mode {
        /** Funciona con normalidad. */
        OK,
        /** Responde 500. */
        ERROR_500,
        /** Tarda más que el tiempo máximo del proveedor. */
        TIMEOUT,
        /** Responde 410 Gone ("ya no quiero más avisos"). */
        GONE_410
    }

    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);
    private final AtomicInteger activeRequests = new AtomicInteger();

    /**
     * Crea el estado con el secreto configurado.
     *
     * @param properties propiedades del webhook
     */
    public ReceiverState(WebhookProperties properties) {
        secrets.add(properties.receiverSecret());
    }

    /**
     * Secretos aceptados.
     *
     * @return lista (más de uno durante una rotación)
     */
    public List<String> secrets() {
        return List.copyOf(secrets);
    }

    /**
     * Reemplaza los secretos aceptados.
     *
     * @param newSecrets secretos nuevos
     */
    public void setSecrets(List<String> newSecrets) {
        secrets.clear();
        secrets.addAll(newSecrets);
    }

    /**
     * Modo simulado.
     *
     * @return modo actual
     */
    public Mode mode() {
        return mode.get();
    }

    /**
     * Cambia el modo simulado.
     *
     * @param newMode modo nuevo
     */
    public void setMode(Mode newMode) {
        mode.set(newMode);
    }

    /**
     * Peticiones que el receptor está atendiendo ahora mismo. Un receptor lento puede seguir procesando un mensaje DESPUÉS de que el
     * proveedor se rindió por tiempo agotado; este contador permite esperar a que termine.
     *
     * @return número de peticiones en curso
     */
    public int activeRequests() {
        return activeRequests.get();
    }

    /** Registra el inicio de una petición. */
    public void requestStarted() {
        activeRequests.incrementAndGet();
    }

    /** Registra el fin de una petición. */
    public void requestFinished() {
        activeRequests.decrementAndGet();
    }
}

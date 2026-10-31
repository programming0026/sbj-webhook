package com.juniverse.webhook.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Firma de los webhooks con <b>HMAC-SHA256</b>. El proveedor y el receptor comparten un secreto; el proveedor firma el mensaje y
 * el receptor recalcula la firma: si coincide, el mensaje viene de quien conoce el secreto y no fue alterado.
 *
 * <p>Qué se firma: {@code timestamp + "." + cuerpo}. Incluir la marca de tiempo evita los <i>ataques de repetición</i>: quien
 * capture un mensaje válido no puede reenviarlo más tarde, porque el receptor rechaza los mensajes antiguos.</p>
 *
 * <p>La firma se calcula sobre los <b>bytes exactos</b> del cuerpo recibido. Si el receptor interpretara el JSON y lo volviera a
 * serializar, el orden de los campos o los espacios cambiarían y la firma nunca coincidiría.</p>
 */
public final class WebhookSignature {

    /** Cabecera con el identificador único del evento. */
    public static final String HEADER_ID = "X-Webhook-Id";
    /** Cabecera con el tipo de evento. */
    public static final String HEADER_EVENT = "X-Webhook-Event";
    /** Cabecera con la marca de tiempo (segundos desde 1970). */
    public static final String HEADER_TIMESTAMP = "X-Webhook-Timestamp";
    /** Cabecera con la firma: {@code v1=<hex>}; puede traer varias separadas por coma (rotación de secretos). */
    public static final String HEADER_SIGNATURE = "X-Webhook-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1=";

    /** Resultado de verificar un mensaje. */
    public enum Verification {
        /** Firma correcta y mensaje reciente. */
        VALID,
        /** La marca de tiempo es demasiado antigua o del futuro. */
        BAD_TIMESTAMP,
        /** Ninguna firma coincide con un secreto conocido. */
        BAD_SIGNATURE
    }

    private WebhookSignature() {
    }

    /**
     * Calcula la firma de un mensaje.
     *
     * @param secret secreto compartido
     * @param timestamp marca de tiempo en segundos
     * @param body cuerpo exacto del mensaje
     * @return firma en hexadecimal
     */
    public static String sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("No se pudo calcular el HMAC", e);
        }
    }

    /**
     * Valor de la cabecera de firma para uno o varios secretos (varios durante una rotación: el receptor acepta cualquiera).
     *
     * @param secrets secretos con los que firmar
     * @param timestamp marca de tiempo en segundos
     * @param body cuerpo exacto
     * @return por ejemplo {@code v1=ab12...,v1=cd34...}
     */
    public static String header(Collection<String> secrets, long timestamp, byte[] body) {
        return secrets.stream().map(secret -> VERSION + sign(secret, timestamp, body)).collect(Collectors.joining(","));
    }

    /**
     * Verifica un mensaje recibido.
     *
     * @param acceptedSecrets secretos que el receptor considera válidos
     * @param timestampHeader valor de {@code X-Webhook-Timestamp}
     * @param signatureHeader valor de {@code X-Webhook-Signature}
     * @param body cuerpo exacto recibido
     * @param nowSeconds hora actual en segundos
     * @param toleranceSeconds antigüedad máxima aceptada
     * @return el resultado de la verificación
     */
    public static Verification verify(List<String> acceptedSecrets, String timestampHeader, String signatureHeader, byte[] body,
            long nowSeconds, long toleranceSeconds) {
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.strip());
        } catch (NumberFormatException e) {
            return Verification.BAD_TIMESTAMP;
        }
        if (Math.abs(nowSeconds - timestamp) > toleranceSeconds) {
            return Verification.BAD_TIMESTAMP;
        }
        boolean match = false;
        for (String candidate : signatureHeader.split(",")) {
            String received = candidate.strip();
            if (!received.startsWith(VERSION)) {
                continue;
            }
            byte[] receivedBytes = received.substring(VERSION.length()).getBytes(StandardCharsets.UTF_8);
            for (String secret : acceptedSecrets) {
                byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8);
                // Comparación en tiempo constante: String.equals se detiene en el primer carácter distinto y, midiendo el tiempo
                // de respuesta, un atacante podría deducir la firma carácter a carácter. Se recorren TODAS las combinaciones
                // sin cortar en la primera coincidencia.
                match |= MessageDigest.isEqual(expected, receivedBytes);
            }
        }
        return match ? Verification.VALID : Verification.BAD_SIGNATURE;
    }
}

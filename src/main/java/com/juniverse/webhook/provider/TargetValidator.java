package com.juniverse.webhook.provider;

import com.juniverse.webhook.config.WebhookProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * Protección contra <b>SSRF</b> (Server-Side Request Forgery). Quien se suscribe elige la URL a la que el servidor hará peticiones;
 * un atacante podría poner {@code http://169.254.169.254/} (los metadatos de la nube, con credenciales), {@code http://localhost:6379}
 * (un Redis interno) o cualquier servicio de la red privada, y usar el servidor como "puente". Por eso solo se aceptan destinos
 * públicos y con esquema http o https.
 *
 * <p>Se valida al registrar la suscripción y otra vez al entregar (la dirección a la que apunta un nombre puede cambiar después).
 * Además el cliente HTTP no sigue redirecciones, porque una URL pública podría redirigir a una interna.</p>
 */
@Component
public class TargetValidator {

    private final boolean allowPrivate;

    /**
     * Crea el validador.
     *
     * @param properties propiedades del webhook
     */
    public TargetValidator(WebhookProperties properties) {
        this.allowPrivate = properties.allowPrivateTargets();
    }

    /**
     * Valida una URL de destino.
     *
     * @param url dirección
     * @throws IllegalArgumentException si no es una URL permitida
     */
    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La URL no es válida");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Solo se admiten URLs http o https");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("La URL debe tener un host y no puede incluir usuario y contraseña");
        }
        if (allowPrivate) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("No se pudo resolver el host " + uri.getHost());
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new IllegalArgumentException("No se permiten destinos en redes privadas ni en este equipo (" + address.getHostAddress() + ")");
            }
        }
    }

    private static boolean isInternal(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC; // fc00::/7
        boolean carrierGradeNat = bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64; // 100.64.0.0/10
        return address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress() || uniqueLocalIpv6 || carrierGradeNat;
    }
}

package com.juniverse.webhook.provider;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Espera entre reintentos con <b>retroceso exponencial</b>: 1 s, 2 s, 4 s, 8 s... hasta un máximo. Si el receptor está caído, no
 * se le bombardea con peticiones y tiene tiempo de recuperarse. Se añade un poco de <i>jitter</i> (variación aleatoria de ±20 %) para
 * que, si muchas entregas fallaron a la vez, no vuelvan a intentarse todas en el mismo instante.
 */
public final class BackoffPolicy {

    private static final double JITTER = 0.2;

    private final long baseMs;
    private final long maxMs;

    /**
     * Crea la política.
     *
     * @param baseMs espera tras el primer fallo
     * @param maxMs espera máxima
     */
    public BackoffPolicy(long baseMs, long maxMs) {
        this.baseMs = baseMs;
        this.maxMs = maxMs;
    }

    /**
     * Espera sin variación aleatoria.
     *
     * @param failedAttempts intentos fallidos hasta ahora (1 tras el primer fallo)
     * @return milisegundos: {@code base * 2^(n-1)} limitado al máximo
     */
    public long baseDelay(int failedAttempts) {
        int shift = Math.min(Math.max(failedAttempts - 1, 0), 40); // evita desbordar el long
        long delay = baseMs << shift;
        return delay <= 0 || delay > maxMs ? maxMs : delay;
    }

    /**
     * Espera con variación aleatoria.
     *
     * @param failedAttempts intentos fallidos hasta ahora
     * @return milisegundos a esperar
     */
    public long delayWithJitter(int failedAttempts) {
        long delay = baseDelay(failedAttempts);
        double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * JITTER;
        return Math.max(1, Math.round(delay * factor));
    }
}

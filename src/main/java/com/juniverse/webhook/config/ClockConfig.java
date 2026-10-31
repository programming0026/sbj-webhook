package com.juniverse.webhook.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj inyectable: las pruebas lo reemplazan para controlar el tiempo sin esperar. */
@Configuration
public class ClockConfig {

    /**
     * Reloj del sistema en UTC.
     *
     * @return reloj
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

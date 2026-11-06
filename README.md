# Webhooks con Spring Boot 4: proveedor firmado con reintentos y receptor seguro

Ejemplo con **Spring Boot 4** y **Java 21** que implementa **las dos mitades** de un webhook en una misma aplicación, para que puedas verlas funcionar juntas: quien **envía** los avisos (con firma, reintentos y registro de entregas) y quien los **recibe** (verificando firma y antigüedad, descartando duplicados). No necesita instalar nada: usa una base de datos H2 en memoria.

## ¿Qué es un webhook?

Es lo contrario de preguntar constantemente. En lugar de que tu aplicación consulte cada minuto "¿ya se pagó el pedido?" (*polling*), el sistema que lo sabe **te llama a ti** con una petición HTTP POST en cuanto ocurre. Lo usan Stripe, GitHub, Shopify, Twilio...

```text
 PROVEEDOR                                                        RECEPTOR (tu aplicación)
 pedido pagado ──► guarda el aviso ──► POST https://tu-app/webhooks
                                         X-Webhook-Id:        evt_9f3a...        (único: sirve para deduplicar)
                                         X-Webhook-Timestamp: 1767225600         (evita repeticiones)
                                         X-Webhook-Signature: v1=3b1c...         (HMAC-SHA256)
                                         {"type":"order.paid","data":{...}}
                                       ◄── 200 OK            (rápido; el trabajo pesado después)
```

## Cómo se resuelve cada problema

### Del lado del proveedor (`SubscriptionService`, `EventPublisher`, `WebhookDispatcher`)

| Problema | Solución |
|----------|----------|
| ¿Cómo sabe el receptor que el mensaje es mío? | **Firma HMAC-SHA256** de `timestamp + "." + cuerpo` con un secreto compartido (`WebhookSignature`) |
| ¿Y si el receptor está caído? | **Reintentos con retroceso exponencial** (10 s, 20 s, 40 s... hasta 1 h) con *jitter* de ±20 % para no reintentar todos a la vez (`BackoffPolicy`). Tras `max-attempts` la entrega queda `FAILED` y se puede **reenviar** manualmente |
| ¿Y si mi aplicación se cae después de crear el pedido pero antes de avisar? | Los avisos se guardan en la **misma transacción** que el pedido y los envía un proceso aparte: nunca se envía HTTP dentro de la transacción (mismo principio que el *Transactional Outbox*) |
| Mensajes duplicados | La garantía es **"al menos una vez"**: si el receptor procesó el mensaje pero su respuesta se perdió, llegará otra vez. Cada mensaje lleva un `X-Webhook-Id` único y estable entre reintentos |
| Un receptor lento bloquea a los demás | Cada entrega va en un **hilo virtual** de Java 21; el despachador las envía en paralelo |
| El proceso muere a mitad de un envío | Las entregas "en vuelo" abandonadas se recuperan solas (`stale-claim-ms`) |
| Varios despachadores | Cada entrega se **reclama** con un `UPDATE ... WHERE status = 'PENDING'` atómico: solo uno la procesa |
| El receptor ya no quiere avisos | Si responde **410 Gone** se desactiva la suscripción, sin más reintentos |
| Filtrar eventos | Cada suscripción elige tipos (`order.paid`...) o `*` |
| Cambiar el secreto sin perder mensajes | **Rotación**: durante 24 h cada mensaje lleva **dos firmas** (secreto nuevo y viejo) y el receptor acepta cualquiera |
| **SSRF**: alguien se suscribe con `http://169.254.169.254/` | `TargetValidator` bloquea localhost, redes privadas, enlace local, `100.64/10`, IPv6 local y esquemas que no sean http/https, al suscribirse **y** al entregar; el cliente HTTP **no sigue redirecciones** |
| El secreto no debe filtrarse | Solo se muestra al crear o rotar; el listado solo enseña los últimos 4 caracteres |

### Del lado del receptor (`ReceiverController`, `ReceivedEventStore`)

| Regla | Cómo |
|-------|------|
| **Verificar la firma sobre los bytes exactos** del cuerpo | El endpoint recibe `byte[]`, no un objeto: si se interpretara y volviera a serializar el JSON, la firma no coincidiría |
| **Comparación en tiempo constante** | `MessageDigest.isEqual`, recorriendo todas las combinaciones; con `String.equals` el tiempo de respuesta revelaría cuántos caracteres de la firma son correctos |
| **Rechazar mensajes antiguos** | Tolerancia de 5 minutos: quien capture un mensaje válido no puede reenviarlo después (ataque de repetición). La marca de tiempo va dentro de lo firmado, así que no se puede "rejuvenecer" |
| **Idempotencia** | `received_events` con el id del evento como **clave primaria**: aunque dos copias lleguen a la vez, la base de datos deja pasar solo una. El duplicado recibe 200 para que el proveedor deje de insistir |
| **Responder rápido** | Guarda y contesta 200; el trabajo pesado iría a una cola. Si tardas, el proveedor agota el tiempo y reintenta |
| No dar pistas al atacante | Firma mala, fecha vieja o secreto equivocado dan el mismo `401 firma inválida` |
| Límite de tamaño | Rechaza cuerpos de más de 1 MB |

## API

**Suscripciones y entregas** (`/api/webhooks`):

| Método | Ruta | Descripción |
|--------|------|-------------|
| POST | `/subscriptions` | Crea una suscripción `{"url","events":[...],"secret"?}` (el secreto se genera si no lo envías) |
| GET | `/subscriptions` | Lista (sin secretos) |
| DELETE | `/subscriptions/{id}` | Elimina la suscripción y su historial |
| POST | `/subscriptions/{id}/rotate-secret` | Rota el secreto con periodo de gracia |
| POST | `/subscriptions/{id}/ping` | Envía un evento de prueba |
| GET | `/deliveries?subscriptionId=&status=` | Historial de entregas |
| GET | `/deliveries/{id}` | Detalle con **cada intento** (código HTTP, error, duración) |
| POST | `/deliveries/{id}/redeliver` | Reenvía una entrega `FAILED` |

**Negocio** (genera eventos): `POST /api/orders` → `order.created`, `POST /api/orders/{id}/pay` → `order.paid`, `POST /api/orders/{id}/cancel` → `order.cancelled`.

**Receptor**: `POST /webhooks/orders` (el endpoint), `GET /api/receiver/events` (lo recibido). Solo para demostraciones: `POST /api/receiver/chaos {"mode":"ERROR_500"|"TIMEOUT"|"GONE_410"|"OK"}` simula un receptor con fallos, y `POST /api/receiver/secrets` cambia los secretos que acepta.

Estados de una entrega: `PENDING` → `IN_FLIGHT` → `DELIVERED` | de vuelta a `PENDING` (reintento) | `FAILED`.

## Estructura

```text
src/main/java/com/juniverse/webhook/
├── controller/       # OrderController, SubscriptionController (altas, rotación de secreto, ping, historial, reenvío), ReceiverController (endpoint seguro)
├── service/
│   ├── EventPublisher.java       # evento -> una entrega por suscriptor (en la transacción del negocio)
│   ├── WebhookDispatcher.java    # reclama, envía con hilos virtuales, reintenta, desactiva con 410
│   ├── SubscriptionService.java  # lógica de suscripciones
│   └── OrderService.java         # el "negocio" que genera los eventos
├── repository/       # SubscriptionRepository, DeliveryRepository, ReceivedEventStore (almacén idempotente)
├── model/            # Subscription, Delivery, AttemptRecord, ReceiverState (modo de fallos de demostración)
├── dto/              # CreateOrderRequest, CreateRequest, SubscriptionView, DeliveryView
├── util/
│   ├── WebhookSignature.java     # firmar y verificar (HMAC, marca de tiempo, tiempo constante)
│   ├── BackoffPolicy.java        # espera exponencial con jitter
│   └── TargetValidator.java      # protección SSRF
├── exception/        # errores Problem Details
└── config/           # propiedades, reloj inyectable
src/main/resources/schema.sql             # subscriptions, deliveries, attempts, orders, received_events
```

## Requisitos

- JDK 21 y Maven 3.9 o superior.

## Ejecutar

```bash
mvn spring-boot:run
```

El receptor de ejemplo verifica con el secreto `whsec_demo_secret_change_me_0123456789` (configurable con `WEBHOOK_RECEIVER_SECRET`). Regístrate con ese mismo valor:

```bash
# 1. Suscribirse (en un caso real el receptor copia el secreto que le entrega el proveedor)
curl -X POST http://localhost:8080/api/webhooks/subscriptions -H "Content-Type: application/json" \
     -d '{"url":"http://localhost:8080/webhooks/orders","events":["order.created","order.paid"],"secret":"whsec_demo_secret_change_me_0123456789"}'

# 2. Generar eventos
curl -X POST http://localhost:8080/api/orders -H "Content-Type: application/json" -d '{"customer":"ana","amount":120.50}'
curl -X POST http://localhost:8080/api/orders/<id>/pay

# 3. Ver qué recibió el receptor y cómo fueron las entregas
curl http://localhost:8080/api/receiver/events
curl http://localhost:8080/api/webhooks/deliveries
```

**Experimentos:**

1. `POST /api/receiver/chaos {"mode":"ERROR_500"}`, crea un pedido y mira `GET /api/webhooks/deliveries`: queda `PENDING` con `intentos` creciendo y el motivo en `lastError`. Con `{"mode":"OK"}` se entrega solo en el siguiente reintento.
2. Con `webhook.max-attempts` agotado la entrega queda `FAILED`: arréglalo y usa `POST /deliveries/{id}/redeliver`.
3. `{"mode":"GONE_410"}`: la suscripción se desactiva sola.
4. Manda a mano un POST a `/webhooks/orders` con una firma inventada: `401`.

Para probar con un servicio real expuesto a internet puedes usar <https://webhook.site> como destino (pon `webhook.allow-private-targets=false` y verás que `localhost` se rechaza).

### Configuración (`application.properties`)

| Propiedad | Descripción |
|-----------|-------------|
| `webhook.max-attempts`, `backoff-base-ms`, `backoff-max-ms` | Política de reintentos |
| `webhook.request-timeout-ms` | Cuánto espera la respuesta del receptor |
| `webhook.allow-private-targets` | **En producción debe ser `false`** (protección SSRF). Aquí es `true` porque el ejemplo se envía avisos a sí mismo |
| `webhook.tolerance-seconds` | Antigüedad máxima aceptada por el receptor |
| `webhook.rotation-grace-hours` | Cuánto tiempo se firma también con el secreto anterior |

## Pruebas

```bash
mvn test
```

61 pruebas:

- **Firma (20):** coincide con un HMAC calculado de forma independiente y con el vector oficial de la RFC 4231; cuerpo alterado, secreto equivocado, mensaje antiguo, del futuro y con la marca de tiempo cambiada; cabeceras mal formadas; rotación con dos firmas.
- **Reintentos y SSRF (23):** la espera se duplica hasta el máximo sin desbordarse y el *jitter* se mantiene en ±20 %; 11 destinos internos bloqueados (loopback, redes privadas, metadatos de la nube, IPv6, multicast...), esquemas peligrosos (`file:`, `ftp:`...) y credenciales en la URL.
- **Flujo completo por HTTP real (17):** entrega firmada, filtro de eventos, varios suscriptores con el mismo id de evento, reintentos con historial, `FAILED` + reenvío, tiempo agotado, **410**, entregas abandonadas que se recuperan, 20 eventos entregados exactamente una vez, rotación de secreto, el receptor rechazando firma falsa/antigua/alterada, **idempotencia**, validaciones de la API, y que si la operación de negocio falla no se crea ningún aviso.
- **SSRF en la API (1):** con la protección activa, `localhost`, `192.168.x.x` y `169.254.169.254` dan 400.

Una de las pruebas reproduce un caso que ocurre en producción: un **receptor lento** que termina de procesar el mensaje *después* de que el proveedor se rindiera por tiempo agotado. El proveedor reintenta y el mismo evento llega dos veces; el receptor lo registra una sola.

## Para producción

- Secretos: guárdalos **cifrados** (aquí van en claro porque hace falta el valor original para firmar) y entrégalos al suscriptor una sola vez.
- Usa una base de datos real y varias instancias del despachador; la reclamación atómica ya lo permite (con PostgreSQL puedes usar `FOR UPDATE SKIP LOCKED`).
- Receptor: procesa el evento en segundo plano (cola) y responde 200 de inmediato; guarda los ids con un TTL para que la tabla no crezca sin límite.
- Limita la cantidad de suscripciones por cuenta y desactiva automáticamente las que fallan durante días.
- HTTPS obligatorio, y considera firmas asimétricas (Ed25519) si no quieres compartir un secreto.
- Ofrece a los clientes un panel con el historial de entregas y un botón de reenvío: es lo primero que piden al depurar.

Fat jar ejecutable: `mvn clean package` y `java -jar target/sbj-webhook-0.0.1-SNAPSHOT.jar`.

## Licencia

Código de ejemplo de uso libre con fines educativos.

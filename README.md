# Banco XYZ — PBY2203 Experiencia 3
## Semana 5 (BFF), Semana 6 (Spring Cloud) y Semana 7 (eventos JMS)

Este repositorio continúa el Banco XYZ. La semana 5 expone un BFF por canal. Las semanas 6 y 7 agregan un ecosistema Spring Cloud local y una saga de retiros por JMS. La configuración vive en `config-repo/` y el broker ActiveMQ arranca embebido en `ms-transacciones`.

## Objetivo

Implementar el patrón **Backend for Frontend (BFF)** para el Banco XYZ, creando backends personalizados para tres canales (Web, Móvil y Cajeros), con autenticación/autorización por canal y seguridad HTTPS.

Los datos se obtienen desde el legado [bank_legacy_data](https://github.com/KariVillagran/bank_legacy_data), previamente migrados a H2 por el proyecto batch.

## Estructura de la entrega

```
.
├── README.md
├── pom.xml                   # Reactor Spring Cloud (semanas 6 y 7)
├── config-repo/              # YAML que publica el Config Server
├── config-server/            # Puerto 8888
├── eureka-server/            # Puerto 8761
├── api-gateway/              # Puerto 8080, HTTP Basic y rutas
├── ms-transacciones/         # Puerto 8081, orquestador JMS
├── ms-intereses/             # Puerto 8082
├── ms-cuentas/               # Puerto 8083, participante de la saga
├── evidencias/
├── xyz/                      # Spring Batch: CSV -> H2
├── xyz-backend/              # Backend HTTP dueño de H2, puerto 8090
└── xyz-bff/                  # BFF HTTPS de la semana 5. Llama al backend con RestClient
```

## Separación de responsabilidades

| Proyecto | Rol |
|----------|-----|
| `xyz` | Solo **Spring Batch**: migra CSV → H2. No expone APIs. |
| `xyz-backend` | Único servicio que abre H2. Expone los datos por HTTP. |
| `xyz-bff` | Canales Web, Móvil y ATM. No usa `JdbcTemplate`. Agrega las respuestas con `RestClient`. |

La base H2 en archivo (`xyz/data/bankxyz`) la escribe el batch y la lee solo `xyz-backend`.

---

## Proyecto `xyz` (Batch)

### Qué hace
- Lee CSV de transacciones, intereses y estados de cuenta.
- Valida/normaliza con ItemProcessors.
- Aplica Skip/Retry/BackOff.
- Persiste en H2 para que `xyz-backend` lo exponga por HTTP.

### Cómo ejecutar
```bash
cd xyz
.\mvnw.cmd -DskipTests spring-boot:run
```
La app es non-web y termina al finalizar los jobs.

### Estructura relevante
```
xyz/src/main/java/com/banco/xyz/
├── XyzApplication.java
├── batch/          # jobs, processors, policies, listeners
└── domain/
```

---

## Proyecto `xyz-bff` (Semana 5)

### Estrategia BFF elegida
Se eligió **backends/endpoints personalizados por tipo de cliente**:

- Equipo pequeño → un proyecto BFF, con canales separados.
- Cada frontend tiene necesidades distintas (payload completo vs liviano vs operaciones críticas).
- Facilita mantenimiento y extensión sin tres repositorios independientes.

### Canales

| Canal | Prefijo | Usuario | Password | Rol | Características |
|-------|---------|---------|----------|-----|-----------------|
| Web | `/bff/web/**` | `web` | `web123` | `CHANNEL_WEB` | Respuestas completas |
| Móvil | `/bff/mobile/**` | `mobile` | `mobile123` | `CHANNEL_MOBILE` | Respuestas livianas |
| ATM | `/bff/atm/**` | `atm` | `atm123` | `CHANNEL_ATM` | Saldo/retiro + PIN por cuenta |

### Endpoints

**Web**
- `GET /bff/web/dashboard`
- `GET /bff/web/transacciones`
- `GET /bff/web/intereses`
- `GET /bff/web/cuentas/{id}/estado-anual`

**Móvil**
- `GET /bff/mobile/home?limit=5`
- `GET /bff/mobile/cuentas` → solo `id`, `saldo`, `tipo`
- `GET /bff/mobile/cuentas/{id}/resumen`

**ATM**
- `GET /bff/atm/ping`
- `GET /bff/atm/saldo/{id}` + header `X-ATM-PIN`
- `POST /bff/atm/retiro` + header `X-ATM-PIN`

`{id}` = PK técnica de `cuentas_con_interes`.

### Seguridad
- **HTTPS** en puerto `8443` (keystore PKCS12 en `xyz-bff/src/main/resources/keystore/`).
- **Autenticación** HTTP Basic por canal.
- **Autorización** por rol (un canal no puede llamar a otro → 403).
- **PIN ATM** validado **por cuenta** (no PIN fijo global).  
  Regla de prueba: `PIN = últimos 4 dígitos de cuentaId` (ej. `cuentaId=106` → `0106`).

### DTOs tipados
Las respuestas usan records tipados por canal (no `Map` genéricos).

### Estructura relevante
```
xyz-bff/src/main/java/com/banco/xyz/bff/
├── XyzBffApplication.java
├── web/WebBffController.java
├── mobile/MobileBffController.java
├── atm/AtmBffController.java
├── security/BffSecurityConfig.java
├── security/AtmPinService.java
└── service/BankDataQueryService.java   # RestClient hacia xyz-backend
```

Los tres canales llaman a `xyz-backend` con `RestClient`. El dashboard web no lee la base: pide `GET /api/resumen` y `GET /api/cuentas` y arma la respuesta con esas dos integraciones. El estado de cuenta anual pide la cuenta y, en otra llamada, `GET /api/cuentas/{id}/movimientos`. El cajero consulta el saldo y envía el retiro con `POST /api/retiros`.

Eso queda demostrado en `evidencias/evidencia_bff.txt`: el backend responde en el puerto 8090, el canal web agrega resumen y cuentas, y el log del BFF registra cada `Integracion HTTP`.

### Cómo ejecutar
```bash
# 1) Cargar datos con batch
cd xyz
.\mvnw.cmd -DskipTests spring-boot:run

# 2) Backend dueño de H2 (desde la raíz del repositorio)
cd ..
.\mvnw.cmd -f xyz-backend\pom.xml -DskipTests spring-boot:run

# 3) BFF
.\mvnw.cmd -f xyz-bff\pom.xml -DskipTests spring-boot:run
```
Backend: `http://localhost:8090`. BFF: `https://localhost:8443`.

### Ejemplos curl
```bash
# Web
curl -k -u web:web123 https://localhost:8443/bff/web/dashboard

# Mobile
curl -k -u mobile:mobile123 "https://localhost:8443/bff/mobile/home?limit=3"

# ATM (id=1, cuentaId=106 → PIN 0106)
curl -k -u atm:atm123 -H "X-ATM-PIN: 0106" https://localhost:8443/bff/atm/saldo/1

# Cross-channel (debe responder 403)
curl -k -u web:web123 https://localhost:8443/bff/mobile/home
```
`-k` se usa porque el certificado es autofirmado (entorno local/académico).

---

## Evidencias

Carpeta `evidencias/`:

| Archivo | Contenido |
|---------|-----------|
| `evidencia_bff.txt` | H2 solo en `xyz-backend`. Canales por RestClient, 401, 403 y log de integración HTTP |
| `evidencia_batch_resumen.txt` | Resumen del batch como soporte de datos |
| `evidencia_s6_config_server.txt` | Config Server entregando la config de un microservicio |
| `evidencia_s6_eureka.txt` | Tres microservicios registrados |
| `evidencia_s6_auth.txt` | 401 sin credenciales, 200 con rol y 403 por autorización |
| `evidencia_s6_resilience.txt` | Circuit breaker OPEN y respuesta FALLBACK |
| `evidencia_s7_saga_jms.txt` | Retiro confirmado, duplicado ignorado y compensación |
| `evidencia_s8_oauth.txt` | Token OAuth2, 401 sin token, 200 con scope de lectura y 403 sin permiso de retiro |
| `evidencia_s8_docker.txt` | Imágenes y contenedores levantados con Docker Compose |
| `evidencia_s8_resiliencia_jms.txt` | Retiro, compensación y circuit breaker dentro de Docker |

---

## Requisitos

- Java 17+
- Maven Wrapper en la raíz (`mvnw.cmd`) y en `xyz` / `xyz-bff`
- Docker Desktop, para la ejecución de la semana 8

---

## Semana 8 — OAuth2 y Docker

La seguridad de las APIs pasa de HTTP Basic a **OAuth 2.0 client credentials**. `auth-server` emite un JWT y el gateway y los microservicios lo validan. No hace falta AWS: todo corre en Docker local.

Clientes:

| Cliente | Secret | Scopes |
|---------|--------|--------|
| `web` | `web123` | `cuentas.read` |
| `atm` | `atm123` | `cuentas.read`, `retiros.write` |
| `servicio` | `servicio123` | `interno` (llamadas entre microservicios) |

### Cómo ejecutar

```powershell
.\mvnw.cmd -DskipTests package
.\xyz\mvnw.cmd -DskipTests -f xyz\pom.xml package
docker compose up --build
```

Compose levanta H2 por TCP, corre el batch una vez, y después Config Server, Eureka, el Authorization Server, los tres microservicios y el gateway.

### Token y llamadas

```powershell
# Token de lectura
curl.exe -s -u web:web123 -d grant_type=client_credentials http://localhost:9000/oauth2/token

# 401 sin token
curl.exe -s -i http://localhost:8080/transacciones/origen-config

# 200 con el token de web
curl.exe -s -H "Authorization: Bearer TOKEN" http://localhost:8080/transacciones/origen-config

# 403: web no puede retirar
curl.exe -s -i -H "Authorization: Bearer TOKEN_WEB" -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10}" http://localhost:8080/transacciones/retiros
```

El retiro, el duplicado y la compensación se hacen igual que en la semana 7, usando el token de `atm`.

---

## Semanas 6 y 7 — microservicios, seguridad y eventos

### Arquitectura elegida

Saga por **orquestación** con **JMS (ActiveMQ embebido)**. El retiro cruza dos servicios: `ms-transacciones` coordina y `ms-cuentas` aplica el débito. JMS encaja en ese flujo punto a punto y no requiere Docker ni un cluster de Kafka. Cada mensaje lleva un `eventId`. Si el mismo débito llega dos veces, `ms-cuentas` lo ignora. Si el débito se aplicó y el orquestador simula un fallo posterior, publica una compensación.

```mermaid
flowchart LR
  cliente[Cliente] --> gateway[ApiGateway]
  gateway --> tx[msTransacciones]
  gateway --> intereses[msIntereses]
  gateway --> cuentas[msCuentas]
  config[ConfigServer] --> tx
  config --> intereses
  config --> cuentas
  tx --> eureka[Eureka]
  intereses --> eureka
  cuentas --> eureka
  tx -->|cuentas.debitar| broker[ActiveMQ]
  broker --> cuentas
  cuentas -->|cuentas.debitar.respuesta| broker
  broker --> tx
  tx -->|cuentas.compensar| broker
  broker --> cuentas
```

Colas y mensajes:

| Cola | Quién publica | Quién consume | Mensaje |
|------|----------------|---------------|---------|
| `cuentas.debitar` | ms-transacciones | ms-cuentas | `DEBIT;eventId;cuentaId;monto;` |
| `cuentas.debitar.respuesta` | ms-cuentas | ms-transacciones | `RESULT;eventId;0;saldo;OK` o `REJECT:...` o `COMPENSATED` |
| `cuentas.compensar` | ms-transacciones | ms-cuentas | `COMPENSATE;eventId;cuentaId;monto;` |

`cuentaId` es la PK técnica de `cuentas_con_interes`, la misma que usa el BFF.

### Cómo ejecutar

Desde la raíz del repositorio, después de compilar con `.\mvnw.cmd -DskipTests package`:

```powershell
# 1) Cargar H2
cd xyz
.\mvnw.cmd -DskipTests spring-boot:run
cd ..

# 2) Plataforma y microservicios, en este orden
java -jar config-server\target\config-server-0.0.1-SNAPSHOT.jar
java -jar eureka-server\target\eureka-server-0.0.1-SNAPSHOT.jar
java -jar ms-transacciones\target\ms-transacciones-0.0.1-SNAPSHOT.jar
java -jar ms-intereses\target\ms-intereses-0.0.1-SNAPSHOT.jar
java -jar ms-cuentas\target\ms-cuentas-0.0.1-SNAPSHOT.jar
java -jar api-gateway\target\api-gateway-0.0.1-SNAPSHOT.jar
```

Espera a que cada proceso imprima que arrancó antes de lanzar el siguiente. `ms-transacciones` abre el broker en `tcp://127.0.0.1:61616`. Los tres microservicios leen su puerto, la base H2 y los umbrales de Resilience4j desde el Config Server.

### Usuarios OAuth2

Los canales ya no entran con HTTP Basic. Piden un token a `auth-server` (puerto 9000), como se describe en la sección de la semana 8. `web` solo lee. `atm` también puede retirar.

### Ejemplos

```powershell
# Config centralizada
curl.exe http://localhost:8888/ms-transacciones/default

# Eureka
curl.exe http://localhost:8761/eureka/apps

# 401, 200 y 403
curl.exe -i http://localhost:8080/transacciones
curl.exe -i -u web:web123 http://localhost:8080/transacciones/origen-config
curl.exe -i -u web:web123 -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10}" http://localhost:8080/transacciones/retiros

# Retiro confirmado (ATM). cuentaId = id de /intereses/cuentas
curl.exe -u atm:atm123 -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10}" http://localhost:8080/transacciones/retiros

# Duplicado ignorado y compensación
curl.exe -u atm:atm123 -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10,\"simularDuplicado\":true}" http://localhost:8080/transacciones/retiros
curl.exe -u atm:atm123 -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10,\"simularFallo\":true}" http://localhost:8080/transacciones/retiros

# Circuit breaker: detener ms-cuentas y consultar
curl.exe -u web:web123 http://localhost:8080/transacciones/salud-dependencia
```

El POST del retiro responde `202` con estado `PENDIENTE`. Un GET posterior a `/transacciones/retiros/{id}` muestra `CONFIRMADO`, `RECHAZADO` o `COMPENSADO`.

Para ver la cola repartir carga, se puede levantar una segunda instancia de `ms-cuentas` en otro puerto (`java -jar ... --server.port=8084`). Ambas consumen `cuentas.debitar`. La evidencia está en `evidencias/evidencia_s7_saga_jms.txt`.

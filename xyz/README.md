# Banco XYZ — Backend for Frontend (BFF) + Batch

Proyecto **Exp2 / Semana 4 (PBY2203)** — *Analizando el patrón arquitectónico Backend for Frontend (BFF)*.

Continúa la modernización del Banco XYZ: los jobs Spring Batch migran `bank_legacy_data` a H2 y, sobre esos datos, se exponen **tres backends especializados** (Web, Móvil y Cajeros).

## Objetivo

1. Aplicar el patrón **BFF** para personalizar APIs por canal (web / móvil / ATM).
2. Gestionar **autenticación y autorización** distintas por canal.
3. Mantener la resiliencia batch (validaciones ItemProcessor + Skip/Retry) de la entrega anterior.

## Estrategia BFF elegida

Se eligió la estrategia de la guía:

> **Crear backends independientes por cada tipo de cliente** + **endpoints personalizados**

En un mismo repositorio Spring Boot (continuidad del proyecto), cada canal tiene su propio paquete BFF, sus DTOs/respuestas y su rol de seguridad:

| Canal | Paquete | Prefijo API | Usuario Basic | Rol | Respuesta |
|-------|---------|-------------|---------------|-----|-----------|
| Web | `bff.web` | `/bff/web/**` | `web` / `web123` | `CHANNEL_WEB` | Completa (métricas, anomalías, auditoría) |
| Móvil | `bff.mobile` | `/bff/mobile/**` | `mobile` / `mobile123` | `CHANNEL_MOBILE` | Liviana (pocos campos) |
| Cajero | `bff.atm` | `/bff/atm/**` | `atm` / `atm123` | `CHANNEL_ATM` | Crítica (saldo/retiro + PIN `X-ATM-PIN: 1234`) |

El núcleo compartido (`core.service.BankDataService`) lee la BD cargada por Batch; **cada BFF adapta** qué expone (Adapter/Strategy de la guía).

## Requisitos

- Java 17+
- Maven Wrapper (`mvnw` / `mvnw.cmd`)

## Estructura del código

```
src/main/java/com/banco/xyz/
├── XyzApplication.java
├── batch/                 # Jobs Spring Batch (semanas 1–3)
│   ├── processor/         # Validaciones ItemProcessor
│   ├── policy/            # SkipPolicy + RetryPolicy + Decider
│   └── ...
├── bff/
│   ├── web/WebBffController.java
│   ├── mobile/MobileBffController.java
│   ├── atm/AtmBffController.java
│   └── security/BffSecurityConfig.java
├── core/service/BankDataService.java
└── domain/
```

## Endpoints BFF

### Web (payload completo)
- `GET /bff/web/dashboard`
- `GET /bff/web/transacciones`
- `GET /bff/web/intereses`
- `GET /bff/web/cuentas/{id}/estado-anual`

### Móvil (payload liviano)
- `GET /bff/mobile/home?limit=5`
- `GET /bff/mobile/cuentas` → solo `id`, `saldo`, `tipo`
- `GET /bff/mobile/cuentas/{id}/resumen`

### ATM (operaciones críticas)
- `GET /bff/atm/ping`
- `GET /bff/atm/saldo/{id}` + header `X-ATM-PIN: 1234`
- `POST /bff/atm/retiro` body `{"id":1,"monto":10}` + `X-ATM-PIN: 1234`

`{id}` es la PK técnica de `cuentas_con_interes` (única), no el `cuenta_id` legacy (puede repetirse).

## Cómo ejecutar

```bash
cd xyz
.\mvnw.cmd -DskipTests spring-boot:run
```

Al arrancar:

1. Ejecuta los 3 jobs batch (carga H2).
2. Deja el servidor HTTP en `http://localhost:8080` para los BFF.

Propiedades útiles (`application.properties`):

| Propiedad | Default | Uso |
|-----------|---------|-----|
| `bank.batch.run-on-startup` | `true` | Corre migración al iniciar |
| `bank.batch.exit-after-jobs` | `false` | `false` = deja viva la API BFF |
| `bank.batch.compare-params` | `false` | Comparación gridSize (semana 3) |

### Ejemplos curl

```bash
# Web
curl -u web:web123 http://localhost:8080/bff/web/dashboard

# Mobile
curl -u mobile:mobile123 http://localhost:8080/bff/mobile/home?limit=3

# ATM
curl -u atm:atm123 -H "X-ATM-PIN: 1234" http://localhost:8080/bff/atm/saldo/1
curl -u atm:atm123 -H "X-ATM-PIN: 1234" -H "Content-Type: application/json" ^
  -d "{\"id\":1,\"monto\":10}" http://localhost:8080/bff/atm/retiro
```

Un usuario de un canal **no** puede llamar otro (HTTP 403).

### Tests (validación + tolerancia a fallos)

```bash
.\mvnw.cmd test
```

Incluye `ItemProcessorValidationTest` y `FaultTolerancePolicyTest`.

## Corrección semana anterior (pauta S3)

### 1) Transformaciones y validaciones en ItemProcessor

Casos cubiertos y omitidos vía `InvalidDataException` + `FileVerificationSkipper`:

| Proceso | Errores omitidos | Transformaciones |
|---------|------------------|------------------|
| Transacciones | incompletos, id ≤ 0, tipo `invalid`/`desconocido`, duplicados | normaliza tipo; **marca anomalía** si monto ≤ 0 (no omite) |
| Intereses | incompletos, nombre `Unknown`, edad ∉ 18–100, saldo ≤ 0, tipo `-1`, duplicados | calcula tasa/interés/saldo; filtra `hipoteca`/`unknown` (return null) |
| Estados anuales | incompletos, tipo `pago`, monto ≤ 0 | `depósito`→`deposito`; descripción vacía; clasifica INGRESO/EGRESO |

### 2) Políticas de reintento y tolerancia a fallos

| Política | Escenarios |
|----------|------------|
| **SkipPolicy** | `InvalidDataException`, `FlatFileParseException`, `DateTimeParseException`, `NumberFormatException`, `IllegalArgumentException` (límite 2000) |
| **RetryPolicy** | Fallos transitorios de BD: `TransientDataAccessException`, `CannotGetJdbcConnectionException`, `QueryTimeoutException`, deadlock/lock, optimistic locking, `RecoverableDataAccessException`, `DataAccessResourceFailureException` (3 reintentos) |
| **BackOff** | Exponencial 80ms ×2, tope 800ms |
| **Decider** | Reejecuta PartitionStep si el manager falla; `COMPLETED_WITH_SKIPS` si hubo omisiones |

## Evidencia

| Archivo | Contenido |
|---------|-----------|
| `evidencia_bff.txt` | Llamadas a las 3 APIs BFF + 403 cross-channel |
| `output/errores.csv` | Omisiones batch |
| `evidencia_ejecucion.txt` | (opcional) salida batch previa |

## Propuesta técnica (resumen)

- **BFF**: tres backends lógicos independientes (`web` / `mobile` / `atm`) sobre un core de datos.
- **Seguridad**: HTTP Basic + roles por canal; ATM exige PIN adicional.
- **Datos**: Spring Batch → H2 → consultas JDBC del core → proyección distinta por BFF.
- **Resiliencia batch**: Skip + Retry + BackOff + Decider + listeners de error/perf.

# Banco XYZ — Exp2 Semana 5 (PBY2203)
## Implementando el patrón arquitectónico Backend for Frontend (BFF)

## Objetivo

Implementar el patrón **Backend for Frontend (BFF)** para el Banco XYZ, creando backends personalizados para tres canales (Web, Móvil y Cajeros), con autenticación/autorización por canal y seguridad HTTPS.

Los datos se obtienen desde el legado [bank_legacy_data](https://github.com/KariVillagran/bank_legacy_data), previamente migrados a H2 por el proyecto batch.

## Estructura de la entrega

```
.
├── README.md                 # Documentación única de la entrega
├── evidencias/               # Evidencias de ejecución
│   ├── evidencia_bff.txt
│   └── evidencia_batch_resumen.txt
├── xyz/                      # Proyecto Spring Batch (solo carga de datos)
└── xyz-bff/                  # Proyecto BFF (APIs Web / Móvil / ATM)
```

## Separación de responsabilidades

| Proyecto | Rol |
|----------|-----|
| `xyz` | Solo **Spring Batch**: migra CSV → H2. No expone APIs BFF. |
| `xyz-bff` | Solo **BFF**: APIs HTTPS por canal. No ejecuta jobs batch. |

Ambos comparten la misma base H2 en archivo: `xyz/data/bankxyz`.

---

## Proyecto `xyz` (Batch)

### Qué hace
- Lee CSV de transacciones, intereses y estados de cuenta.
- Valida/normaliza con ItemProcessors.
- Aplica Skip/Retry/BackOff.
- Persiste en H2 para que el BFF consulte.

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
└── service/BankDataQueryService.java
```

### Cómo ejecutar
```bash
# 1) Primero cargar datos con batch
cd xyz
.\mvnw.cmd -DskipTests spring-boot:run

# 2) Luego levantar BFF
cd ../xyz-bff
.\mvnw.cmd -DskipTests spring-boot:run
```
Base URL: `https://localhost:8443`

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
| `evidencia_bff.txt` | Ejecución HTTPS de Web/Móvil/ATM, PIN inválido (401), cross-channel (403) |
| `evidencia_batch_resumen.txt` | Resumen del batch como soporte de datos |

---

## Requisitos

- Java 17+
- Maven Wrapper incluido en ambos proyectos (`mvnw` / `mvnw.cmd`)

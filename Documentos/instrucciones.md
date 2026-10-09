# Instrucciones de ejecución y prueba

Estas instrucciones cubren cada componente del Banco XYZ. Requieren Java 17, Maven Wrapper y, para el ecosistema de microservicios, Docker Desktop.

Los comandos se ejecutan en la raíz del proyecto (la carpeta de `docker-compose.yml`), no dentro de `Documentos`. Las evidencias están en `../evidencias` respecto de este archivo.

## 1. Batch (`xyz`)

Carga los CSV legado a H2. La aplicación no es web: corre los tres jobs y termina.

```powershell
cd xyz
.\mvnw.cmd -DskipTests spring-boot:run
```

Jobs:

1. `reporteTransaccionesDiariasJob` — anomalías y resumen diario.
2. `calculoInteresesMensualesJob` — intereses de ahorro y préstamo.
3. `estadosCuentaAnualesJob` — movimientos para auditoría.

Skip, retry y backoff están en los processors. El partitioner reparte el CSV entre workers. La base queda en `xyz/data/bankxyz`. Evidencia: `../evidencias/evidencia_batch_resumen.txt`.

## 2. Backend de datos (`xyz-backend`)

Único proceso que abre H2 para las APIs de canal. Puerto 8090.

```powershell
.\mvnw.cmd -f xyz-backend\pom.xml -DskipTests spring-boot:run
```

Pruebas:

```powershell
curl.exe -s http://localhost:8090/api/resumen
curl.exe -s http://localhost:8090/api/cuentas
```

## 3. BFF (`xyz-bff`)

HTTPS en 8443. Los tres canales llaman al backend con `RestClient`. No usan `JdbcTemplate`.

```powershell
.\mvnw.cmd -f xyz-bff\pom.xml -DskipTests spring-boot:run
```

| Canal | Usuario | Password | Qué probar |
|-------|---------|----------|------------|
| Web | `web` | `web123` | `GET /bff/web/dashboard` |
| Móvil | `mobile` | `mobile123` | `GET /bff/mobile/home?limit=3` |
| ATM | `atm` | `atm123` | `GET /bff/atm/saldo/1` con `X-ATM-PIN: 0106` |

```powershell
curl.exe -k -u web:web123 https://localhost:8443/bff/web/dashboard
curl.exe -k -u mobile:mobile123 "https://localhost:8443/bff/mobile/home?limit=3"
curl.exe -k -u atm:atm123 -H "X-ATM-PIN: 0106" https://localhost:8443/bff/atm/saldo/1
curl.exe -k -u atm:atm123 -H "X-ATM-PIN: 1234" https://localhost:8443/bff/atm/saldo/1
curl.exe -k -u web:web123 https://localhost:8443/bff/mobile/home
```

PIN inválido: 401. Canal cruzado: 403. Evidencia: `../evidencias/evidencia_bff.txt`.

## 4. Microservicios con Docker Compose

Desde la raíz, con los jars ya compilados:

```powershell
.\mvnw.cmd -DskipTests package
.\xyz\mvnw.cmd -DskipTests -f xyz\pom.xml package
docker compose up --build
```

Orden: H2, batch (una vez), Config Server, Eureka, Authorization Server, los tres microservicios y el gateway.

| Componente | Puerto |
|------------|--------|
| Config Server | 8888 |
| Eureka | 8761 |
| Auth Server | 9000 |
| Gateway | 8080 |
| ms-transacciones | 8081 |
| ms-intereses | 8082 |
| ms-cuentas | 8083 (interno; al escalar no se publica un solo puerto host) |

### Token OAuth2

```powershell
curl.exe -s -u web:web123 -d grant_type=client_credentials http://localhost:9000/oauth2/token
curl.exe -s -u atm:atm123 -d grant_type=client_credentials http://localhost:9000/oauth2/token
```

Clientes: `web` lee (`cuentas.read`). `atm` lee y retira (`retiros.write`).

### 401, 200 y 403

```powershell
curl.exe -s -i http://localhost:8080/transacciones/origen-config
curl.exe -s -H "Authorization: Bearer TOKEN_WEB" http://localhost:8080/transacciones/origen-config
curl.exe -s -i -H "Authorization: Bearer TOKEN_WEB" -H "Content-Type: application/json" --data-binary "@retiro.json" http://localhost:8080/transacciones/retiros
```

Sin token: 401. Lectura con `web`: 200. Retiro con `web`: 403. Evidencia: `../evidencias/evidencia_s8_oauth.txt`.

### Saga JMS (retiro)

Con el token de `atm`:

```powershell
# Confirmado
curl.exe -s -H "Authorization: Bearer TOKEN_ATM" -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10}" http://localhost:8080/transacciones/retiros
curl.exe -s -H "Authorization: Bearer TOKEN_ATM" http://localhost:8080/transacciones/retiros/1

# Duplicado
curl.exe -s -H "Authorization: Bearer TOKEN_ATM" -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10,\"simularDuplicado\":true}" http://localhost:8080/transacciones/retiros

# Compensacion
curl.exe -s -H "Authorization: Bearer TOKEN_ATM" -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10,\"simularFallo\":true}" http://localhost:8080/transacciones/retiros
```

Estados: `CONFIRMADO`, duplicado ignorado, `COMPENSADO`. Evidencias: `../evidencias/evidencia_s7_saga_jms.txt` y `../evidencias/evidencia_s8_resiliencia_jms.txt`.

### Circuit breaker

```powershell
curl.exe -s -H "Authorization: Bearer TOKEN_WEB" "http://localhost:8080/intereses/salud-dependencia?forzarFallo=true"
curl.exe -s -H "Authorization: Bearer TOKEN_WEB" "http://localhost:8080/intereses/salud-dependencia?forzarFallo=true"
```

La segunda llamada debe mostrar `FALLBACK` y circuito `OPEN`.

### Eureka y Config Server

```powershell
curl.exe -s http://localhost:8888/ms-transacciones/default
curl.exe -s http://localhost:8761/eureka/apps
```

## 5. Escalado horizontal

Con el stack arriba:

```powershell
docker compose up -d --scale ms-cuentas=2 --no-recreate
docker compose ps
curl.exe -s http://localhost:8761/eureka/apps/MS-CUENTAS
```

Deben aparecer dos instancias de `MS-CUENTAS`. La cola `cuentas.debitar` reparte mensajes entre ambas. Evidencia: `../evidencias/evidencia_s9_escalado.txt`.

El detalle de nube (local, broker en EC2 y despliegue completo) está en [despliegue.md](despliegue.md).

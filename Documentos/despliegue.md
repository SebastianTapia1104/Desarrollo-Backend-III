# Despliegue del Banco XYZ

Hay tres formas de correr el sistema. La primera es la que ya está probada en este computador. La segunda es el paso que separa el broker, como se pidió en la corrección de la semana 8: ActiveMQ en una EC2 y los microservicios todavía en local. La tercera es el despliegue completo en la nube.

Los comandos de Maven y Compose se ejecutan desde la raíz del proyecto, no desde esta carpeta.

## 1. Entorno local

Requisitos: Docker Desktop, Java 17 y el Maven Wrapper del repositorio.

```powershell
.\mvnw.cmd -DskipTests package
.\xyz\mvnw.cmd -DskipTests -f xyz\pom.xml package
docker compose up --build -d
```

Compose hace esto, en orden:

1. Levanta H2 por TCP en el puerto 9092. La base en archivo no se comparte entre contenedores, por eso el servidor es TCP.
2. Corre el batch una vez contra `jdbc:h2:tcp://h2:9092/bankxyz` y el contenedor termina con código 0.
3. Levanta Config Server (8888), Eureka (8761) y el Authorization Server (9000).
4. Arranca `ms-transacciones` (8081 y el broker embebido en 61616), `ms-intereses` (8082), `ms-cuentas` y el gateway (8080).

El perfil `docker` cambia `localhost` por el nombre del servicio (`auth-server`, `eureka-server`, `h2`).

Comprobar:

```powershell
docker compose ps
curl.exe -s http://localhost:8761/eureka/apps
curl.exe -s -u web:web123 -d grant_type=client_credentials http://localhost:9000/oauth2/token
```

Escalado horizontal. `ms-cuentas` no publica un puerto fijo del host, así dos réplicas no chocan:

```powershell
docker compose up -d --scale ms-cuentas=2 --no-recreate
curl.exe -s http://localhost:8761/eureka/apps/MS-CUENTAS
```

Deben verse dos `instanceId`. La cola `cuentas.debitar` reparte mensajes entre ambas. Evidencias: `../evidencias/evidencia_s8_docker.txt` y `../evidencias/evidencia_s9_escalado.txt`.

`ms-transacciones` se deja en una réplica en este escenario porque el broker vive dentro de ese contenedor. El escenario 2 saca el broker de ahí.

Para detener:

```powershell
docker compose down
```

## 2. Primer salto: broker ActiveMQ en una EC2

Este escenario responde a la corrección de la semana 8: la mensajería no queda embebida en `ms-transacciones`. El broker corre solo, en una instancia, y los microservicios del notebook se conectan a su IP o DNS. El resto del sistema sigue en local. No hace falta mover todavía el gateway ni la base.

### 2.1 Instancia

1. En AWS, crear una EC2 Ubuntu 22.04. Un tipo `t3.small` alcanza para el broker de esta carga.
2. Security group: permitir SSH (22) desde tu IP y el puerto 61616 (OpenWire de ActiveMQ) desde tu IP. No dejar 61616 abierto a `0.0.0.0/0`.
3. Asociar una IP elástica o anotar el DNS público. En este documento se llama `IP_EC2`.
4. Entrar por SSH e instalar Docker:

```bash
sudo apt-get update
sudo apt-get install -y docker.io
sudo systemctl enable --now docker
```

### 2.2 Contenedor del broker

En la EC2, no en el notebook:

```bash
sudo docker run -d --name activemq --restart unless-stopped \
  -p 61616:61616 -p 8161:8161 \
  apache/activemq-classic:5.18.6
sudo docker ps
```

`docker ps` debe mostrar `activemq` en ejecución. La consola web queda en el puerto 8161; si se quiere ver desde fuera, se abre ese puerto solo hacia tu IP. Usuario por defecto de la imagen clásica: `admin` / `admin`. Conviene cambiarlo antes de dejar la instancia encendida.

Qué guardar como evidencia de esta parte:

- La pantalla de la instancia EC2 (nombre, IP o DNS, estado running).
- La salida de `docker ps` dentro de la EC2, con el contenedor `activemq`.

### 2.3 Microservicios locales apuntando a la EC2

Hay que apagar el broker embebido de `ms-transacciones` para que no escuche también en 61616, y apuntar productor y consumidor a la EC2.

En `config-repo/ms-transacciones-docker.yml` y `config-repo/ms-cuentas-docker.yml`, la URL del broker pasa de `tcp://ms-transacciones:61616` a:

```yaml
spring:
  activemq:
    broker-url: tcp://IP_EC2:61616
```

Si los microservicios corren fuera de Compose, la misma URL se pone en `config-repo/ms-transacciones.yml` y `config-repo/ms-cuentas.yml` en lugar de `tcp://127.0.0.1:61616`.

Luego se reinician `ms-transacciones` y `ms-cuentas` (o `docker compose up -d` si siguen en Compose, con el puerto 61616 del servicio de transacciones ya no publicado). Un retiro de prueba:

```powershell
curl.exe -s -u atm:atm123 -d grant_type=client_credentials http://localhost:9000/oauth2/token
curl.exe -s -H "Authorization: Bearer TOKEN_ATM" -H "Content-Type: application/json" -d "{\"cuentaId\":1,\"monto\":10}" http://localhost:8080/transacciones/retiros
```

La evidencia de que el mensaje viajó al broker remoto es el log `SAGA cola=cuentas.debitar` en transacciones y `SAGA cola=cuentas.debitar` en cuentas, más la cola visible en la consola de ActiveMQ de la EC2. El estado final del retiro debe ser `CONFIRMADO`, igual que en `../evidencias/evidencia_s8_resiliencia_jms.txt`.

## 3. Despliegue completo en la nube

Cuando el broker ya está fuera del notebook, el resto puede mudarse a la misma red. La opción más directa es una EC2 más grande (o un ECS) con Docker, y el mismo `docker-compose.yml`. La opción por servicio es una tarea ECS por imagen, con las nueve imágenes en Elastic Container Registry.

### 3.1 Qué se mueve

| Pieza | Dónde queda | Notas |
|-------|-------------|--------|
| ActiveMQ | La EC2 del escenario 2, o un servicio en la misma VPC | Los MS usan `tcp://activemq:61616` dentro de la red, no la IP pública |
| H2 TCP | Misma Compose, o RDS PostgreSQL | H2 está en modo PostgreSQL; el cambio de URL es el salto a RDS |
| Config, Eureka, Auth, gateway y los tres MS | EC2 con Compose, o ECS | El batch corre una vez y termina |
| BFF y xyz-backend | Opcional, otra tarea | El BFF publica 8443; el backend no debe quedar público |

### 3.2 Pasos con Compose en una EC2

1. Security group: 22 desde tu IP; 8080 (gateway) si los clientes entran por ahí. 8761, 8888, 9000 y 61616 se dejan solo dentro de la VPC.
2. Clonar el repositorio, instalar Docker y el plugin Compose.
3. Construir: `./mvnw -DskipTests package` y el mismo comando dentro de `xyz`. Hace falta JDK 17 en esa máquina, o subir los jars ya compilados.
4. En los YAML docker, `broker-url` apunta al servicio ActiveMQ de la sección 2, no a `ms-transacciones`.
5. `BANK_OAUTH_ISSUER` y `issuer-uri` usan el DNS interno (`http://auth-server:9000`) para las llamadas entre contenedores. Si un cliente de fuera pide el token, el issuer que ve debe coincidir con la URL pública del puerto 9000.
6. `docker compose up --build -d` y, para cuentas, `docker compose up -d --scale ms-cuentas=2`.
7. Logs: `docker compose logs` o el agente de CloudWatch leyendo la salida de los contenedores (`/tmp/*.log` en cada servicio).

### 3.3 Comprobación

Desde una máquina que llegue al gateway:

```bash
curl -s -u web:web123 -d grant_type=client_credentials http://DNS:9000/oauth2/token
curl -s -H "Authorization: Bearer TOKEN" http://DNS:8080/transacciones/origen-config
curl -s http://DNS_INTERNO:8761/eureka/apps/MS-CUENTAS
```

Sin token se espera 401. Con el token de web, la lectura responde 200. Dos instancias de `MS-CUENTAS` confirman el escalado. Un retiro con el token de `atm` confirma que la saga sigue llegando al broker de la misma red.

### 3.4 Qué no hace falta el primer día

RDS, ECR y Auto Scaling de ECS se pueden dejar para cuando el Compose de una sola EC2 ya responda. El orden recomendado es el de este documento: local, después solo el broker en EC2, después el resto en la misma nube.

# OI Archivos · Puente remoto MCP

Este servicio enlaza asistentes compatibles con MCP y **un teléfono Android expresamente emparejado** con OI Archivos. La conexión del teléfono es **saliente HTTPS** y muestra una notificación fija mientras está activa.

## Arranque

Node.js 20+ y tres variables de entorno:

- `OI_DEVICE_TOKEN`: secreto aleatorio de 32+ caracteres; se escribe una sola vez en Ajustes de OI Archivos. No compartirlo ni guardarlo en Git.
- `OI_MCP_ROUTE_KEY`: clave independiente de 32+ caracteres en la ruta privada MCP.
- `PORT`: puerto de escucha (Railway lo proporciona automáticamente).

```sh
cd bridge
OI_DEVICE_TOKEN="TOKEN_ALEATORIO_DE_AL_MENOS_32_CARACTERES" OI_MCP_ROUTE_KEY="OTRA_CLAVE_ALEATORIA_DE_AL_MENOS_32" npm start
```

Detrás de TLS, el endpoint MCP privado es `https://servidor.example/mcp/<OI_MCP_ROUTE_KEY>`. La aplicación Android recibe solamente la URL base `https://servidor.example` y el valor de `OI_DEVICE_TOKEN`. No expongas Node directamente en HTTP público ni publiques estas credenciales.

## Alcance real

Opera sobre almacenamiento compartido visible a la aplicación mediante los permisos de Android. Incluye lectura, listados, creación y modificaciones activables; envío a papelera tiene un permiso independiente. No habilita root, exploración de datos privados de otras aplicaciones ni un shell remoto.

El puente usa una cola volátil y está diseñado para **un dispositivo emparejado** en la primera versión. No persiste archivos de usuario, pero puede procesar sus contenidos en tránsito. Para varios teléfonos se necesitan identidades, colas y claves independientes por dispositivo antes de anunciar soporte multi-dispositivo.

El conector MCP solo funciona después de: desplegar este puente detrás de HTTPS, configurar secretos, instalar la APK modificada, emparejarla en Ajustes y activar la conexión manualmente.

## Herramientas

`phone_status`, `list_roots`, `list_files`, `file_info`, `read_text`, `make_directory`, `write_new_text`, `rename_file`, `copy_file`, `move_file` y `trash_file`.

Las operaciones están restringidas por el sistema Android, y las de escritura/papelera están desactivadas inicialmente. No hay borrado definitivo remoto. Los archivos de texto se limitan a 64 KiB por petición en esta primera fase; archivos grandes, SAF, nubes y medios binarios requieren una ampliación del protocolo.

## Clientes adicionales y puesta en servicio

El puente admite **MCP** (ChatGPT y clientes compatibles) y **REST JSON** (otras aplicaciones, scripts y automatizaciones), con credenciales independientes. Para habilitar REST, define también `OI_API_TOKEN` (32 caracteres aleatorios como mínimo). Endpoints:

- `GET /api/v1/status`: estado de conexión. Enviar `Authorization: Bearer <OI_API_TOKEN>`.
- `GET /api/v1/tools`: operaciones disponibles, con el mismo encabezado.
- `POST /api/v1/run`: cuerpo JSON `{"name":"list_roots","args":{}}` y el mismo encabezado.

El endpoint MCP permanece en `POST /mcp/<OI_MCP_ROUTE_KEY>`. No introduzcas el token del móvil en su URL. Cada conector o cliente debe ser autorizado explícitamente. Esta primera versión tiene **un único token REST**, no identidades de clientes individuales; para múltiples consumidores independientes conviene emitir credenciales separadas y permitir revocación por cliente.

### VPS con Docker

```sh
cd bridge
cp .env.example .env
# Sustituir todos los CHANGE_ME por secretos independientes de: openssl rand -hex 32
docker compose up -d --build
```

El contenedor únicamente escucha en `127.0.0.1:8787` del VPS; hay que poner **un proxy HTTPS** delante (Caddy, Nginx o el proxy TLS del hosting). No publicar el puerto 8787 directamente en Internet. En Ajustes de OI Archivos, introduce solamente la base HTTPS, sin `/mcp/...`, junto al token del dispositivo. Luego activa «Conectar» y revisa la notificación visible.

Sin VPS/hosting HTTPS y credenciales reales, instalar la APK no conecta el teléfono por arte de magia. Esta versión aún no proporciona un servicio de nube gestionado.

### Advertencia sobre la firma Android

El APK de desarrollo sigue usando el certificado debug fijo del proyecto, conservado para instalar encima de versiones anteriores. Esa firma **no es apropiada para una aplicación de distribución pública con acceso amplio a archivos**. Antes de producción conviene migrar a una firma privada, gestionada fuera del repositorio. Cambiar la firma puede requerir una reinstalación o un procedimiento de rotación de claves compatible, y necesita planificación para conservar los datos locales.

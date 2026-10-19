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

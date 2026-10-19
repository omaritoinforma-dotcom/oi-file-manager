---
name: oi-archivos
description: Gestiona archivos del teléfono emparejado con OI Archivos y sus conexiones SFTP, FTP, SMB o proveedores compatibles, mediante el puente autenticado de Omar.
---

# OI Archivos

Usar el servidor MCP existente, sin recrear OI SFTP ni extraer las contraseñas guardadas en Android.

1. Consultar `phone_status`. `connected:false` significa que el móvil no está conectado, aunque HTTPS responda.
2. Consultar `device_capabilities` para comprobar la versión y los permisos reales del agente; el catálogo del servidor por sí solo no demuestra que la APK implemente todas las operaciones.
3. Para archivos del móvil, usar `list_roots`; para servidores y nubes ya configurados en la aplicación, usar `list_connections` y pasar su `connectionId` a las operaciones de archivos. Omitirlo selecciona almacenamiento local. Nunca devolver las contraseñas o tokens de las conexiones.
4. Ejecutar las acciones dentro del encargo autorizado. El usuario ya puede autorizar una tarea completa; no pedir confirmación por cada paso reversible necesario. Los controles de Android y de autenticación siguen aplicándose.
5. Usar `offset` y `nextOffset` para recorrer listados completos. Leer binarios con `read_file_chunk` en bloques de hasta 65536 bytes.
6. Para subir archivos de hasta 256 MiB, calcular tamaño y SHA-256; llamar `begin_upload`, enviar bloques base64 mediante `upload_chunk` con el desplazamiento devuelto y finalizar con `commit_upload`. Reintentar solamente bloques idénticos. `cancel_upload` descarta la preparación. No se sobrescribe un destino existente.
7. Escritura y envío a papelera requieren permisos activos; el borrado definitivo tiene permiso adicional y se limita a archivos o carpetas vacías. No borrar raíces. `trash_file` y el reemplazo condicional `replace_text` son locales; los proveedores remotos no comparten un contrato de papelera ni de comparación atómica. Crear una nueva versión cuando corresponda. Copia remota directa: archivo individual de hasta 8 MiB. SFTP admite mover entre carpetas; otros proveedores pueden limitarse al renombrado.
8. Ante un tiempo de espera, comprobar el archivo antes de repetir una escritura: el móvil puede haberla ejecutado aunque la respuesta no llegara.

## Emparejamiento y límites

El servicio Android mantiene una notificación con control de desconexión. No concede root ni acceso a datos privados de otras aplicaciones. La configuración y los permisos permanecen en el dispositivo; no prometer que un token del servidor los habilita por sí solo.

El administrador puede emitir un enlace de emparejamiento de un uso y 30 minutos mediante la API privada del puente. La página abre `oiarchivos://pair` y la APK pide confirmación local antes de canjearlo. No incluir el token permanente en el enlace ni en archivos del plugin.

El endpoint MCP es `https://test.omaritoinforma.com/oi-android-bridge/index.php/mcp`, con autenticación Bearer independiente del token del dispositivo. Usar la conexión autorizada del host; instalar un plugin no prueba que la autenticación esté completada.

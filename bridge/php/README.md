# Puente PHP para IONOS

Compatible con la APK v0.2.142 sin cambiar el protocolo Android. No requiere daemon, Node ni base de datos. PHP 7.4+ con JSON; estado temporal protegido por flock y directorio privado fuera de las raíces web.

Desplegar index.php, tools.json y .htaccess en `/test-omaritoinforma/oi-android-bridge/`. Configuración privada en `/private/oi-android-bridge/config.php` con claves independientes `device_token`, `mcp_token` y `api_token` (64 caracteres aleatorios). No versionar valores. Proteger el directorio con 0700 y los archivos con 0600. Se puede cambiar la ubicación con OI_BRIDGE_PRIVATE.

Servidor Android: `https://test.omaritoinforma.com/oi-android-bridge/index.php`. MCP: añadir `/mcp` y autenticar con Bearer; REST: añadir `/api/v1/status`, `/api/v1/tools` o `/api/v1/run` y su token independiente. La ruta index.php usa PATH_INFO para evitar depender del .htaccess del directorio padre.

La cola guarda hasta 32 comandos temporalmente, entrega cada comando una sola vez, rechaza respuestas duplicadas y elimina las respuestas tras consumirlas. Los elementos vencidos se purgan en la siguiente petición; no hay borrado programado durante inactividad. Un timeout no permite saber si una escritura llegó a completarse en el móvil: comprobar el resultado antes de repetir.

Un teléfono por despliegue. Sigue sujeto a los permisos que Android conceda. No controla otras apps ni habilita root. Firma de desarrollo de la APK pendiente de migración para producción.

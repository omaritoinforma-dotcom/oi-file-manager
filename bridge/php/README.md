# Puente PHP para IONOS

## Versión 0.3

21 herramientas: añade el despacho hacia las conexiones guardadas mediante `connectionId`, permisos reales del agente, listados paginados, borrado de archivos/carpetas vacías y subidas verificadas por bloques hasta 256 MiB. Las credenciales SFTP permanecen en Android. Consultar `device_capabilities`: una APK antigua no implementa las funciones nuevas aunque el catálogo del servidor las anuncie.

El administrador autenticado con el token REST puede llamar `POST /api/v1/pairing` para emitir un enlace de un uso que caduca a los 30 minutos. Abrirlo no consume el ticket; la APK actualizada muestra la autorización y canjea el ticket mediante `POST /device/pair`. No se publica el token del dispositivo en la URL. Configuración, secretos y cola existentes se conservan al actualizar PHP y tools.json.

`replace_text` y papelera siguen siendo locales. Los proveedores remotos no garantizan reemplazo condicional atómico ni papelera común. Copias remotas directas limitadas a archivos individuales de 8 MiB; para subir archivos mayores usar el protocolo por bloques. SFTP permite mover entre carpetas; otros proveedores pueden limitarse a renombrar.

Compatible con la APK v0.2.142 sin cambiar el protocolo Android. No requiere daemon, Node ni base de datos. PHP 7.4+ con JSON; estado temporal protegido por flock y directorio privado fuera de las raíces web.

Desplegar index.php, tools.json y .htaccess en `/test-omaritoinforma/oi-android-bridge/`. Configuración privada en `/private/oi-android-bridge/config.php` con claves independientes `device_token`, `mcp_token` y `api_token` (64 caracteres aleatorios). No versionar valores. Proteger el directorio con 0700 y los archivos con 0600. Se puede cambiar la ubicación con OI_BRIDGE_PRIVATE.

Servidor Android: `https://test.omaritoinforma.com/oi-android-bridge/index.php`. MCP: añadir `/mcp` y autenticar con Bearer; REST: añadir `/api/v1/status`, `/api/v1/tools` o `/api/v1/run` y su token independiente. La ruta index.php usa PATH_INFO para evitar depender del .htaccess del directorio padre.

La cola guarda hasta 32 comandos temporalmente, entrega cada comando una sola vez, rechaza respuestas duplicadas y elimina las respuestas tras consumirlas. Los elementos vencidos se purgan en la siguiente petición; no hay borrado programado durante inactividad. Un timeout no permite saber si una escritura llegó a completarse en el móvil: comprobar el resultado antes de repetir.

Un teléfono por despliegue. Sigue sujeto a los permisos que Android conceda. No controla otras apps ni habilita root. Firma de desarrollo de la APK pendiente de migración para producción.

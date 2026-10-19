# OI Archivos · Cloud IONOS (versión web + Android + MCP)

**Estado:** código de integración en rama de desarrollo. No implica que IONOS esté desplegado ni que el teléfono esté emparejado.

## Concepto
- Un único directorio raíz en hosting IONOS, accesible también por SFTP **sin iniciar SFTP desde el mismo hosting**: PHP opera directamente sobre el directorio local autorizado.
- APK Android: nuevo origen permanente **IONOS · Mis archivos** en Inicio y menú; configuración heredada del emparejamiento HTTPS. Sin segundas credenciales SFTP en Android.
- Web: explorador responsivo servido desde \`/oi-cloud/index.html\` con sesión autenticada; mismos archivos que Android y cliente MCP.
- API HTTP JSON y endpoint MCP separado por token. Leer el almacenamiento del teléfono sigue exigiendo el emparejamiento independiente del puente Android.
- Desconexión: solo se guardan listas de carpetas y archivos explícitamente descargados. No se fabrican datos offline ni se descarga todo el hosting.
- Transferencias Android **solo en Wi-Fi no medido** por defecto. Si un APK de 27 MB viaja al dispositivo, esos bytes necesariamente llegan por alguna red o cable. El servidor no puede instalarlo mágicamente sin tráfico.
- Actualizaciones de APK mediante consentimiento de instalación de Android, comprobación de firma y versiones: previsto; **no implementado aún**.

## Instalación en IONOS (PHP 7.4+)

1. Desplegar los archivos de este directorio en **\`/oi-cloud/\`** del mismo host de HTTPS que el puente \`/oi-android-bridge/index.php\`. Conservar \`index.html\`, \`app.js\`, \`index.php\`, \`.htaccess\`. Para producción bloquear el acceso web a \`smoke.py\` y otros archivos de origen o, preferiblemente, desplegar únicamente los cuatro archivos enumerados.
2. En configuración PHP privada del puente existente (\`OI_BRIDGE_PRIVATE/config.php\`) **añadir** estas claves (sin borrar las claves originales):
    \`\`\`php
    'cloud_root' => '/ruta/absoluta/real/a/directorio-autorizado',
    'cloud_web_password_hash' => 'HASH_GENERADO_POR_PASSWORD_HASH',
    \`\`\`
    Para el hash usar \`php -r 'echo password_hash("CLAVE_UNICA_LARGA", PASSWORD_DEFAULT), PHP_EOL;'\`, ejecutado **solo en entorno privado**, no publicar la contraseña. El \`cloud_root\` debe ser un subdirectorio deliberadamente seleccionado; no configurar \`/\`, \`/private\` ni carpetas que contengan secretos.
3. La API hereda \`device_token\` para Android y \`mcp_token\` para clientes MCP de la configuración privada del puente; usar credenciales diferentes para usuario web, móvil y ChatGPT. No incluir secretos en el frontend ni el APK.
4. Ruta web: \`https://test.omaritoinforma.com/oi-cloud/\`. Endpoint Android: \`https://test.omaritoinforma.com/oi-cloud/index.php?action=list&path=/\`; requiere token Bearer en encabezado. MCP: \`POST https://test.omaritoinforma.com/oi-cloud/index.php?action=mcp\`, Bearer MCP.
5. El alojamiento debe servir ambas rutas por HTTPS. Si termina TLS en un proxy real y verificado, es posible establecer \`OI_CLOUD_TRUST_PROXY=1\`; **no habilitar confianza en X-Forwarded-Proto cuando los clientes pueden enviar ese encabezado sin control de proxy**.

## Límites actuales
- API lista carpetas, lectura UTF-8 acotada, crear carpeta, renombrar, eliminar archivos, crear texto, subir archivos (32 MiB máximo PHP), bajar archivos (máximo 512 MiB en APK). La UI web soporta crear carpeta, renombrar, borrar archivo, subir y bajar.
- Lista hasta 1000 entradas; lista offline guardada en preferencias Android, no reconstruye un sistema virtual completo ni sincroniza automáticamente escrituras pendientes.
- Cada archivo descargado en Android persiste bajo directorio privado de la app. No se ve como carpeta física por otras aplicaciones sin exportarlo.
- Credenciales y permisos de escritura del puente configurables en Ajustes Android. Aún falta el control por dispositivo sobre el endpoint cloud en el servidor, notificaciones push y control de versiones o conflictos de edición concurrente.
- Seguridad: bloquea \`..\`, NUL, barras invertidas y enlaces simbólicos; no usa root. La ubicación raíz no debe estar expuesta públicamente como sitio web si contiene ficheros sensibles; protege el directorio frente a ejecución de scripts cargados y URL directas. No usar como almacenamiento de secretos hasta revisión de seguridad completa.
- App instalada v0.2.187 todavía usa la ruta defectuosa \`https://test.omaritoinforma.com/oi-android-bridge/\` para «Abrir panel de emparejamiento»; la rama nueva cambia a \`/oi-android-bridge/index.php\`. La página existente solo presenta instrucciones, **no hace el emparejamiento por pulsar el botón**. Para vincular, usar un ticket emitido por una herramienta autorizada o configurar token en Ajustes y pulsar «Conectar».

## Verificación
\`\`\`sh
php -l bridge/cloud/index.php
python3 bridge/cloud/smoke.py
# Además: pruebas Android con ./gradlew testDebugUnitTest assembleRelease
\`\`\`

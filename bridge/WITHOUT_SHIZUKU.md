# Android normal y ruta ADB

La API del puente conserva la misma identidad, autenticación y argumentos. Esta
ampliación requiere actualizar la APK; no cambia permisos de Android mediante
el servidor ni activa ADB, root, Shizuku o Accesibilidad.

`debug_status.withoutShizuku` anuncia las acciones disponibles. `rootlessReady`
conserva su significado anterior: servicio ADB/Shizuku autorizado con UID 2000.
Por tanto, puede ser `false` y existir diagnóstico mediante API Android.

| Acción | Sin Shizuku |
| --- | --- |
| Información del dispositivo y apps instaladas | API pública; permiso remoto local vigente |
| `package` | Versión, firmas públicas actuales/históricas y permisos; sin datos privados |
| Batería, red, pantalla y almacenamiento | Información que Android proporciona al UID de la app |
| Memoria, actividad y procesos | Totales públicos y proceso propio de OI Archivos; no otros procesos |
| Rutas del sistema | Solo rutas públicas legibles por el UID de la app y la lista de lectura existente |
| `install`, `uninstall` | Solicitud visible; confirmación final del usuario en Android |
| Logcat ajeno, cierres ajenos, AppOps, concesión de permisos, detener/borrar datos, inyectar toques y capturas | La ruta avanzada sigue necesitando privilegios ADB/Shizuku |

## Solicitudes de instalación

`debug_run` con `action:install`, `value:/storage/.../archivo.apk` y paquete
esperado opcional crea una copia privada e inmutable del archivo. Rechaza un
paquete distinto al solicitado. Máximo 256 MiB por APK, cuatro solicitudes y
caducidad de una hora. Se conserva el origen sin modificarlo. El APK no se
instala automáticamente ni se sustituye su firma.

Se publica una notificación que abre una actividad privada. Si las notificaciones
están desactivadas, se puede abrir desde OI Archivos > Depurador > Solicitudes
pendientes. La actividad muestra paquete, versión y SHA-256, y abre el instalador
de Android solo al pulsar el botón. Si falta autorización para instalar desde
OI Archivos, Android muestra sus ajustes de origen; al volver, el usuario puede
pulsar de nuevo. No se confirma nada automáticamente.

El instalador recibe permiso de lectura para la URI concreta de un proveedor
dedicado y no exportado, limitado a la caché de solicitudes. No se solicita acceso
a todo el almacenamiento mediante ese proveedor. Se comprueba otra vez que el
conector y sus permisos siguen activos antes de continuar una solicitud remota.

La respuesta inicial tiene `status:requires_user_action`, `completed:false`,
`requiresUserAction:true` y `exitCode:null`. La aceptación de la solicitud no
acredita instalación. Después de la confirmación, consultar `debug_list_apps` o
`package` para verificar versión y presencia del paquete. La desinstalación usa
la confirmación normal de Android y exige además autorización local de borrado.

El relay no necesita comandos nuevos para esta ruta. Los catálogos describen
el protocolo nuevo, pero una APK antigua seguirá rechazando esas acciones sin
Shizuku hasta que el propietario instale la actualización. La primera actualización
del propio puente requiere interacción local con el instalador existente.

## Verificación

Las pruebas JVM cubren conservación del origen, instantáneas, integridad, límites,
caducidad, recuperación de solicitudes y rechazo de rutas/paquetes inválidos.
La instrumentación en Android 13 comprueba UID normal, metadatos de firma,
consentimiento remoto, bloqueo de acciones ADB, cola sin notificaciones, proveedor
acotado, componentes privados y revocación de permisos. No pulsa la confirmación
final de instalación/desinstalación ni declara ejecutada una solicitud pendiente.

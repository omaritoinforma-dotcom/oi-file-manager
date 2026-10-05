# OI Archivos — informe de avance

Fecha: 5 de octubre de 2026. Rama `ccr-cc9d8418-yd5iu0` (PR #1). Estado: **versión de desarrollo; equivalencia completa con ES File Explorer pendiente**.

## Objetivo y criterio de terminación

Continuar la aplicación Android iniciada por Claude hasta cubrir, como mínimo, las funciones de ES File Explorer. Después podrán añadirse opciones adicionales. Compilar un APK o añadir una pantalla no basta para declarar terminado ese mínimo: cada función debe estar conectada a la interfaz y **comprobarse en su entorno real**, es decir, en el emulador Android 15 del CI o contra un servidor real.

## Estado en una tabla

La matriz completa está en [COMPARACION_ES.md](COMPARACION_ES.md). Se hizo a partir del APK oficial de ES 4.4.2.2.1, con firma verificada. Tiene 85 filas:

| Estado | Filas | Qué significa |
| --- | ---: | --- |
| ✅ | 69 | Implementado y comprobado en el emulador Android 15 del CI o contra un servidor real |
| 🟡 | 15 | Implementado y conectado a la interfaz; su comprobación en el emulador ya está escrita y espera su pasada, o necesita hardware real |
| 🟠 | 0 | Parcial |
| ❌ | 1 | Falta: las nubes minoritarias (MediaFire, Flickr, Instagram, Facebook, Nutstore, China Mobile Cloud) |

Lo que falta en ❌ necesita hardware o cuentas que el CI no tiene.

## Cómo se comprueba

### Servidores reales en el CI

El trabajo `build` de `.github/workflows/build.yml` levanta servidores de verdad antes de las pruebas JVM. Las pruebas usan los clientes de la app y comprueban el resultado en el disco del servidor.

| Servidor | Cómo se levanta | Qué se prueba |
| --- | --- | --- |
| SFTP, FTP y WebDAV | rclone 1.75.1 (`scripts/remote_servers.py`) | listar, crear, subir, leer desde un desplazamiento, renombrar y borrar; descargas y subidas movidas que se interrumpen y se reanudan sin restos |
| FTPS explícito e implícito | pyftpdlib con certificado de prueba (`OI_REMOTE_FTPS=1`) | certificado validado, nombre del servidor comprobado y reanudación |
| SMB 2/3 con firma obligatoria | Samba (`scripts/smb_samba.sh`) | ciclo completo y contraseña incorrecta rechazada |
| NFS 3 | nfs-ganesha y rpcbind (`scripts/nfs_ganesha.sh`) | ciclo completo, lectura desde un desplazamiento y exportación inexistente rechazada |

Además, el servidor FTP del propio teléfono se prueba con el cliente FTP de commons-net. Se prueban los modos pasivo y activo, la protección contra «FTP bounce», las codificaciones Latin-1 y GBK y el puerto fijo.

### Emulador Android 15

El trabajo `smoke` arranca la app en un emulador Android 15 y ejecuta dos scripts:

- `scripts/smoke_android.py` recorre la app de punta a punta. Incluye una descarga SFTP de 24 MiB que se reanuda tras matar el proceso y se compara por SHA-256.
- `scripts/android_features.py` hace **89 comprobaciones**, una por función. Cada una guarda su captura, su jerarquía de pantalla y el resultado en `results.json`.

Desde ahora las comprobaciones se reparten en **tres emuladores nuevos en paralelo** (`OI_SHARD=k/3`). Así una sesión no pasa de unos 40 minutos.

**Última pasada completa (35e0256, ejecución 37331516858):** aprobó **84 de 89** en tres emuladores. Pasan a ✅ 11 filas: selección múltiple, copiar ruta, vistas, temas, tipos de documento, idioma, unir vídeos y GIF, lista de apps, NFS desde Android, envío entre teléfonos (también uniéndose a la red de un punto de acceso) y Android TV por ADB. También aprobaron por primera vez el servidor OBEX por Bluetooth (arranca en la pila Bluetooth real; falta otro equipo), la intro y el outro del editor de vídeo, limpiar las carpetas que deja una app, el fondo con imagen y el aviso claro cuando el teléfono no puede crear un punto de acceso.

Antes hubo que arreglar el propio CI: Google estaba actualizando la imagen de Android 15 y la descarga llegaba dañada. El paso con reintentos (d72d226) llamaba a `sdkmanager`, que no está en el PATH del runner; 35e0256 usa el del SDK.

Los 5 fallos, en estudio:

- **DLNA y Chromecast:** la app reproduce en la TV de prueba y muestra la posición, pero la petición del archivo que hace la «TV» desde el equipo de CI (redirigida por la consola del emulador) no recibe respuesta. Aprobaban en 521f954 y fallan desde be8215d con el mismo orden de comprobaciones. La próxima pasada guarda el estado de la red del emulador para encontrar la causa.
- **USB y tarjeta SD:** el aviso y la tarjeta de la unidad en Inicio funcionan; lo que no encontraba la prueba era el texto de la notificación en la cortina. Ahora la abre de otra forma y guarda su captura.
- **Root:** el su de prueba quedaba montado, pero la app no lo veía: un montaje hecho después de arrancar zygote no llega a las apps. Ahora se monta también dentro del espacio de montajes del proceso de la app, como hace Magisk.
- **GIF:** el archivo se escribe de forma atómica; lo que falló fue leerlo con `adb pull`, que ahora se reintenta.

Además, cada comprobación que falla guarda el logcat del sistema.

### Pruebas JVM

Hay 236 pruebas en 46 archivos. En local pasan todas menos 9, que necesitan rclone y 7-Zip; en el CI pasan también.

## Errores reales que encontraron las pruebas

| Dónde | Qué pasaba | Cómo se encontró |
| --- | --- | --- |
| FTPS implícito | El puerto 990 se trataba como FTPS explícito y la conexión fallaba | Prueba contra pyftpdlib en modo implícito |
| NFS | Las carpetas y archivos nuevos quedaban con permisos 000. Además, tras renombrar el temporal, el objeto viejo seguía apuntando al archivo y «borrar el temporal» se llevaba el archivo bueno | Prueba contra nfs-ganesha |
| ZIP sin compresión | zip4j fallaba en modo STORE si no se indicaba el tamaño | Prueba JVM de niveles de compresión |
| Recorte de imagen | Arrastrar una esquina más allá de la opuesta daba la vuelta al recuadro | Prueba JVM de `ImageCrop` |
| NFS en la interfaz | La pantalla de conexiones empieza en la carpeta exportada y NfsFs la pedía dentro de la propia exportación: «file handle is null» y no se listaba nada | Emulador contra nfs-ganesha |
| Enviar leyendo un QR | El enlace del QR (lo abre la cámara) creaba una segunda pantalla con su propio estado y se perdían los archivos elegidos | Emulador |
| Subtítulos del editor de vídeo | Entre dos subtítulos el texto quedaba vacío y Media3 intentaba crear una imagen de ancho 0: la exportación fallaba si el primer subtítulo no empezaba en 0 | Leyendo el código de `TextOverlay` de Media3 al añadir la intro; la comprobación nueva del emulador lo cubre |
| Aviso de poco espacio | Al activarlo había que esperar a la revisión de cada hora; ahora revisa al momento | Emulador |
| Búsqueda avanzada (anterior) | Siempre mostraba 0 resultados | Emulador |
| Recepción entre teléfonos (anterior) | Dejaba bytes sin leer tras un error y estropeaba la solicitud siguiente | Emulador |
| FTP, SFTP (anterior) | Nombres con tildes cambiados, cancelaciones que acababan en error y parciales que quedaban en el servidor | Servidores reales |

## Novedades de este punto de control

- **Red:**
  - SMB 2/3, FTPS explícito e implícito y NFS 3, comprobados contra servidores reales.
  - Servidor FTP del teléfono con modo activo, puerto fijo, codificación de nombres, contraseña fija opcional y opción de detenerlo al salir.
- **Explorador:**
  - Búsqueda avanzada por tipo, ocultos y subcarpetas, y búsqueda dentro de una categoría.
  - Barra de herramientas inferior configurable (botones y orden; lo que se quita pasa a «Más»).
  - Botón de selección y botón de pestañas.
- **Inicio:** secciones e iconos que se pueden ocultar y reordenar, sección «Archivos nuevos» y buscador.
- **Imagen y vídeo:**
  - Editor de imagen (recortar con proporciones, girar, voltear; copia o reemplazo atómico).
  - Intro y outro en el editor de vídeo, como en ES: una foto recortada al centro, un texto sobre un color o ambos, de 2, 3 o 5 s.
  - La comprobación en el emulador del editor de vídeo usa vídeos reales grabados con `screenrecord` y un vídeo con sonido hecho con PyAV, cuyos fotogramas y audio se decodifican para comprobar la intro, el outro, el texto y los subtítulos.
- **Ajustes de ES que faltaban:**
  - orientación;
  - diseño grande;
  - nombre en la barra;
  - estilo de carpetas;
  - fondo con imagen;
  - tipos de documento;
  - límite del resaltado;
  - aviso de permisos al instalar una app;
  - notificación fija con el uso del almacenamiento.

  Todos van en la copia de ajustes y se validan al restaurar, salvo los que son del dispositivo: la contraseña FTP y la imagen de fondo.
- **Envío entre teléfonos:** se puede enviar leyendo un código QR.
- **Chromecast:** envío con el protocolo abierto CASTV2 (TLS y protobuf hechos a mano, sin el SDK de Google), búsqueda por mDNS o por IP; el enlace del archivo solo lo puede leer ese Chromecast.
- **Android TV por ADB:** cliente ADB propio (sin binarios externos) para instalar APK elegidos en el explorador, listar, abrir y desinstalar apps y usar el teléfono como mando. La primera vez la TV pregunta si permite la depuración; después basta la firma RSA de la app. Probado en la JVM y, en el emulador, contra una TV falsa que verifica la firma.
- **Memorias USB y tarjetas SD:** aviso al conectarlas con «Abrir» y «Expulsar», aviso si se quitan sin expulsar y botón de expulsar en Inicio. Como Android no deja a las apps desmontar sin root, «Expulsar» comprueba que no quede ninguna copia en curso y abre Ajustes → Almacenamiento.
- **Root:** quitar y devolver apps del sistema (`pm uninstall -k --user 0`), montar el sistema en lectura y escritura cuando Android lo permite y editar el hosts (con una copia montada encima si el sistema no se puede escribir). El explorador root lista cada carpeta con una sola orden. En el emulador se prueban con un su de prueba (scripts/test_root): un demonio root, como el de Magisk, ejecuta las órdenes que deja el su.
- **Punto de acceso:** «Recibir con punto de acceso (sin router)» crea un punto de acceso local y pone su red y clave en el QR; el que envía se une a esa red solo para la app y envía por ella. El emulador no puede crear puntos de acceso (lo confirma el diagnóstico de la pasada 37257397887), así que se prueba la parte del que envía con la Wi-Fi del emulador.
- **Servidor OBEX por Bluetooth:** Compartir por red → Bluetooth (OBEX FTP) deja que los equipos emparejados exploren la carpeta elegida (solo lectura salvo que se permita escribir). El cliente OBEX de la app ahora funciona sobre cualquier flujo y se prueba en la JVM contra el servidor.
- **Idioma:** la app se puede usar en español o en inglés, o seguir el idioma del teléfono (Ajustes → Pantalla). Los textos siguen escritos en español en el código y `tr("…")` los traduce con el catálogo `assets/i18n/en.tsv`; una prueba JVM recorre el código y exige que cada texto tenga su traducción.
- **Apps:** al desinstalar una app desde OI Archivos, propone mover a la papelera las carpetas con su nombre que dejó en la raíz del almacenamiento (como «Clean associated folders» de ES, pero solo por nombre exacto y con confirmación).

## Trabajo que falta

1. **Pasada del emulador** de las 18 filas en 🟡, ya escritas: corregir lo que falle y pasar a ✅ lo que apruebe.
2. **Más idiomas:** el catálogo admite cualquier idioma; hoy hay español e inglés.
3. **Nubes:** registrar el acceso OAuth de la aplicación y comprobar cada operación con cuentas reales (Drive, Dropbox, OneDrive, Box, Yandex, S3, Baidu, SugarSync).
4. **Hardware y root, imposibles en el CI:** un Chromecast físico (el protocolo se prueba con un receptor de prueba), punto de acceso Wi-Fi, una Android TV física (el protocolo ADB se prueba con una TV falsa), Bluetooth OBEX (cliente y servidor con otro equipo real), USB OTG, tarjeta SD y funciones root.
5. **Revisión estática:** `lintDebug` no tiene errores. Quedan 28 advertencias, sobre todo versiones nuevas de dependencias y la API objetivo 34. Las dos de «TrustAllX509TrustManager» son de clases de commons-net que la app no usa. La de «CustomX509TrustManager» es la conexión con el Chromecast: se acepta su certificado propio solo hacia direcciones de la red local, porque la app no hace el desafío de autenticación de Google.

No se declara el proyecto completo.

## Cómo retomar

Revisar primero este informe, [COMPARACION_ES.md](COMPARACION_ES.md) y `FEATURES.md`. Después, continuar por la pasada del emulador y las filas en 🟡.

Herramientas: JDK 17 completo, Gradle 8.11.1, SDK/plataforma Android 35 y build-tools 35.0.0. El NDK 27.2.12479018 solo hace falta para reconstruir los binarios Android de 7-Zip, que ya están en `app/src/main/jniLibs`.

```sh
python3 scripts/build_archives.py --host
export OI_ARCHIVE_TEST_EXECUTABLE="$PWD/build/native-archives/--host/7zz"
pip install cryptography pyftpdlib pyopenssl
OI_REMOTE_FTPS=1 RCLONE=/ruta/a/rclone python3 scripts/remote_servers.py start > servers.env
scripts/smb_samba.sh start "$PWD/build/remote-servers/data" oi una-clave >> servers.env   # sudo; instala Samba si falta
scripts/nfs_ganesha.sh start "$PWD/build/remote-servers/data" >> servers.env             # sudo; instala nfs-ganesha si falta
set -a; . ./servers.env; set +a
LANG=C.UTF-8 ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Las pruebas con nombres no ASCII necesitan una configuración regional UTF-8 (`LANG=C.UTF-8`). Gradle no repite una prueba si solo cambian las variables de entorno: para volver a probar contra los servidores, use `cleanTestDebugUnitTest testDebugUnitTest`.

Para instalar una actualización encima, la compilación necesita un número mayor que el instalado (`OI_BUILD_NUMBER` o, en el CI, el número de ejecución).

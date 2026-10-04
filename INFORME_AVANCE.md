# OI Archivos — informe de avance

Fecha: 4 de octubre de 2026. Rama `ccr-cc9d8418-yd5iu0` (PR #1). Estado: **versión de desarrollo; equivalencia completa con ES File Explorer pendiente**.

## Objetivo y criterio de terminación

Continuar la aplicación Android iniciada por Claude hasta cubrir, como mínimo, las funciones de ES File Explorer. Después podrán añadirse opciones adicionales. Compilar un APK o añadir una pantalla no basta para declarar terminado ese mínimo: cada función debe estar conectada a la interfaz y **comprobarse en su entorno real**, es decir, en el emulador Android 15 del CI o contra un servidor real.

## Estado en una tabla

La matriz completa está en [COMPARACION_ES.md](COMPARACION_ES.md). Se hizo a partir del APK oficial de ES 4.4.2.2.1, con firma verificada. Tiene 85 filas:

| Estado | Filas | Qué significa |
| --- | ---: | --- |
| ✅ | 40 | Implementado y comprobado en el emulador Android 15 del CI o contra un servidor real |
| 🟡 | 32 | Implementado y conectado a la interfaz; su comprobación en el emulador ya está escrita y espera su pasada |
| 🟠 | 4 | Parcial: Chromecast, punto de acceso Wi-Fi, intro/outro del editor de vídeo y limpiar las carpetas que deja una app |
| ❌ | 9 | Falta: idioma dentro de la app, funciones que exigen root (desinstalar apps del sistema, montar /system, editar hosts), Android TV por ADB, servidor OBEX, expulsar USB, punto de acceso propio y las nubes minoritarias |

Lo que falta en ❌ y en 🟠 necesita casi siempre hardware, root o cuentas que el CI no tiene. La excepción es el idioma: hay que sacar a recursos los textos de la interfaz, que hoy están escritos en español dentro del código.

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
- `scripts/android_features.py` hace **78 comprobaciones**, una por función. Cada una guarda su captura, su jerarquía de pantalla y el resultado en `results.json`.

Desde ahora las comprobaciones se reparten en **tres emuladores nuevos en paralelo** (`OI_SHARD=k/3`). Así una sesión no pasa de unos 40 minutos.

**Última pasada completa (5625ab5, ejecución 37215783930):** aprobó **47 de 57**. Aprobaron por primera vez:

- fijar arriba;
- subcategorías;
- analizador de permisos;
- aviso e informe de archivos nuevos;
- aviso de poco espacio;
- listas de reproducción;
- copia automática al SFTP real.

Los 10 fallos eran de la prueba y están corregidos en `df93f9f`:

- el menú lateral se tocaba antes de abrirse;
- había listas que solo se buscaban hacia abajo;
- una ficha marcada se leía en el nodo equivocado;
- la pantalla de apps predeterminadas de Android 15 es de `permissioncontroller`;
- se leía un archivo del servidor justo mientras la app lo sustituía;
- `am start -W` superó 30 s.

Las 21 comprobaciones nuevas de este punto de control esperan su primera pasada.

### Pruebas JVM

Hay 206 pruebas en 41 archivos. En local pasan todas menos 9, que necesitan rclone y 7-Zip; en el CI pasan también.

## Errores reales que encontraron las pruebas

| Dónde | Qué pasaba | Cómo se encontró |
| --- | --- | --- |
| FTPS implícito | El puerto 990 se trataba como FTPS explícito y la conexión fallaba | Prueba contra pyftpdlib en modo implícito |
| NFS | Las carpetas y archivos nuevos quedaban con permisos 000. Además, tras renombrar el temporal, el objeto viejo seguía apuntando al archivo y «borrar el temporal» se llevaba el archivo bueno | Prueba contra nfs-ganesha |
| ZIP sin compresión | zip4j fallaba en modo STORE si no se indicaba el tamaño | Prueba JVM de niveles de compresión |
| Recorte de imagen | Arrastrar una esquina más allá de la opuesta daba la vuelta al recuadro | Prueba JVM de `ImageCrop` |
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
  - La comprobación en el emulador del editor de vídeo usa vídeos reales grabados con `screenrecord`.
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

## Trabajo que falta

1. **Pasada del emulador** de las 32 filas en 🟡, ya escritas: corregir lo que falle y pasar a ✅ lo que apruebe.
2. **Idioma dentro de la app:** sacar los textos a `strings.xml` y añadir al menos inglés.
3. **Nubes:** registrar el acceso OAuth de la aplicación y comprobar cada operación con cuentas reales (Drive, Dropbox, OneDrive, Box, Yandex, S3, Baidu, SugarSync).
4. **Hardware y root, imposibles en el CI:** Chromecast, punto de acceso Wi-Fi, Android TV por ADB, Bluetooth OBEX (cliente con un dispositivo real; servidor por hacer), USB OTG, tarjeta SD y funciones root.
5. **Editor de vídeo:** intro y outro.
6. **Revisión estática:** `lintDebug` no tiene errores. Quedan 26 advertencias, sobre todo dependencias nuevas y la API objetivo 34.

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

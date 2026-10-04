# OI Archivos — informe de avance

Fecha: 4 de octubre de 2026. Versión del APK comprobado: **0.2.7**, código de versión **7**. Estado: **versión de desarrollo; equivalencia completa con ES File Explorer pendiente**.

## Objetivo y criterio de terminación

Continuar la aplicación Android iniciada por Claude hasta cubrir, como mínimo, las funciones de ES File Explorer. Después podrán añadirse opciones adicionales. Compilar un APK o añadir una pantalla no basta para declarar terminado ese mínimo: cada función debe estar conectada a la interfaz y comprobarse en su entorno real.

Este punto de control añade la pausa y la recuperación persistente de transferencias de red/nube, completa por primera vez la revisión estática `lintDebug` y empieza a comprobar los clientes de red contra **servidores reales**, no simulados.

### Comprobación con servidores reales

`scripts/remote_servers.py` inicia servidores SFTP, FTP y WebDAV reales (rclone 1.75.1) sobre una carpeta compartida; `RemoteServerTest` usa los clientes reales de la aplicación contra ellos y comprueba el resultado en el disco del servidor. CI los inicia antes de las pruebas. Esto destapó cuatro errores que las pruebas simuladas no veían, ya corregidos:

| Error encontrado | Efecto para el usuario | Corrección |
| --- | --- | --- |
| FTP enviaba los nombres en ISO-8859-1 | Archivos con tildes o ñ (`año.bin`) se creaban con otro nombre o no se encontraban | Canal de control en UTF-8 y `OPTS UTF8 ON` |
| Cancelar o pausar una subida FTP | La conexión quedaba esperando ~30 s y terminaba en «Error» en lugar de «Cancelado» | La interrupción cierra la conexión de datos; limpieza sin bloqueo |
| Cancelar una subida SFTP | JSch envolvía la cancelación y se registraba como error | Se recupera la cancelación original |
| Subida SFTP interrumpida | Quedaba un archivo oculto `.oi-….part` para siempre en el servidor | Nombre temporal fijo por destino (también FTP, SMB y root): el reintento lo reemplaza |

Resultado con servidores reales: listar, crear, subir, leer, leer desde un desplazamiento, renombrar y borrar; descarga movida interrumpida y reanudada por bytes; subida movida interrumpida y reanudada sin restos. Las tres pruebas pasan en SFTP, FTP y WebDAV. SMB, FTPS (requiere certificado de confianza) y las nubes siguen sin servidor real.

**Comprobado en Android 15 (emulador de CI, ejecución 37180055226):** se creó una conexión SFTP desde la interfaz contra un servidor real en el equipo de CI, se inició una descarga de 24 MiB, se mató la aplicación (`am force-stop`) con 5.404.575 bytes descargados, se reabrió, se reanudó desde Transferencias y el SHA-256 del archivo final coincidió con el original; el original se conservó en el servidor y no quedaron archivos parciales. Los 14 pasos anteriores de la prueba del emulador también pasaron.

## Base de la aplicación

El proyecto conserva gestión de archivos locales y por lotes, conflictos de nombres, papelera, propiedades y hashes; pestañas, búsqueda, categorías, favoritos e historial; editor de texto; galería, audio/video y PDF; análisis de espacio y duplicados; administración y respaldo de APK; acceso SD/USB mediante SAF; conexiones FTP/FTPS/SFTP/SMB/WebDAV; nubes; servidores HTTP/FTP; cifrado de archivos y operaciones root autorizadas.

El detalle de capacidades y límites está en [FEATURES.md](FEATURES.md). La presencia de código en esas áreas no significa que todos sus dispositivos, servidores o proveedores hayan sido probados en este APK.

## Avances conservados en este punto de control

| Área | Trabajo incorporado | Comprobación actual |
| --- | --- | --- |
| Transferencias de red/nube | Registro persistente para descargas (remoto → local) y subidas (local → remoto), en copia o movimiento: pausa, cancelación y reanudación desde Transferencias tras un error, una pausa o el cierre del proceso. El registro guarda solo el identificador de la conexión; las credenciales siguen cifradas en Keystore. Las descargas continúan por desplazamiento de bytes en SFTP, FTP (`REST`) y WebDAV (`Range`, solo con respuesta 206 exacta) y reinician el archivo en los demás servicios; nunca sustituyen un archivo local existente. Las subidas se reanudan por archivo: el nombre remoto se registra antes de escribir, de modo que solo se reemplaza el resto propio. Al mover, cada original se borra solo después de guardar su copia | Siete pruebas nuevas aprobadas con un servidor simulado de tipo nube (rutas como identificadores, escrituras no atómicas, con y sin rangos). Falta probar con servidores y cuentas reales |
| Revisión estática | `lintDebug` completado | 0 errores, 21 advertencias (ver abajo) |
| Clientes de red | Prueba con servidores SFTP, FTP y WebDAV reales; cuatro errores corregidos (tabla anterior) | 3 pruebas aprobadas en cada uno de los tres protocolos |
| RAR y 7z | Motor 7-Zip, lectura RAR clásico/RAR5 con contraseña y creación 7z cifrada; binarios para arm64, ARM de 32 bits, x86_64 y x86; fuente y script de construcción incluidos | Pruebas de host aprobadas para archivos cifrados, contraseña incorrecta y privacidad de cabeceras; ejecución del motor dentro de Android pendiente |
| Copias locales | Registro persistente, pausa y recuperación manual desde Transferencias tras un cierre; integridad de parciales y protección del original y del destino | Siete pruebas de recuperación y conflictos aprobadas |
| Bluetooth | Pantalla de equipos y cliente de navegación/transferencia OBEX | Codificación y rechazo de paquetes malformados probados; falta dispositivo OBEX real |
| Nubes | Autorización y renovación de acceso; exportación de documentos nativos Google; renombrado de carpetas S3; clientes Baidu y SugarSync | Código compilado; faltan registros OAuth y pruebas de cuentas y operaciones reales |
| Video | Imágenes, fondos, lienzo de salida y subtítulos SRT temporizados añadidos al editor/exportador | Parser SRT aprobado; exportaciones reales pendientes |
| Audio | Servicio Media3 para reproducción en segundo plano | Código compilado; falta probar controles, notificación y continuidad en Android |
| Doble panel | Arrastrar archivos entre paneles con elección de copiar o mover | Código compilado; prueba de interfaz pendiente |
| Gestos | Selección persistente en Ajustes y ejecución de deslizamientos horizontales en el explorador | Código conectado y compilado; prueba de interfaz pendiente |
| Compilación | Versión local configurable mediante `OI_BUILD_NUMBER`; número 7 utilizado para superar el APK 0.2.6 | APK debug y release generados; firma verificada con la clave del proyecto |
| Pruebas | El motor de host 7-Zip y los servidores de red reales son obligatorios; CI los prepara antes de las pruebas | 51 pruebas ejecutadas; ninguna omitida |

## Evidencia de validación

| Conjunto | Pruebas | Fallos | Omitidas |
| --- | ---: | ---: | ---: |
| Seguridad de archivos, operaciones y formatos | 21 | 0 | 0 |
| Recuperación de copias locales | 7 | 0 | 0 |
| Recuperación de transferencias de red/nube | 7 | 0 | 0 |
| Clientes reales contra servidores SFTP/FTP/WebDAV | 3 | 0 | 0 |
| Archivos RAR/7z nativos | 4 | 0 | 0 |
| Paquetes OBEX y listados de comprimidos | 4 | 0 | 0 |
| XML y límites de endpoints de nube | 3 | 0 | 0 |
| Subtítulos SRT | 2 | 0 | 0 |
| **Total** | **51** | **0** | **0** |

Los resultados XML, el informe de lint y `results.json` se conservan en `verification/2026-10-04/`; los del punto anterior siguen en `verification/2026-10-03/`. Con los servidores iniciados, el comando `testDebugUnitTest assembleRelease lintDebug assembleDebug` terminó sin errores. Las pruebas con nombres no ASCII requieren una configuración regional UTF-8 (`LANG=C.UTF-8`); con la configuración `POSIX` del contenedor fallan tres pruebas por la codificación de rutas de Java, no por el código.

Advertencias de lint (ninguna bloquea la compilación): 11 versiones de dependencias más nuevas disponibles, API objetivo 34 inferior a la última, dos `commit()` de SharedPreferences mantenidos a propósito para que el historial y las conexiones se escriban de inmediato, dos funciones de `Modifier` de Compose, `getUsableSpace`, carpeta `mipmap-anydpi-v26` innecesaria e icono sin capa monocroma. Las dos advertencias `TrustAllX509TrustManager` proceden de una clase interna de la biblioteca `commons-net`; el cliente FTPS de la aplicación usa el gestor de confianza predeterminado del sistema.

El APK tiene paquete `com.omaritoinforma.oiarchivos`, Android mínimo API 26, objetivo API 34 y compilación API 35. Su estructura ZIP y su firma APK v2 fueron comprobadas. Es un APK de desarrollo firmado con la clave debug fija del proyecto; no se presenta como una publicación estable.

SHA-256 de los APK comprobados:

```text
aac801d6b106594546c84bbb1a0dbb7eb1cdfe332c442120440ee7798d042fd8  app-debug.apk
be58a167b31d49752f399c33ba46bcb23606f307475b45ef745bf5e4053e257b  app-release.apk
```

## Trabajo que falta

1. **Transferencias de red/nube:** el registro persistente cubre descargas y subidas y ya se comprobó con servidores SFTP, FTP y WebDAV reales en el host. En Android 15 (emulador) ya se comprobó la descarga SFTP reanudada tras la muerte del proceso. Falta probar en Android la subida y la pausa manual, probar SMB, FTPS y las nubes con servidores y cuentas reales, y llevar el mismo registro al pegado de remoto a remoto, que todavía usa una copia temporal sin reanudación. Una pausa larga mantiene abierta la conexión; si el servidor la corta, la transferencia queda en error y se reanuda desde Transferencias. Si un archivo remoto cambia sin cambiar de tamaño, no se detecta al reanudar una descarga parcial (los servidores no ofrecen una suma de comprobación común).
2. **Nubes:** registrar/configurar el acceso OAuth de la aplicación y comprobar inicio de sesión, renovación, navegación, subida, descarga, creación, cambio de nombre y borrado con cuentas reales, incluidos Baidu y SugarSync.
3. **APK nuevo en Android:** verificar las transferencias de red/nube reanudables, RAR/7z nativo, arrastrar entre paneles, gestos, edición/exportación de video con imágenes/fondos/SRT y audio en segundo plano. Las pruebas previas de versiones anteriores no sustituyen esta comprobación.
4. **Red y hardware:** probar servidores HTTP/FTP y clientes con servidores reales; Bluetooth OBEX, SD/USB y root en dispositivos compatibles y autorizados.
5. **Revisión estática:** `lintDebug` ya se completa sin errores. Queda decidir sobre sus 21 advertencias, en especial actualizar dependencias y la API objetivo, lo que exige repetir las pruebas en Android.
6. **Comparación con ES:** la matriz está en [COMPARACION_ES.md](COMPARACION_ES.md), hecha a partir del APK oficial de ES 4.4.2.2.1 con firma verificada. De 79 filas: 23 comprobadas en su entorno real, 15 implementadas sin comprobar, 10 parciales y 31 que faltan. `scripts/android_features.py` comprueba 21 funciones una a una en el emulador Android 15 de CI (21/21 aprobadas) y encontró un error real ya corregido: la búsqueda avanzada mostraba siempre 0 resultados. No se declara el proyecto completo.

Shizuku, bóveda con huella, MCP e instalador propio de paquetes divididos quedan como posibles ampliaciones posteriores; no sustituyen los pendientes del mínimo.

## Cómo retomar

Revisar primero este informe y `FEATURES.md`, conservar el APK de este punto de control y continuar por la validación de integración en Android y con servidores reales. Evitar sobrescribir los cambios existentes con una base antigua.

Herramientas: JDK 17 completo, Gradle 8.11.1, SDK/plataforma Android 35 y build-tools 35.0.0. El NDK 27.2.12479018 se requiere para reconstruir los binarios Android de 7-Zip; los binarios ya están incluidos en `app/src/main/jniLibs`. `third_party/7zip/` conserva la fuente fijada y su licencia.

```sh
python3 scripts/build_archives.py --host
export OI_ARCHIVE_TEST_EXECUTABLE="$PWD/build/native-archives/--host/7zz"
RCLONE=/ruta/a/rclone python3 scripts/remote_servers.py start > servers.env
set -a; . ./servers.env; set +a
LANG=C.UTF-8 OI_BUILD_NUMBER=7 ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Para una actualización posterior, elegir un número de compilación superior al instalado. El repositorio contiene el código y los recursos propios del proyecto; un entorno nuevo también necesita las herramientas Android y las dependencias Maven. No se promete una compilación completamente sin conexión.

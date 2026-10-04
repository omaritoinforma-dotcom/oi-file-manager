# OI Archivos — informe de avance

Fecha: 4 de octubre de 2026. Versión del APK comprobado: **0.2.6**, código de versión **6**. Estado: **versión de desarrollo; equivalencia completa con ES File Explorer pendiente**.

## Objetivo y criterio de terminación

Continuar la aplicación Android iniciada por Claude hasta cubrir, como mínimo, las funciones de ES File Explorer. Después podrán añadirse opciones adicionales. Compilar un APK o añadir una pantalla no basta para declarar terminado ese mínimo: cada función debe estar conectada a la interfaz y comprobarse en su entorno real.

Este punto de control añade la pausa y la recuperación persistente de transferencias de red/nube y completa por primera vez la revisión estática `lintDebug`.

## Base de la aplicación

El proyecto conserva gestión de archivos locales y por lotes, conflictos de nombres, papelera, propiedades y hashes; pestañas, búsqueda, categorías, favoritos e historial; editor de texto; galería, audio/video y PDF; análisis de espacio y duplicados; administración y respaldo de APK; acceso SD/USB mediante SAF; conexiones FTP/FTPS/SFTP/SMB/WebDAV; nubes; servidores HTTP/FTP; cifrado de archivos y operaciones root autorizadas.

El detalle de capacidades y límites está en [FEATURES.md](FEATURES.md). La presencia de código en esas áreas no significa que todos sus dispositivos, servidores o proveedores hayan sido probados en este APK.

## Avances conservados en este punto de control

| Área | Trabajo incorporado | Comprobación actual |
| --- | --- | --- |
| Transferencias de red/nube | Registro persistente para descargas (remoto → local) y subidas (local → remoto), en copia o movimiento: pausa, cancelación y reanudación desde Transferencias tras un error, una pausa o el cierre del proceso. El registro guarda solo el identificador de la conexión; las credenciales siguen cifradas en Keystore. Las descargas continúan por desplazamiento de bytes en SFTP, FTP (`REST`) y WebDAV (`Range`, solo con respuesta 206 exacta) y reinician el archivo en los demás servicios; nunca sustituyen un archivo local existente. Las subidas se reanudan por archivo: el nombre remoto se registra antes de escribir, de modo que solo se reemplaza el resto propio. Al mover, cada original se borra solo después de guardar su copia | Siete pruebas nuevas aprobadas con un servidor simulado de tipo nube (rutas como identificadores, escrituras no atómicas, con y sin rangos). Falta probar con servidores y cuentas reales |
| Revisión estática | `lintDebug` completado | 0 errores, 21 advertencias (ver abajo) |
| RAR y 7z | Motor 7-Zip, lectura RAR clásico/RAR5 con contraseña y creación 7z cifrada; binarios para arm64, ARM de 32 bits, x86_64 y x86; fuente y script de construcción incluidos | Pruebas de host aprobadas para archivos cifrados, contraseña incorrecta y privacidad de cabeceras; ejecución del motor dentro de Android pendiente |
| Copias locales | Registro persistente, pausa y recuperación manual desde Transferencias tras un cierre; integridad de parciales y protección del original y del destino | Siete pruebas de recuperación y conflictos aprobadas |
| Bluetooth | Pantalla de equipos y cliente de navegación/transferencia OBEX | Codificación y rechazo de paquetes malformados probados; falta dispositivo OBEX real |
| Nubes | Autorización y renovación de acceso; exportación de documentos nativos Google; renombrado de carpetas S3; clientes Baidu y SugarSync | Código compilado; faltan registros OAuth y pruebas de cuentas y operaciones reales |
| Video | Imágenes, fondos, lienzo de salida y subtítulos SRT temporizados añadidos al editor/exportador | Parser SRT aprobado; exportaciones reales pendientes |
| Audio | Servicio Media3 para reproducción en segundo plano | Código compilado; falta probar controles, notificación y continuidad en Android |
| Doble panel | Arrastrar archivos entre paneles con elección de copiar o mover | Código compilado; prueba de interfaz pendiente |
| Gestos | Selección persistente en Ajustes y ejecución de deslizamientos horizontales en el explorador | Código conectado y compilado; prueba de interfaz pendiente |
| Compilación | Versión local configurable mediante `OI_BUILD_NUMBER`; número 6 utilizado para superar el APK 0.2.5 | APK debug y release generados; firma verificada con la clave del proyecto |
| Pruebas | Cargador de ejemplos RAR corregido; el motor de host es obligatorio y CI lo prepara antes de las pruebas | 48 pruebas ejecutadas; ninguna omitida |

## Evidencia de validación

| Conjunto | Pruebas | Fallos | Omitidas |
| --- | ---: | ---: | ---: |
| Seguridad de archivos, operaciones y formatos | 21 | 0 | 0 |
| Recuperación de copias locales | 7 | 0 | 0 |
| Recuperación de transferencias de red/nube | 7 | 0 | 0 |
| Archivos RAR/7z nativos | 4 | 0 | 0 |
| Paquetes OBEX y listados de comprimidos | 4 | 0 | 0 |
| XML y límites de endpoints de nube | 3 | 0 | 0 |
| Subtítulos SRT | 2 | 0 | 0 |
| **Total** | **48** | **0** | **0** |

Los resultados XML, el informe de lint y `results.json` se conservan en `verification/2026-10-04/`; los del punto anterior siguen en `verification/2026-10-03/`. El comando `testDebugUnitTest assembleRelease lintDebug assembleDebug` terminó sin errores. Las pruebas con nombres no ASCII requieren una configuración regional UTF-8 (`LANG=C.UTF-8`); con la configuración `POSIX` del contenedor fallan tres pruebas por la codificación de rutas de Java, no por el código.

Advertencias de lint (ninguna bloquea la compilación): 11 versiones de dependencias más nuevas disponibles, API objetivo 34 inferior a la última, dos `commit()` de SharedPreferences mantenidos a propósito para que el historial y las conexiones se escriban de inmediato, dos funciones de `Modifier` de Compose, `getUsableSpace`, carpeta `mipmap-anydpi-v26` innecesaria e icono sin capa monocroma. Las dos advertencias `TrustAllX509TrustManager` proceden de una clase interna de la biblioteca `commons-net`; el cliente FTPS de la aplicación usa el gestor de confianza predeterminado del sistema.

El APK tiene paquete `com.omaritoinforma.oiarchivos`, Android mínimo API 26, objetivo API 34 y compilación API 35. Su estructura ZIP y su firma APK v2 fueron comprobadas. Es un APK de desarrollo firmado con la clave debug fija del proyecto; no se presenta como una publicación estable.

SHA-256 de los APK comprobados:

```text
dbccdbe7462282682ad5bf42c9ec83398bdaee51a1c748e2447d5b90a6c42d1b  app-debug.apk
33e7fb34d7b3fceb014f7c884647df5e6565129fdd0858a96255ca5fb5625567  app-release.apk
```

## Trabajo que falta

1. **Transferencias de red/nube:** el registro persistente cubre descargas y subidas. Faltan probarlas con servidores y cuentas reales y llevar el mismo registro al pegado de remoto a remoto, que todavía usa una copia temporal sin reanudación. Una pausa larga mantiene abierta la conexión; si el servidor la corta, la transferencia queda en error y se reanuda desde Transferencias. Si un archivo remoto cambia sin cambiar de tamaño, no se detecta al reanudar una descarga parcial (los servidores no ofrecen una suma de comprobación común).
2. **Nubes:** registrar/configurar el acceso OAuth de la aplicación y comprobar inicio de sesión, renovación, navegación, subida, descarga, creación, cambio de nombre y borrado con cuentas reales, incluidos Baidu y SugarSync.
3. **APK nuevo en Android:** verificar las transferencias de red/nube reanudables, RAR/7z nativo, arrastrar entre paneles, gestos, edición/exportación de video con imágenes/fondos/SRT y audio en segundo plano. Las pruebas previas de versiones anteriores no sustituyen esta comprobación.
4. **Red y hardware:** probar servidores HTTP/FTP y clientes con servidores reales; Bluetooth OBEX, SD/USB y root en dispositivos compatibles y autorizados.
5. **Revisión estática:** `lintDebug` ya se completa sin errores. Queda decidir sobre sus 21 advertencias, en especial actualizar dependencias y la API objetivo, lo que exige repetir las pruebas en Android.
6. **Comparación final con ES:** cerrar la matriz de funciones y sus evidencias, incluida cualquier función adicional detectada durante la comparación. No hay un porcentaje fiable de terminación y no se declara el proyecto completo.

Shizuku, bóveda con huella, MCP e instalador propio de paquetes divididos quedan como posibles ampliaciones posteriores; no sustituyen los pendientes del mínimo.

## Cómo retomar

Revisar primero este informe y `FEATURES.md`, conservar el APK de este punto de control y continuar por la validación de integración en Android y con servidores reales. Evitar sobrescribir los cambios existentes con una base antigua.

Herramientas: JDK 17 completo, Gradle 8.11.1, SDK/plataforma Android 35 y build-tools 35.0.0. El NDK 27.2.12479018 se requiere para reconstruir los binarios Android de 7-Zip; los binarios ya están incluidos en `app/src/main/jniLibs`. `third_party/7zip/` conserva la fuente fijada y su licencia.

```sh
python3 scripts/build_archives.py --host
export OI_ARCHIVE_TEST_EXECUTABLE="$PWD/build/native-archives/--host/7zz"
LANG=C.UTF-8 OI_BUILD_NUMBER=6 ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Para una actualización posterior, elegir un número de compilación superior al instalado. El repositorio contiene el código y los recursos propios del proyecto; un entorno nuevo también necesita las herramientas Android y las dependencias Maven. No se promete una compilación completamente sin conexión.

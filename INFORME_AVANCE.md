# OI Archivos — informe de avance

Fecha: 3 de octubre de 2026. Versión del APK comprobado: **0.2.5**, código de versión **5**. Estado: **versión de desarrollo; equivalencia completa con ES File Explorer pendiente**.

## Objetivo y criterio de terminación

Continuar la aplicación Android iniciada por Claude hasta cubrir, como mínimo, las funciones de ES File Explorer. Después podrán añadirse opciones adicionales. Compilar un APK o añadir una pantalla no basta para declarar terminado ese mínimo: cada función debe estar conectada a la interfaz y comprobarse en su entorno real.

La petición de este punto de control es conservar en GitHub el código completo existente, este informe y el APK compilado, con un inventario claro de avances y pendientes.

## Base de la aplicación

El proyecto conserva gestión de archivos locales y por lotes, conflictos de nombres, papelera, propiedades y hashes; pestañas, búsqueda, categorías, favoritos e historial; editor de texto; galería, audio/video y PDF; análisis de espacio y duplicados; administración y respaldo de APK; acceso SD/USB mediante SAF; conexiones FTP/FTPS/SFTP/SMB/WebDAV; nubes; servidores HTTP/FTP; cifrado de archivos y operaciones root autorizadas.

El detalle de capacidades y límites está en [FEATURES.md](FEATURES.md). La presencia de código en esas áreas no significa que todos sus dispositivos, servidores o proveedores hayan sido probados en este APK.

## Avances conservados en este punto de control

| Área | Trabajo incorporado | Comprobación actual |
| --- | --- | --- |
| RAR y 7z | Motor 7-Zip, lectura RAR clásico/RAR5 con contraseña y creación 7z cifrada; binarios para arm64, ARM de 32 bits, x86_64 y x86; fuente y script de construcción incluidos | Pruebas de host aprobadas para archivos cifrados, contraseña incorrecta y privacidad de cabeceras; ejecución del motor dentro de Android pendiente |
| Copias locales | Registro persistente, pausa y recuperación manual desde Transferencias tras un cierre; integridad de parciales y protección del original y del destino | Siete pruebas de recuperación y conflictos aprobadas |
| Bluetooth | Pantalla de equipos y cliente de navegación/transferencia OBEX | Codificación y rechazo de paquetes malformados probados; falta dispositivo OBEX real |
| Nubes | Autorización y renovación de acceso; exportación de documentos nativos Google; renombrado de carpetas S3; clientes Baidu y SugarSync | Código compilado; faltan registros OAuth y pruebas de cuentas y operaciones reales |
| Video | Imágenes, fondos, lienzo de salida y subtítulos SRT temporizados añadidos al editor/exportador | Parser SRT aprobado; exportaciones reales pendientes |
| Audio | Servicio Media3 para reproducción en segundo plano | Código compilado; falta probar controles, notificación y continuidad en Android |
| Doble panel | Arrastrar archivos entre paneles con elección de copiar o mover | Código compilado; prueba de interfaz pendiente |
| Gestos | Selección persistente en Ajustes y ejecución de deslizamientos horizontales en el explorador | Código conectado y compilado; prueba de interfaz pendiente |
| Compilación | Versión local configurable mediante `OI_BUILD_NUMBER`; número 5 utilizado para superar el APK anterior 0.2.4 | APK generado y firma verificada |
| Pruebas | Cargador de ejemplos RAR corregido; el motor de host es obligatorio y CI lo prepara antes de las pruebas | 41 pruebas ejecutadas; ninguna omitida |

## Evidencia de validación

| Conjunto | Pruebas | Fallos | Omitidas |
| --- | ---: | ---: | ---: |
| Seguridad de archivos, operaciones y formatos | 21 | 0 | 0 |
| Recuperación de copias locales | 7 | 0 | 0 |
| Archivos RAR/7z nativos | 4 | 0 | 0 |
| Paquetes OBEX y listados de comprimidos | 4 | 0 | 0 |
| XML y límites de endpoints de nube | 3 | 0 | 0 |
| Subtítulos SRT | 2 | 0 | 0 |
| **Total** | **41** | **0** | **0** |

Los resultados XML y el registro del intento de compilación completo se conservan en `verification/2026-10-03/`. Las tareas `testDebugUnitTest` y `assembleDebug` terminaron, pero el comando conjunto devolvió error porque **`lintDebug` no pudo resolver dependencias**. No se afirma que lint haya aprobado.

El APK tiene paquete `com.omaritoinforma.oiarchivos`, Android mínimo API 26, objetivo API 34 y compilación API 35. Su estructura ZIP y su firma APK v2 fueron comprobadas. Es un APK de desarrollo firmado con la clave debug fija del proyecto; no se presenta como una publicación estable.

SHA-256 del APK comprobado:

```text
8819dbc6374acea54a6f016136af62dd7bd3c2796ec8753b9f032c01df5c7ec4
```

## Trabajo que falta

1. **Transferencias de red/nube:** implementar pausa y recuperación persistente tras la muerte del proceso. El registro durable actual cubre copias locales; las transferencias remotas todavía no tienen esa equivalencia.
2. **Nubes:** registrar/configurar el acceso OAuth de la aplicación y comprobar inicio de sesión, renovación, navegación, subida, descarga, creación, cambio de nombre y borrado con cuentas reales, incluidos Baidu y SugarSync.
3. **APK nuevo en Android:** verificar RAR/7z nativo, arrastrar entre paneles, gestos, edición/exportación de video con imágenes/fondos/SRT y audio en segundo plano. Las pruebas previas de versiones anteriores no sustituyen esta comprobación.
4. **Red y hardware:** probar servidores HTTP/FTP y clientes con servidores reales; Bluetooth OBEX, SD/USB y root en dispositivos compatibles y autorizados.
5. **Revisión estática:** completar `lintDebug` y corregir sus resultados. En el entorno local faltaban `kotlin-stdlib-jdk7/jdk8:1.8.21` y `com.android.tools.lint:lint-gradle:31.7.3`; la descarga necesaria desde Google fue bloqueada por la política de red. El APK y las pruebas unitarias sí se obtuvieron.
6. **Comparación final con ES:** cerrar la matriz de funciones y sus evidencias, incluida cualquier función adicional detectada durante la comparación. No hay un porcentaje fiable de terminación y no se declara el proyecto completo.

Shizuku, bóveda con huella, MCP e instalador propio de paquetes divididos quedan como posibles ampliaciones posteriores; no sustituyen los pendientes del mínimo.

## Cómo retomar

Revisar primero este informe y `FEATURES.md`, conservar el APK de este punto de control y continuar por la recuperación de transferencias remotas y la validación de integración. Evitar sobrescribir los cambios existentes con una base antigua.

Herramientas: JDK 17 completo, Gradle 8.11.1, SDK/plataforma Android 35 y build-tools 35.0.0. El NDK 27.2.12479018 se requiere para reconstruir los binarios Android de 7-Zip; los binarios ya están incluidos en `app/src/main/jniLibs`. `third_party/7zip/` conserva la fuente fijada y su licencia.

```sh
python3 scripts/build_archives.py --host
export OI_ARCHIVE_TEST_EXECUTABLE="$PWD/build/native-archives/--host/7zz"
OI_BUILD_NUMBER=5 ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Para una actualización posterior, elegir un número de compilación superior al instalado. El repositorio contiene el código y los recursos propios del proyecto; un entorno nuevo también necesita las herramientas Android y las dependencias Maven. No se promete una compilación completamente sin conexión.

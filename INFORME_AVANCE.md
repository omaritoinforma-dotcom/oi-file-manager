# OI Archivos — informe de avance

Fecha: 3 de octubre de 2026, hora de Cuba. APK de esta entrega: **0.2.10**, código **10**. Compilación local de validación: **0.2.8**, código **8**. Estado: **desarrollo; equivalencia completa con ES File Explorer pendiente**.

## Objetivo

Continuar la aplicación Android iniciada por Claude hasta cubrir, como mínimo, las funciones de ES File Explorer. Cada función debe estar conectada a la interfaz y comprobarse en su entorno real. No se asigna un porcentaje de terminación.

La rama de continuidad es `codex/es-parity`. Se recuperó desde `362018a446db1b96ffc60c51cf415cfb88fbf678`, que corrigió la respuesta 303 del servidor Wi-Fi. Su ejecución [37160817186](https://github.com/omaritoinforma-dotcom/oi-file-manager/actions/runs/37160817186), versión 0.2.7, aprobó compilación, lint y las comprobaciones existentes en Android 15. El informe de 0.2.5 describía el estado anterior a esa ejecución.

El código probado corresponde a `08ff291d47439a69dd2c8bf1c78daf8949c92c9f`. La ejecución [37168753282](https://github.com/omaritoinforma-dotcom/oi-file-manager/actions/runs/37168753282), intento 2, aprobó compilación, las 56 pruebas, lint y las 20 comprobaciones en Android 15. La APK se compiló en el intento 1 y se reutilizó para la prueba aprobada.

## Trabajo incorporado en esta entrega

| Área | Cambio | Comprobación |
| --- | --- | --- |
| Descargas de red y nube | Registro persistente para archivos, lotes y carpetas; recuperación desde Transferencias tras cierre del proceso | 15 pruebas nuevas aprobadas |
| Pausa remota | Cierra la conexión y conserva el parcial; reanudar resuelve la conexión guardada y las credenciales actuales | Pausa, reapertura y recuperación WebDAV comprobadas en Android 15 |
| Integridad | Compara todo el prefijo con el origen; verifica tamaño y revisión disponible antes y después de leer; SHA-256 antes de publicar | Parcial corrupto, original cambiado, lectura truncada y tamaño desconocido probados |
| Destino | No reemplaza archivos aparecidos después de iniciar; rechaza enlaces, rutas inseguras y ciclos; distingue nombres duplicados de nube | Pruebas de conflictos y recorrido aprobadas |
| Cierre final | Recupera el archivo publicado si el proceso murió antes de actualizar el registro, sin volver a descargarlo | Ventana entre renombrado y registro probada, incluida recuperación sin red |
| Interfaz | Descargar y copiar de red/nube a almacenamiento local usan el trabajo recuperable; Reanudar y Descartar en Transferencias | Descargar y Reanudar probados con WebDAV en Android 15; compilación y lint aprobados |
| Privacidad | Registro con ID de conexión y huella del servidor/cuenta, sin claves ni tokens; renovación de autenticación compatible | Registro e identidad probados |

La interfaz común `RemoteFs` permite recuperar descargas de todos los protocolos y proveedores implementados. Para validar el prefijo, se vuelve a leer desde el servidor: no se promete ahorrar esos bytes ni usar rangos en todos los proveedores. Si hay una revisión, fecha o ETag, se compara. Un proveedor sin versión no permite demostrar que la parte todavía no descargada permaneció inmutable.

La recuperación cubre **descargas y copias de red/nube a almacenamiento local**. Las previsualizaciones temporales, movimientos que borran el original remoto, subidas y copias entre servidores conservan el flujo anterior.

## Validación local

| Conjunto | Pruebas | Fallos | Omitidas |
| --- | ---: | ---: | ---: |
| Seguridad de archivos y formatos | 21 | 0 | 0 |
| Copias locales recuperables | 7 | 0 | 0 |
| Descargas remotas recuperables | 15 | 0 | 0 |
| RAR/7z nativos en host | 4 | 0 | 0 |
| Paquetes OBEX y listados | 4 | 0 | 0 |
| XML y endpoints de nube | 3 | 0 | 0 |
| Subtítulos SRT | 2 | 0 | 0 |
| **Total** | **56** | **0** | **0** |

`testDebugUnitTest`, `assembleDebug` y `lintDebug` terminaron correctamente. Lint: **0 errores, 0 fatales y 21 advertencias**. XML, HTML, resumen y registro en `verification/2026-10-04/`, con fecha UTC de ejecución.

El APK debug local tiene paquete `com.omaritoinforma.oiarchivos`, mínimo API 26, objetivo API 34 y compilación API 35. Estructura ZIP, versión y firma APK v2 comprobadas. Usa la clave debug fija del proyecto; es una compilación de desarrollo.

SHA-256 del APK debug local:

```text
6d3821dc26814323386ea9c5393115d6f28cba3740270d16c50ba8c22a750c0e
```

## Android 15

**20 comprobaciones aprobadas** en un emulador Android 15, API 35, x86_64. Incluyen permiso inicial, navegación, conexiones, transferencias, historial, doble panel, búsqueda avanzada, guardado del editor, PDF, galería, extracción ZIP, HTTP con autorización/subida/descarga/confinamiento y regreso a la aplicación.

La comprobación nueva descargó **32 MiB mediante WebDAV**, pausó la transferencia, cerró y abrió la aplicación, reanudó, mató el proceso durante esa recuperación y volvió a reanudar. El archivo terminó con el SHA-256 esperado y sin parciales sobrantes. Las cuatro capturas y sus XML, `checks.json`, resúmenes y estado de GitHub están en `verification/2026-10-04/ci-run-10/`.

La primera ejecución del script buscaba el control de pausa detrás del diálogo de progreso; se corrigió el recorrido. El primer intento de 0.2.10 encontró un bloqueo de Pixel Launcher antes de las pruebas. Se repitió únicamente el trabajo fallido en un emulador nuevo; el intento 2 terminó correctamente. La prueba aprobada no detectó ANR de OI Archivos.

Esta evidencia comprueba el recorrido WebDAV descrito. Las cuentas de nube, otros protocolos y el hardware indicado abajo requieren sus propias comprobaciones.

## APK entregada

`OI-Archivos-v0.2.10-dev.apk` es la APK de la ejecución aprobada: variante release, **26.517.571 bytes**, firmada con la clave de desarrollo del proyecto. ZIP, paquete, versión y firma APK v2 verificados. Conserva el mínimo API 26, objetivo API 34 y compilación API 35.

SHA-256:

```text
96121c2d2ad9631da34799aac3a43aeffc85f30be019cac49b2a92fe38e6a11f
```

Los informes de las 56 pruebas y lint de GitHub se conservan junto a la evidencia Android. Lint mantiene **21 advertencias y ningún error**.

## Trabajo pendiente

1. Recuperación persistente de **subidas, movimientos remotos y copias entre servidores**, sin duplicar archivos ya confirmados.
2. Configurar OAuth y probar autorización, renovación y operaciones con cuentas reales de cada nube, incluidos Baidu y SugarSync.
3. Comprobar RAR/7z nativo, arrastrar entre paneles, gestos, exportación de video y audio en segundo plano dentro de Android.
4. Probar FTP/FTPS/SFTP/SMB y otros proveedores con servidores reales; Bluetooth OBEX, SD/USB y root en hardware compatible y autorizado.
5. Revisar las 21 advertencias de lint y cerrar la matriz de equivalencia con ES en [FEATURES.md](FEATURES.md).

Shizuku, bóveda con huella, MCP e instalador de paquetes divididos siguen como ampliaciones; no sustituyen el mínimo.

## Cómo retomar

Continuar por subidas recuperables y validación de integración. Herramientas: JDK 17 completo, Gradle 8.11.1, plataforma Android 35 y build-tools 35.0.0. El NDK 27.2.12479018 solo es necesario para reconstruir los binarios Android de 7-Zip; binarios, fuente fijada y licencia ya están en el repositorio.

```sh
python3 scripts/build_archives.py --host
export OI_ARCHIVE_TEST_EXECUTABLE="$PWD/build/native-archives/--host/7zz"
OI_BUILD_NUMBER=11 ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Elegir un número mayor que el APK instalado para otra actualización. Un entorno nuevo necesita herramientas y dependencias Maven; no se promete una compilación completamente sin conexión.

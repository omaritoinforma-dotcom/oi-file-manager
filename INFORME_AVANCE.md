# OI Archivos — informe de avance

Fecha: 3 de octubre de 2026, hora de Cuba. Compilación local: **0.2.8**, código **8**. Estado: **desarrollo; equivalencia completa con ES File Explorer pendiente**.

## Objetivo

Continuar la aplicación Android iniciada por Claude hasta cubrir, como mínimo, las funciones de ES File Explorer. Cada función debe estar conectada a la interfaz y comprobarse en su entorno real. No se asigna un porcentaje de terminación.

La rama de continuidad es `codex/es-parity`. Se recuperó desde `362018a446db1b96ffc60c51cf415cfb88fbf678`, que corrigió la respuesta 303 del servidor Wi-Fi. Su ejecución [37160817186](https://github.com/omaritoinforma-dotcom/oi-file-manager/actions/runs/37160817186), versión 0.2.7, aprobó compilación, lint y las comprobaciones existentes en Android 15. El informe de 0.2.5 describía el estado anterior a esa ejecución.

## Trabajo incorporado en 0.2.8

| Área | Cambio | Comprobación |
| --- | --- | --- |
| Descargas de red y nube | Registro persistente para archivos, lotes y carpetas; recuperación desde Transferencias tras cierre del proceso | 15 pruebas nuevas aprobadas |
| Pausa remota | Cierra la conexión y conserva el parcial; reanudar resuelve la conexión guardada y las credenciales actuales | Servicio e interfaz integrados; prueba Android nueva preparada |
| Integridad | Compara todo el prefijo con el origen; verifica tamaño y revisión disponible antes y después de leer; SHA-256 antes de publicar | Parcial corrupto, original cambiado, lectura truncada y tamaño desconocido probados |
| Destino | No reemplaza archivos aparecidos después de iniciar; rechaza enlaces, rutas inseguras y ciclos; distingue nombres duplicados de nube | Pruebas de conflictos y recorrido aprobadas |
| Cierre final | Recupera el archivo publicado si el proceso murió antes de actualizar el registro, sin volver a descargarlo | Ventana entre renombrado y registro probada, incluida recuperación sin red |
| Interfaz | Descargar y copiar de red/nube a almacenamiento local usan el trabajo recuperable; Reanudar y Descartar en Transferencias | Compilación y lint aprobados; smoke Android ampliado |
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

`scripts/smoke_android.py` conserva las comprobaciones existentes y añade un servidor WebDAV de prueba con una descarga de 32 MiB: pausa, reapertura, muerte del proceso durante recuperación, finalización y SHA-256, con capturas y registros. **Ejecución nueva pendiente al guardar este punto de control.** El éxito previo de 0.2.7 no sustituye esta comprobación.

## Trabajo pendiente

1. Recuperación persistente de **subidas, movimientos remotos y copias entre servidores**, sin duplicar archivos ya confirmados.
2. Configurar OAuth y probar autorización, renovación y operaciones con cuentas reales de cada nube, incluidos Baidu y SugarSync.
3. Ejecutar la prueba Android nueva; ampliar comprobaciones de RAR/7z nativo, arrastrar entre paneles, gestos, exportación de video y audio en segundo plano.
4. Probar FTP/FTPS/SFTP/SMB y otros proveedores con servidores reales; Bluetooth OBEX, SD/USB y root en hardware compatible y autorizado.
5. Revisar las 21 advertencias de lint y cerrar la matriz de equivalencia con ES en [FEATURES.md](FEATURES.md).

Shizuku, bóveda con huella, MCP e instalador de paquetes divididos siguen como ampliaciones; no sustituyen el mínimo.

## Cómo retomar

Continuar por subidas recuperables y validación de integración. Herramientas: JDK 17 completo, Gradle 8.11.1, plataforma Android 35 y build-tools 35.0.0. El NDK 27.2.12479018 solo es necesario para reconstruir los binarios Android de 7-Zip; binarios, fuente fijada y licencia ya están en el repositorio.

```sh
python3 scripts/build_archives.py --host
export OI_ARCHIVE_TEST_EXECUTABLE="$PWD/build/native-archives/--host/7zz"
OI_BUILD_NUMBER=8 ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Elegir un número mayor que el APK instalado para otra actualización. Un entorno nuevo necesita herramientas y dependencias Maven; no se promete una compilación completamente sin conexión.

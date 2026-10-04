# OI Archivos

Gestor de archivos Android en Kotlin y Jetpack Compose. El objetivo del proyecto se mantiene: cubrir las funciones de ES File Explorer y añadir herramientas útiles, sin anuncios ni analítica.

**La copia local v0.2.6 genera un APK firmado, pasa 48 pruebas unitarias sin omisiones y completa `lintDebug` sin errores. Las pruebas de integración de esta versión siguen pendientes; no se declara todavía equivalencia completa con ES.** Consulta [FEATURES.md](FEATURES.md) para distinguir funciones implementadas, dependencias externas y lo que falta.

## Instalar

Descarga el APK de [Releases](https://github.com/omaritoinforma-dotcom/oi-file-manager/releases). Android 8.0 (API 26) o posterior. La firma se mantiene para actualizar sobre la v0.1 sin perder ajustes. El código de versión aumenta con cada ejecución de GitHub Actions.

Concede acceso a todos los archivos para organizar el almacenamiento compartido. Android conserva sus restricciones sobre datos privados de otras aplicaciones. USB, SD y proveedores de nube pueden abrirse con el selector de carpetas del sistema.

## La continuación incluye

- FTP, FTPS, SFTP con huella verificada, SMB 2/3 y WebDAV.
- Google Drive, Dropbox, OneDrive, Box, Yandex Disk, S3, Baidu y SugarSync; secretos protegidos por Android Keystore. El código de autorización y renovación está integrado; falta configurar los registros OAuth y validarlo con cuentas reales.
- Servidores HTTP y FTP locales, con contraseña aleatoria y una carpeta elegida explícitamente.
- Transferencias en un servicio de primer plano, cancelación e historial; copias locales pausables y recuperables desde la pantalla Transferencias tras un cierre. Recuperación persistente de red/nube pendiente.
- Crear ZIP, 7z, TAR y TAR.GZ; ZIP/7z con contraseña; lectura/extracción de ZIP, 7z, RAR clásico/RAR5 y compresores GZ/BZ2/XZ. Las pruebas de RAR cifrado se ejecutan en el host; falta validar el motor dentro de Android.
- Galería con zoom, reproductor con listas, PDF, búsqueda avanzada, análisis de espacio y duplicados, doble panel.
- Editor con números de línea, resaltado sencillo, búsqueda/reemplazo, tamaño de fuente y codificación.
- Recorte temporal, rotación, recorte central, velocidad, texto, audio, unión de videos, imágenes, fondos y subtítulos SRT; GIF corto de hasta 10 segundos. Las funciones nuevas de exportación necesitan pruebas en Android.
- Cifrado AES-256-GCM por bloques autenticados, sin cargar archivos completos en RAM; originales conservados.
- Root mediante autorización explícita de su/Magisk, accesos directos, `.nomedia`, fondo de pantalla, recepción de archivos compartidos e inspección de APK.

No se realizan peticiones de red durante la exploración local. El permiso de Internet permite únicamente las funciones que el usuario activa. No hay SDK de anuncios ni de analítica; los secretos no se incluyen en las copias de seguridad de Android.

## Compilar y verificar

Java 17, SDK Android 35:

```sh
python3 scripts/build_archives.py --host
export OI_ARCHIVE_TEST_EXECUTABLE="$PWD/build/native-archives/--host/7zz"
LANG=C.UTF-8 OI_BUILD_NUMBER=6 ./gradlew testDebugUnitTest assembleRelease lintDebug
```

Para construir una actualización local, elige un `OI_BUILD_NUMBER` mayor que la versión instalada. En CI se conserva el número de ejecución de GitHub cuando no se especifica ese valor. Las pruebas de RAR fallan si falta el motor de host, en lugar de omitirse.

Las pruebas ejercitan conflictos al mover, cancelaciones sin truncar destinos, rutas maliciosas, enlaces simbólicos, ZIP con contraseña, 7z/TAR, cifrado y alteraciones de contenido, duplicados, búsqueda y GIF decodificable.

Las ramas `codex/**` compilan y guardan el APK como artefacto. Solo `main` publica una release, después de las pruebas y de ejecutar el APK en un emulador Android 15. La prueba de integración recorre el permiso de almacenamiento, navegación, doble panel, editor y guardado real, PDF, galería, extracción de ZIP y servidor HTTP con autenticación, subida y bloqueo de rutas externas. Las conexiones con cuentas reales, los codecs del teléfono, USB y root deben validarse también en un dispositivo compatible.

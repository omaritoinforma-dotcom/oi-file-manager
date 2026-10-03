# OI Archivos

Gestor de archivos Android en Kotlin y Jetpack Compose. El objetivo del proyecto se mantiene: cubrir las funciones de ES File Explorer y añadir herramientas útiles, sin anuncios ni analítica.

**La v0.2 amplía la base local de Claude. No se declara todavía equivalencia completa con ES.** Consulta [FEATURES.md](FEATURES.md) para distinguir funciones implementadas, dependencias externas y lo que falta.

## Instalar

Descarga el APK de [Releases](https://github.com/omaritoinforma-dotcom/oi-file-manager/releases). Android 8.0 (API 26) o posterior. La firma se mantiene para actualizar sobre la v0.1 sin perder ajustes. El código de versión aumenta con cada ejecución de GitHub Actions.

Concede acceso a todos los archivos para organizar el almacenamiento compartido. Android conserva sus restricciones sobre datos privados de otras aplicaciones. USB, SD y proveedores de nube pueden abrirse con el selector de carpetas del sistema.

## La continuación incluye

- FTP, FTPS, SFTP con huella verificada, SMB 2/3 y WebDAV.
- Google Drive, Dropbox, OneDrive, Box, Yandex Disk y S3 compatibles mediante credenciales del propietario; varias cuentas con secretos protegidos por Android Keystore. El inicio de sesión OAuth integrado aún no está configurado: los tokens deben actualizarse cuando caducan.
- Servidores HTTP y FTP locales, con contraseña aleatoria y una carpeta elegida explícitamente.
- Transferencias en un servicio de primer plano, cancelación e historial. Una operación interrumpida se registra; no se promete reanudación automática tras la muerte del proceso.
- Crear ZIP, 7z, TAR y TAR.GZ; ZIP cifrado con AES; lectura/extracción de ZIP, 7z, TAR y compresores GZ/BZ2/XZ. RAR clásico tiene restricciones que se explican en FEATURES.
- Galería con zoom, reproductor con listas, PDF, búsqueda avanzada, análisis de espacio y duplicados, doble panel.
- Editor con números de línea, resaltado sencillo, búsqueda/reemplazo, tamaño de fuente y codificación.
- Recorte temporal, rotación, recorte central, velocidad, texto, audio y unión de videos; GIF corto de hasta 10 segundos.
- Cifrado AES-256-GCM por bloques autenticados, sin cargar archivos completos en RAM; originales conservados.
- Root mediante autorización explícita de su/Magisk, accesos directos, `.nomedia`, fondo de pantalla, recepción de archivos compartidos e inspección de APK.

No se realizan peticiones de red durante la exploración local. El permiso de Internet permite únicamente las funciones que el usuario activa. No hay SDK de anuncios ni de analítica; los secretos no se incluyen en las copias de seguridad de Android.

## Compilar y verificar

Java 17, SDK Android 35:

```sh
./gradlew testDebugUnitTest assembleRelease lintDebug
```

Las pruebas ejercitan conflictos al mover, cancelaciones sin truncar destinos, rutas maliciosas, enlaces simbólicos, ZIP con contraseña, 7z/TAR, cifrado y alteraciones de contenido, duplicados, búsqueda y GIF decodificable.

Las ramas `codex/**` compilan y guardan el APK como artefacto. Solo `main` publica una release, después de las pruebas. Las conexiones con cuentas reales, los codecs del teléfono, USB y root deben validarse también en un dispositivo compatible.

# Cobertura de funciones y trabajo pendiente

El mínimo solicitado sigue siendo cubrir ES File Explorer. Esta lista **no convierte código nuevo en funciones probadas en un teléfono**. La copia local genera el APK v0.2.6 con firma válida y pasa 48 pruebas unitarias, sin fallos ni pruebas omitidas. Estas pruebas incluyen RAR cifrado y la recuperación de copias locales y de transferencias de red/nube. `lintDebug` se completa con 0 errores y 21 advertencias. La nueva versión todavía necesita validación de interfaz, hardware, servidores y cuentas reales; no se declara equivalencia completa con ES.

Referencia de funciones: ficha del desarrollador de ES en [Xiaomi](https://app.mi.com/details?id=com.estrongs.android.pop&type=pad), versión 4.4.3.7, publicada el 4 de agosto de 2026. Las funciones históricas de navegación de ES se conservan en la lista original de Claude.

| Área | Implementado en la copia local | Dependencia o límite |
|---|---|---|
| Gestión local | Listar, copiar, cortar, pegar, crear, renombrar, lote, selección/rango, conflictos, papelera, propiedades y hashes | Directorios privados sujetos a permisos de Android |
| Operaciones | Progreso, cancelación, servicio con notificación e historial; pausa y registro persistente para recuperar tras un cierre o error las copias locales y las descargas y subidas de red/nube | Recuperación iniciada desde Transferencias. Las descargas continúan por bytes en SFTP, FTP y WebDAV; en los demás servicios se reinicia el archivo pendiente. El pegado de remoto a remoto todavía no es reanudable |
| Navegación | Pestañas, lista/detalles/cuadrícula, miniaturas, orden, ocultos, marcadores, categorías, historial, doble panel, arrastrar entre paneles, gestos configurables y accesos directos | Arrastrar y gestos necesitan prueba de interfaz en el APK nuevo; el lanzador debe admitir accesos fijados |
| Búsqueda | Nombre, extensión, rango de tamaño, fecha reciente y contenido de texto | 5.000 resultados; contenido de hasta 8 MB; límite de recorrido 128 niveles |
| Comprimidos | Crear ZIP/7z/TAR/TAR.GZ; ZIP y 7z con contraseña; navegar listado; extraer ZIP/APK/JAR/APKS, 7z, TAR/GZ/TGZ/BZ2/XZ | 7z cifrado probado con el motor de host; falta comprobar su ejecución dentro de Android; hasta 100.000 entradas y 64 GiB por extracción |
| RAR | Listado y extracción RAR clásico/RAR5 mediante 7-Zip; lectura cifrada y rechazo de contraseña incorrecta comprobados en pruebas de host | Motor incluido para arm64, ARM de 32 bits, x86_64 y x86; ejecución Android pendiente |
| Texto | UTF-8, UTF-16, ISO-8859-1, Windows-1252; líneas, fuente, resaltado sencillo y buscar/reemplazar | 8 MB; no sobrescribe un archivo modificado fuera del editor |
| Multimedia | Galería con zoom/paneo, anterior/siguiente; audio/video con lista, aleatorio y repetición; PDF paginado; servicio de audio en segundo plano | Servicio y controles de audio necesitan prueba en Android; codecs determinados por Android/Media3 |
| Edición de video | Recorte temporal, rotación, recorte central, velocidad, texto fijo, música, unión, imágenes, fondos y subtítulos SRT; exportar MP4 y GIF | Parser SRT probado; exportación con fondos, imágenes y subtítulos todavía necesita prueba real; GIF máx. 10 s/480 px |
| Espacio | Carpetas/archivos grandes, duplicados SHA-256, candidatos temporales/vacíos con selección manual y papelera | Hasta 200.000 archivos; no borra cachés privados de otras apps sin permiso |
| Apps y APK | Abrir, instalar APK, desinstalar, compartir, información y permisos; respaldo con splits en APKS | APKS requiere un instalador compatible para reinstalar todos los splits; no se presenta base.apk como respaldo completo |
| Almacenamiento externo | Volúmenes locales y selector SAF para SD/USB/proveedores instalados; copiar/importar, crear, renombrar y borrar documentos | El proveedor determina lectura/escritura y si ofrece carpetas; no todos los proveedores de nube ofrecen árboles SAF |
| Red | Clientes FTP/FTPS/SFTP/SMB 2/3/WebDAV: listar, subir, bajar, crear, renombrar, borrar y portapapeles remoto | Credenciales y servidor real; SFTP exige SHA256 de la clave del servidor; FTP no cifra |
| Nubes | Drive, Dropbox, OneDrive, Box, Yandex, S3, Baidu y SugarSync: código de navegación, transferencias y administración; autorización/renovación integradas; exportación de documentos Google y renombrado de carpetas S3 | Falta configurar registros OAuth de la aplicación y validar autorización, renovación y operaciones con cuentas reales |
| Servidores | HTTP desde navegador y FTP pasivo con usuario/contraseña, carpeta limitada y servicio en primer plano | Red local de confianza; sin TLS local; HTTP subidas máx. 1 GB; FTP no reanuda subidas |
| Bluetooth | Compartir archivos y explorar dispositivos mediante cliente OBEX | Codificación de paquetes probada; navegación y transferencias requieren un dispositivo que ofrezca el servicio OBEX y una prueba real |
| Root | Exploración y operaciones con su/Magisk; cambiar permisos octales | Teléfono con root y autorización explícita; enlaces/archivos especiales excluidos |
| Cifrado | AES-256-GCM por bloques, PBKDF2-SHA256, final autenticado; copia y original conservado | Formato propio versionado OIENC v2; no importa el cifrado propietario de ES |
| Integración Android | Recibir archivos/texto compartidos, `.nomedia`, ocultar/mostrar por nombre y fondo de pantalla | Capacidades del lanzador, proveedor y versión de Android |
| Privacidad | Sin anuncios ni analítica; credenciales con Keystore; backup Android desactivado | Permiso de Internet necesario para red/nube; sin tráfico durante uso local |

## Pendientes para la equivalencia completa

1. Probar con servidores y cuentas reales la pausa y recuperación de transferencias de red/nube, y extenderlas al pegado de remoto a remoto.
2. Registrar/configurar OAuth para las nubes y probar cada operación y la renovación con cuentas reales, incluidos Baidu/SugarSync.
3. Ejecutar el APK nuevo en Android para probar el motor RAR/7z, arrastrar/soltar, gestos, edición de video y audio en segundo plano.
4. Probar servidores HTTP/FTP y clientes de red con servidores reales; Bluetooth, SD/USB y root en hardware compatible.
5. Atender las advertencias de `lintDebug` (dependencias y API objetivo) y cerrar la comparación funcional con ES. Las 48 pruebas unitarias aprobadas no sustituyen estas comprobaciones.
6. Extras posteriores al mínimo: Shizuku, bóveda con huella, MCP e instalador de paquetes divididos. La pantalla root ya permite trabajar en rutas autorizadas en dispositivos compatibles.

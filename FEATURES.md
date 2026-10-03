# Cobertura de funciones y trabajo pendiente

El mínimo solicitado sigue siendo cubrir ES File Explorer. Esta lista **no convierte código nuevo en funciones probadas en un teléfono**. La compilación y las pruebas automáticas se registran en Actions; hardware, servidores y cuentas necesitan su propia validación.

Referencia de funciones: ficha del desarrollador de ES en [Xiaomi](https://app.mi.com/details?id=com.estrongs.android.pop&type=pad), versión 4.4.3.7, publicada el 4 de agosto de 2026. Las funciones históricas de navegación de ES se conservan en la lista original de Claude.

| Área | Implementado en v0.2 | Dependencia o límite |
|---|---|---|
| Gestión local | Listar, copiar, cortar, pegar, crear, renombrar, lote, selección/rango, conflictos, papelera, propiedades y hashes | Directorios privados sujetos a permisos de Android |
| Operaciones | Progreso, cancelación, servicio con notificación e historial | Sin reanudar automáticamente tras matar el proceso; sin pausa/reanudación persistente |
| Navegación | Pestañas, lista/detalles/cuadrícula, miniaturas, orden, ocultos, marcadores, categorías, historial, doble panel y accesos directos | Tamaño de celda configurable; el lanzador debe admitir accesos fijados |
| Búsqueda | Nombre, extensión, rango de tamaño, fecha reciente y contenido de texto | 5.000 resultados; contenido de hasta 8 MB; límite de recorrido 128 niveles |
| Comprimidos | Crear ZIP/7z/TAR/TAR.GZ; navegar listado; extraer ZIP/APK/JAR/APKS, 7z, TAR/GZ/TGZ/BZ2/XZ | Contraseña de creación solo ZIP; vista previa individual ZIP hasta 64 MB; hasta 100.000 entradas y 64 GiB por extracción |
| RAR | Listado y extracción mediante Junrar | RAR5 y RAR cifrado pendientes; errores visibles, sin declarar éxito |
| Texto | UTF-8, UTF-16, ISO-8859-1, Windows-1252; líneas, fuente, resaltado sencillo y buscar/reemplazar | 8 MB; no sobrescribe un archivo modificado fuera del editor |
| Multimedia | Galería con zoom/paneo, anterior/siguiente; audio/video con lista, aleatorio y repetición; PDF paginado | Codecs de Android/Media3; reproducción actual vinculada a la pantalla |
| Edición de video | Recorte temporal, rotación, recorte central, velocidad, texto fijo, música, unión; exportar MP4 y GIF | Música/video adicional se indican por ruta; GIF máx. 10 s/480 px, paleta RGB de 256 colores; subtítulos temporizados, imágenes y fondos pendientes |
| Espacio | Carpetas/archivos grandes, duplicados SHA-256, candidatos temporales/vacíos con selección manual y papelera | Hasta 200.000 archivos; no borra cachés privados de otras apps sin permiso |
| Apps y APK | Abrir, instalar APK, desinstalar, compartir, información y permisos; respaldo con splits en APKS | APKS requiere un instalador compatible para reinstalar todos los splits; no se presenta base.apk como respaldo completo |
| Almacenamiento externo | Volúmenes locales y selector SAF para SD/USB/proveedores instalados; copiar/importar, crear, renombrar y borrar documentos | El proveedor determina lectura/escritura y si ofrece carpetas; no todos los proveedores de nube ofrecen árboles SAF |
| Red | Clientes FTP/FTPS/SFTP/SMB 2/3/WebDAV: listar, subir, bajar, crear, renombrar, borrar y portapapeles remoto | Credenciales y servidor real; SFTP exige SHA256 de la clave del servidor; FTP no cifra |
| Nubes | Drive, Dropbox, OneDrive, Box, Yandex y S3: listar, transferir y administrar | Tokens/credenciales del usuario; OAuth integrado y renovación automática pendientes; Docs de Google necesitan exportación previa; S3 no renombra carpetas |
| Servidores | HTTP desde navegador y FTP pasivo con usuario/contraseña, carpeta limitada y servicio en primer plano | Red local de confianza; sin TLS local; HTTP subidas máx. 1 GB; FTP no reanuda subidas |
| Bluetooth | Compartir archivos mediante el selector Android | Exploración OBEX de otro dispositivo pendiente |
| Root | Exploración y operaciones con su/Magisk; cambiar permisos octales | Teléfono con root y autorización explícita; enlaces/archivos especiales excluidos |
| Cifrado | AES-256-GCM por bloques, PBKDF2-SHA256, final autenticado; copia y original conservado | Formato propio versionado OIENC v2; no importa el cifrado propietario de ES |
| Integración Android | Recibir archivos/texto compartidos, `.nomedia`, ocultar/mostrar por nombre y fondo de pantalla | Capacidades del lanzador, proveedor y versión de Android |
| Privacidad | Sin anuncios ni analítica; credenciales con Keystore; backup Android desactivado | Permiso de Internet necesario para red/nube; sin tráfico durante uso local |

## Pendientes para la equivalencia completa

1. RAR5 y RAR con contraseña; creación 7z con contraseña.
2. Inicio de sesión OAuth de nubes, renovación de tokens y exportación de documentos nativos de Google. Integraciones de Baidu/SugarSync quedan sin implementar hasta verificar APIs y acceso actual.
3. Exploración Bluetooth OBEX, arrastrar/soltar y gestos personalizables.
4. Edición de video con imágenes, fondos y subtítulos temporizados; reproducción de audio al salir de la pantalla.
5. Transferencias pausables/reanudables que sobrevivan a la muerte del proceso y pruebas reales de cada backend.
6. Extras posteriores al mínimo: Shizuku, bóveda con huella, MCP e instalador de paquetes divididos. La pantalla root ya permite trabajar en rutas autorizadas en dispositivos compatibles.

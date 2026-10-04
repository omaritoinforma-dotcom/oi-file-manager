# Comparación con ES File Explorer

Fecha: 4 de octubre de 2026 (actualizada con la prueba por funciones en Android: `scripts/android_features.py`, 33 de 35 aprobadas; los 2 fallos eran de la prueba y están corregidos). Esta matriz define el **mínimo** del proyecto: cada función de ES que un usuario puede usar, frente a su estado en OI Archivos.

## Fuente y método

- **APK analizado:** ES File Explorer 4.4.2.2.1 (código 15036), paquete `com.estrongs.android.pop`, SHA-256 `0f9f18684653696b1146700046f12e313c01e9fd5b794db35f6ba913cbc7c853`, obtenido de la réplica de APKPure.
- **Autenticidad:** firmado con el certificado oficial de ES (`CN=xiao, OU=estrongs, O=estrongs`, SHA-256 `08e7cf9d166f82553fc89a447adaff3bf17ab53ea79b9743c250fcdfc57fa75b`). Es el mismo certificado que el APK 4.2.8.1 enlazado desde `estrongs.com`.
- **Versión:** la tienda de Xiaomi publica la 4.4.3.7 (4 de agosto de 2026), pero no sirve el archivo sin su aplicación. La 4.4.2.2.1 es la más reciente con firma verificable que se pudo descargar; puede faltar algún cambio menor posterior.
- **Análisis estático, sin ejecutar ES** (código descompilado con jadx 1.5.1 para entender cómo funciona cada parte):
  - Manifiesto: 168 pantallas, servicios y receptores, y 43 permisos.
  - 3.283 textos de la interfaz (2.236 traducidos al español).
  - Nombres de servicios y protocolos presentes en el código compilado.
- **Se estudia el código de ES como inspiración, pero no se copia código ni recursos.** El APK no se guarda en el repositorio porque es software propietario; el hash permite volver a obtener el mismo archivo.

## Leyenda

| Estado | Significado |
| --- | --- |
| ✅ | Implementado y comprobado en su entorno real (emulador Android 15 de CI o servidor real) |
| 🟡 | Implementado y conectado a la interfaz, sin comprobar todavía en su entorno real |
| 🟠 | Parcial: falta una parte de lo que ofrece ES |
| ❌ | Falta |
| ⛔ | Fuera del mínimo: publicidad, pagos, cuentas y nube propias de ES, o promociones de otras apps de ES |

## Matriz

### Gestión de archivos

| Función de ES | OI | Nota |
| --- | --- | --- |
| Copiar, cortar, pegar, mover, renombrar, eliminar, crear | ✅ | Emulador Android 15: crear carpeta, copiar, cortar, renombrar y eliminar, verificados en el disco |
| Selección múltiple, todo, ninguno, por intervalo | 🟡 | |
| Portapapeles visible, «Pegar todo» y botón flotante cuando hay contenido | ✅ | Emulador Android 15: archivos de dos carpetas en el portapapeles, ver la lista, quitar uno y pegar el resto, verificado en el disco |
| Renombrado por lotes (número inicial, cambiar extensión) | ✅ | Emulador Android 15 (prefijo); numerar y cambiar extensión sin recorrido propio |
| Propiedades (tamaño, contenido, fechas, permisos, propietario) | ✅ | Emulador Android 15, con SHA-256 comprobado |
| Copiar ruta completa | 🟡 | |
| Abrir con | 🟡 | |
| «Abrir como» (elegir tipo) y gestión de apps predeterminadas | ❌ | |
| Fijar elementos arriba | ❌ | |
| Papelera de reciclaje (activar, restaurar, vaciar) | ✅ | Emulador Android 15 |
| Ocultar archivos y lista de ocultos protegida con contraseña | 🟠 | Mostrar los ocultos puede pedir contraseña (comprobado en el emulador); falta la lista de ocultos propia de ES |
| Accesos directos en el escritorio | 🟡 | |
| Comprimir ZIP/7z con nivel de compresión | 🟠 | Crear y cifrar sí; falta elegir el nivel |
| Extraer ZIP | ✅ | Emulador |
| Extraer 7z/RAR (motor 7-Zip, igual que ES) | ✅ | Emulador Android 15: crear y extraer 7z cifrado, extraer RAR5 cifrado |
| Cifrar y descifrar | ✅ | Emulador Android 15 |

### Navegación y vistas

| Función de ES | OI | Nota |
| --- | --- | --- |
| Ventanas o pestañas, abrir en ventana nueva | ✅ | Emulador Android 15 |
| Vistas lista, detalle y cuadrícula, ordenar, miniaturas | 🟡 | Ordenar por tamaño comprobado en el emulador; faltan vistas y miniaturas |
| Doble panel | 🟠 | La pantalla abre (emulador); falta comprobar arrastrar |
| Marcadores, historial y opciones del historial | ✅ | Emulador Android 15: el marcador sobrevive al reinicio; el historial abre |
| Categorías (imágenes, música, vídeo, documentos, APK, comprimidos) | ✅ | Emulador Android 15 (Documentos; el resto usa la misma consulta) |
| Subcategorías de ES: libros electrónicos, capturas, grabaciones, Office separado (DOC/XLS/PPT), «último abierto o creado» | 🟠 | |
| Gestos configurables | ✅ | Emulador Android 15 |
| Barra lateral personalizable y diseño de la barra de herramientas | ❌ | |
| Temas: claro y oscuro | ✅ | Emulador Android 15 (brillo de pantalla medido) |
| Temas: colores, fondo, estilo de carpetas | ❌ | |
| Idioma dentro de la app | ❌ | Hoy sigue el idioma del sistema |
| Contraseña para abrir la app | ✅ | Emulador Android 15: pide la contraseña al abrir, rechaza una incorrecta y desbloquea con la buena. Se guarda como hash PBKDF2 con sal (ES la guardaba cifrada de forma reversible) |
| Contraseña para recursos de red | ✅ | Emulador Android 15: abrir una conexión y mostrar los ocultos piden la contraseña |
| Copia y restauración de ajustes | ✅ | Emulador Android 15: se guarda el JSON (sin contraseña) y al restaurar vuelve el tema oscuro |

### Búsqueda y análisis

| Función de ES | OI | Nota |
| --- | --- | --- |
| Búsqueda avanzada (tamaño, fecha, tipo, subcarpetas, ocultos) | 🟠 | Búsqueda por contenido comprobada en el emulador, tras corregir un error que borraba los resultados; faltan el filtro de archivos del sistema y la búsqueda dentro de una categoría |
| Analizador de espacio por tipo y carpeta | ✅ | Emulador Android 15 |
| Archivos grandes, recientes, vacíos y duplicados | ✅ | Emulador Android 15 (grandes y duplicados) |
| Limpieza de basura: caché, restos de apps desinstaladas, APK obsoletos, miniaturas | ✅ | Emulador Android 15: restos de apps desinstaladas, miniaturas y APK ya instalados van a la papelera; también temporales, vacíos y la caché |
| Analizador de apps (permisos sensibles, tamaño, memoria) | 🟠 | Se muestran los permisos de cada APK; falta el análisis global |
| Informe diario de archivos nuevos y aviso de archivos nuevos | 🟠 | Aviso de archivos nuevos (Registrador) por tipo, lanzado por Android al cambiar MediaStore; falta el informe diario |
| Aviso de poco espacio | 🟡 | Revisión cada hora con umbral elegible; el aviso abre «Limpiar basura» |

### Multimedia y editores

| Función de ES | OI | Nota |
| --- | --- | --- |
| Visor de imágenes, zoom, deslizar | ✅ | Emulador |
| Recortar imagen y fijarla como fondo | 🟠 | Fondo sí; falta recortar |
| Reproductor de audio y vídeo, aleatorio y repetir | 🟡 | El audio suena en el emulador; faltan vídeo, aleatorio y repetir |
| Listas de reproducción guardadas | 🟡 | Listas M3U8: crear, añadir desde el explorador, reordenar, quitar, renombrar y reproducir; comprobación en el emulador preparada |
| Audio en segundo plano con notificación | ✅ | Emulador Android 15: sigue sonando al salir, con notificación |
| Poner como tono, alarma o notificación | ✅ | Emulador Android 15: el tono de llamada del sistema pasa a ser el archivo elegido |
| Reproducir desde red sin descargar (streaming) | ✅ | Emulador Android 15: un audio de 19 MB en un SFTP limitado a 256 KB/s suena a los 3 s. Servidor local solo en 127.0.0.1 y con clave por enlace (el de ES estaba abierto a toda la red, CVE-2019-6447) |
| Visor de PDF | ✅ | Emulador |
| Editor de texto: codificación, buscar y reemplazar, tamaño de letra | ✅ | Guardado comprobado en el emulador |
| Editor de texto: resaltado de sintaxis, sangría automática, mayúsculas y minúsculas, duplicar línea, guardado automático | 🟡 | Sangría y guardado automáticos comprobados en el emulador; resaltado sencillo. Añadidos mayúsculas/minúsculas, duplicar línea, barra de símbolos con Tab (espacios o tabulador, tamaño 2/4/8), mostrar espacios en blanco y mayúscula automática; comprobación en el emulador preparada |
| Editor de vídeo: recortar, rotar, velocidad, recorte de imagen, música, subtítulos, imágenes, fondo, intro/outro | 🟠 | Todo menos intro/outro; sin comprobar en Android |
| Unir vídeos y convertir vídeo a GIF | 🟡 | |

### Aplicaciones

| Función de ES | OI | Nota |
| --- | --- | --- |
| Lista de apps, abrir, desinstalar, compartir, información | 🟡 | Lista y búsqueda usadas en el emulador; faltan abrir, desinstalar y compartir |
| Copia de seguridad de APK (también divididos) | ✅ | Emulador Android 15 (APK simple) |
| Instalar o desinstalar varias apps a la vez | 🟡 | Varios APK desde el explorador y varias apps desde Aplicaciones (mantener pulsado), de una en una con la confirmación de Android; comprobación en el emulador preparada |
| Copia antes de desinstalar, limpiar carpetas asociadas | 🟠 | Copia del APK antes de desinstalar comprobada en el emulador. Android ya borra Android/data, media y obb de la app al desinstalarla; lo que queda se limpia desde «Limpiar basura». Las carpetas que ES asocia a cada app vienen de su base de datos en línea; adivinarlas por el nombre podría borrar datos del usuario, así que no se hace |
| Desinstalar apps del sistema (root) | ❌ | |
| Ver el contenido de un APK | ✅ | Emulador Android 15 |

### Red, nube y dispositivos

| Función de ES | OI | Nota |
| --- | --- | --- |
| FTP, SFTP, WebDAV | ✅ | Servidores reales en el host; descarga SFTP reanudada en el emulador |
| FTPS, SMB 1/2 | 🟡 | Falta un servidor de prueba |
| Buscar equipos en la red local (LAN) | ✅ | Emulador Android 15: encuentra el servidor FTP del equipo de CI (10.0.2.2:21) y abre la conexión ya rellenada. Anuncios mDNS y puertos 445, 21, 990 y 22; SFTP y FTP se confirman por su saludo |
| NFS | ❌ | |
| Google Drive, Dropbox, OneDrive, Box, Yandex, S3, Baidu, SugarSync | 🟡 | Faltan registros OAuth y cuentas reales |
| MediaFire, Flickr, Instagram, Facebook, Nutstore (坚果云), China Mobile Cloud (中国移动云盘) | ❌ | |
| Varias cuentas por servicio | 🟡 | |
| Copia automática a la nube (fotos, música, vídeo; solo con Wi-Fi; carpetas) | 🟡 | A cualquier conexión guardada (SFTP, FTP, WebDAV, SMB o nube): fotos, vídeos, música y carpetas elegidas, solo con Wi-Fi si se quiere; se lanza al aparecer archivos nuevos y cada 6 h, y sigue donde iba si se corta. Comprobación en el emulador contra el SFTP real de CI preparada |
| Subir automáticamente un archivo remoto editado en otra app | ❌ | |
| Servidor FTP para gestionar el teléfono desde el PC | 🟡 | Solo modo pasivo; faltan modo activo, elegir codificación y acceso directo |
| Servidor HTTP desde el navegador | ✅ | Emulador |
| Enviar archivos entre teléfonos (ES Sender: misma Wi-Fi, punto de acceso, código QR) | 🟠 | Misma Wi-Fi: enviar y recibir comprobados en el emulador, con aceptación y SHA-256 por archivo; faltan punto de acceso y código QR |
| Crear un punto de acceso Wi-Fi para transferir | ❌ | |
| Enviar a la TV: DLNA/UPnP y Chromecast | 🟠 | DLNA comprobado en el emulador con una TV de prueba (reproducir, pausa, detener; solo esa TV puede leer el archivo). Falta Chromecast |
| Instalar y gestionar una Android TV por ADB | ❌ | |
| Bluetooth: compartir y explorar (cliente OBEX) | 🟡 | Falta un dispositivo real |
| Bluetooth: servidor OBEX FTP (que otros exploren el teléfono) | ❌ | |
| USB OTG y tarjeta SD (SAF) | 🟡 | Falta hardware |
| Expulsar USB de forma segura y aviso al conectarlo | ❌ | |
| Gestor de descargas desde URL | ✅ | Emulador Android 15: descarga desde el equipo de CI con SHA-256 comprobado; si se corta, continúa con Range/If-Range (pruebas unitarias) |
| Centro de tareas: progreso, cancelar, historial | ✅ | Transferencias, reanudación SFTP comprobada |

### Root

| Función de ES | OI | Nota |
| --- | --- | --- |
| Explorador root | 🟡 | Falta un dispositivo con root |
| Montar /system en lectura y escritura | ❌ | |
| Editar el archivo hosts | ❌ | |

### Fuera del mínimo (⛔)

No se replican porque no son funciones de gestión de archivos o dependen de servicios propios de ES:

- Publicidad, «ver vídeo para desbloquear», ES Premium, pagos (Alipay, WeChat Pay, Stripe, Google Pay).
- Cuenta ES y nube PCS de ES.
- Tarjetas de noticias, tiempo, recomendaciones y «Earth Hour».
- Promociones de otras apps de ES: App Locker, Game Locker, Smart Charge, ES Swipe, grabador de pantalla DU Recorder.
- Avisos de «escenarios dañinos» y analítica de terceros (Umeng, Bugly).

## Resumen

| Estado | Funciones |
| --- | ---: |
| ✅ Comprobadas en su entorno real | 32 |
| 🟡 Implementadas, sin comprobar | 20 |
| 🟠 Parciales | 12 |
| ❌ Faltan | 15 |
| **Total de filas** | **79** |

**OI Archivos no cubre todavía el mínimo.** Las funciones que faltan más grandes, por valor para el usuario:

1. Enviar a la TV por Chromecast (DLNA ya está).
2. Enviar entre teléfonos por punto de acceso o código QR.
3. Idioma y temas dentro de la app; barra lateral personalizable.

Hechos y pendientes de su comprobación en el emulador (CI en curso): listas de reproducción, aviso de archivos nuevos, aviso de poco espacio, instalar y desinstalar apps por lotes, copia automática y herramientas del editor.

Los recuentos son por fila de esta matriz; una fila puede agrupar varias funciones pequeñas. Solo 32 de 79 cumplen ya el criterio de terminación (conectadas y comprobadas en su entorno real).

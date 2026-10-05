# Comparación con ES File Explorer

Fecha: 4 de octubre de 2026 (actualizada con la última pasada completa de `scripts/android_features.py` en el emulador Android 15 del CI: 47 de 57 aprobadas en la ejecución 37215783930; los 10 fallos eran de la prueba y están corregidos). Esta matriz define el **mínimo** del proyecto: cada función de ES que un usuario puede usar, frente a su estado en OI Archivos.

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
| Selección múltiple, todo, ninguno, por intervalo | 🟡 | Mantener pulsado, «Seleccionar rango», «Seleccionar todo», «Invertir selección» y cancelar; comprobación en el emulador preparada (cuenta de elementos marcados tras cada paso) |
| Portapapeles visible, «Pegar todo» y botón flotante cuando hay contenido | ✅ | Emulador Android 15: archivos de dos carpetas en el portapapeles, ver la lista, quitar uno y pegar el resto, verificado en el disco |
| Renombrado por lotes (número inicial, cambiar extensión) | ✅ | Emulador Android 15 (prefijo); numerar y cambiar extensión sin recorrido propio |
| Propiedades (tamaño, contenido, fechas, permisos, propietario) | ✅ | Emulador Android 15, con SHA-256 comprobado |
| Copiar ruta completa | 🟡 | «Más» → «Copiar ruta»; comprobación en el emulador preparada (se pega en el buscador y se compara la ruta) |
| Abrir con | ✅ | Muestra el selector de apps de Android. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| «Abrir como» (elegir tipo) y gestión de apps predeterminadas | ✅ | Abrir como texto (editor propio), imagen, audio, vídeo, PDF o cualquier tipo. Los predeterminados se cambian en la pantalla de Android (una app no puede borrar los de otras). Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Fijar elementos arriba | ✅ | Emulador Android 15 (ejecución 37215783930): fijar sube el archivo arriba con su alfiler, se conserva al reabrir la app y «Quitar de fijados» lo devuelve a su sitio. También va en la copia de ajustes y sigue al renombrar |
| Papelera de reciclaje (activar, restaurar, vaciar) | ✅ | Emulador Android 15 |
| Ocultar archivos y lista de ocultos protegida con contraseña | ✅ | «Ocultar» (pone un punto al nombre, no pisa nada) y «Dejar de ocultar»; «Lista de ocultos» en el menú lateral con lo ocultado desde la app, protegida con contraseña si se activa «Proteger los archivos ocultos». Mostrar los ocultos con contraseña está comprobado en el emulador; la lista tiene su comprobación preparada. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Accesos directos en el escritorio | ✅ | «Más opciones» → «Acceso directo en Android» fija una carpeta en el lanzador (confirma el aviso del lanzador y mira que Android guarde el acceso). Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Comprimir ZIP/7z con nivel de compresión | ✅ | Sin compresión, rápida, normal o máxima en ZIP, 7z y tar.gz; se recuerda el último. Pruebas locales de tamaños y contenido. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Extraer ZIP | ✅ | Emulador |
| Extraer 7z/RAR (motor 7-Zip, igual que ES) | ✅ | Emulador Android 15: crear y extraer 7z cifrado, extraer RAR5 cifrado |
| Cifrar y descifrar | ✅ | Emulador Android 15 |

### Navegación y vistas

| Función de ES | OI | Nota |
| --- | --- | --- |
| Ventanas o pestañas, abrir en ventana nueva | ✅ | Emulador Android 15 |
| Vistas lista, detalle y cuadrícula, ordenar, miniaturas | 🟡 | Ordenar por tamaño y miniaturas (mostrar y ocultar) comprobados en el emulador; las tres vistas con comprobación preparada (detalle por el texto «fecha · tamaño», cuadrícula por filas con varias columnas) |
| Doble panel | ✅ | La pantalla abre (emulador); arrastrar archivos de un panel al otro (copiar o mover) con. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Marcadores, historial y opciones del historial | ✅ | Emulador Android 15: el marcador sobrevive al reinicio; el historial abre |
| Categorías (imágenes, música, vídeo, documentos, APK, comprimidos) | ✅ | Emulador Android 15 (Documentos; el resto usa la misma consulta) |
| Subcategorías de ES: libros electrónicos, capturas, grabaciones, Office separado (DOC/XLS/PPT), «último abierto o creado» | ✅ | Emulador Android 15 (ejecución 37215783930): Libros, Capturas, Grabaciones, Word, Excel y PowerPoint muestran solo lo suyo (archivos sembrados en sus carpetas y reindexados), sin mezclarse; en Inicio como iconos propios |
| Gestos configurables | ✅ | Emulador Android 15 |
| Barra lateral personalizable y diseño de la barra de herramientas | ✅ | Menú lateral: ocultar y reordenar cada opción (Inicio, Ajustes y Salir siempre se ven). Distribución de Inicio (Ajustes → «Pantalla de inicio»): ocultar y reordenar sus cinco secciones (almacenamiento, categorías, accesos rápidos, marcadores y los cinco archivos nuevos, como «Mostrar nuevos archivos en la página de inicio» de ES) y ocultar cada icono de categoría o de acceso rápido. Barra de herramientas (Ajustes → «Barra de herramientas»): elegir hasta 5 botones de la barra inferior al seleccionar (copiar, cortar, eliminar, renombrar, compartir, comprimir, fijar arriba, propiedades) y su orden; lo que se quita pasa a «Más», nunca se pierde. Los tres van en la copia de ajustes, con 14 pruebas JVM y una comprobación en el emulador cada uno (preparadas). Los botones de la barra superior (buscar, vista, más) no se pueden ocultar. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Temas: claro y oscuro | ✅ | Emulador Android 15 (brillo de pantalla medido) |
| Temas: colores, fondo, estilo de carpetas | 🟡 | Color de la app (azul, rojo, verde, naranja, morado, turquesa, rosa o los del sistema), fondo negro puro con el tema oscuro, estilo de carpetas (clásica amarilla, color de la app o gris) y fondo con imagen (Ajustes → «Pantalla»: por ruta o con el selector del sistema; se guarda una copia reducida en el almacenamiento privado; la visibilidad se elige del 10 al 60 %; las pantallas de contraseña y permiso llevan fondo sólido). Todo con comprobación en el emulador preparada (color del icono, color del margen) |
| Ajustes de pantalla de ES: orientación, nombre en la barra, botón de selección, diseño grande, límite del resaltado | ✅ | Ajustes → «Pantalla»: orientación automática, vertical u horizontal; mostrar u ocultar el título de la barra; un botón «Seleccionar» que empieza a marcar sin mantener pulsado; diseño grande (interfaz un 20 % mayor). Ajustes → «Editor de texto»: tamaño máximo del archivo que se colorea (de 50 KB a 2 MB). Todo va en la copia de ajustes, validado; pruebas JVM del límite y de la copia, y (pantalla en horizontal, título, botón de selección y tamaño medido). Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Botón de ventanas (pestañas) en la barra («Mostrar el botón de Windows» de ES) | ✅ | Ajustes → «Pantalla» → «Mostrar el botón de pestañas»: un botón con el número de pestañas abiertas que lista las pestañas para cambiar, cerrarlas o abrir otra, aunque solo haya una. Va en la copia de ajustes (abre, suma una, cierra una). Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Tipos de documento elegibles («Document type setting» de ES) | 🟡 | Ajustes → «Documentos»: PDF, Word, hojas de cálculo, presentaciones, texto y libros electrónicos; al menos uno. Cambia lo que sale en la categoría Documentos. Va en la copia de ajustes; pruebas JVM y comprobación en el emulador preparada (el número de archivos baja al quitar el texto) |
| Buscador en la pantalla de inicio («Show Search engine on Homepage» de ES) | ✅ | Barra «Buscar archivos…» en Inicio que busca por nombre en todo el almacenamiento; se puede ocultar en Ajustes → «Pantalla de inicio». Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Idioma dentro de la app | 🟡 | Ajustes → Pantalla → Idioma: español, inglés o el del sistema (español si el teléfono está en español). Cambia al momento, sin reiniciar, y se guarda en la copia de ajustes. Los textos están en español en el código y un catálogo los traduce (1.280 textos, con datos como {0}); una prueba JVM comprueba que cada texto de la interfaz tiene su traducción. ES trae más idiomas; aquí, por ahora, español e inglés. Comprobación en el emulador preparada |
| Contraseña para abrir la app | ✅ | Emulador Android 15: pide la contraseña al abrir, rechaza una incorrecta y desbloquea con la buena. Se guarda como hash PBKDF2 con sal (ES la guardaba cifrada de forma reversible) |
| Contraseña para recursos de red | ✅ | Emulador Android 15: abrir una conexión y mostrar los ocultos piden la contraseña |
| Copia y restauración de ajustes | ✅ | Emulador Android 15: se guarda el JSON (sin contraseña) y al restaurar vuelve el tema oscuro |

### Búsqueda y análisis

| Función de ES | OI | Nota |
| --- | --- | --- |
| Búsqueda avanzada (tamaño, fecha, tipo, subcarpetas, ocultos) | ✅ | Nombre, extensión, tipo (carpetas, imágenes, vídeos, música, documentos, texto y código, comprimidos, APK; se pueden sumar), tamaño, fecha, texto dentro, buscar o no en las subcarpetas e incluir ocultos (con «Proteger los archivos ocultos» pide la contraseña). Dentro de una categoría (Imágenes, Word…) la búsqueda se limita a ella. Búsqueda por contenido comprobada en el emulador; el resto con pruebas JVM y. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Analizador de espacio por tipo y carpeta | ✅ | Emulador Android 15 |
| Archivos grandes, recientes, vacíos y duplicados | ✅ | Emulador Android 15 (grandes y duplicados) |
| Limpieza de basura: caché, restos de apps desinstaladas, APK obsoletos, miniaturas | ✅ | Emulador Android 15: restos de apps desinstaladas, miniaturas y APK ya instalados van a la papelera; también temporales, vacíos y la caché |
| Analizador de apps (permisos sensibles, tamaño, memoria) | ✅ | Emulador Android 15 (ejecución 37215783930): Aplicaciones → «Analizar permisos» muestra una app de prueba con cámara, ubicación y SMS (no nombra INTERNET, que no es delicado), filtra por grupo y abre la información de la app |
| Informe diario de archivos nuevos y aviso de archivos nuevos | ✅ | Emulador Android 15 (ejecución 37215783930): el aviso de archivos nuevos lo lanza Android al cambiar MediaStore y nombra el archivo; tocarlo abre su carpeta. El informe diario resume lo nuevo sin nombrar archivos y abre «Recientes» |
| Aviso de poco espacio | ✅ | Emulador Android 15 (ejecución 37215783930): con un umbral por encima del espacio libre sale el aviso «Espacio insuficiente» al momento (sin esperar a la revisión de cada hora) y al tocarlo se abre «Limpiar basura» |
| Uso del almacenamiento en la barra de estado («Mostrar tarjeta SD en la barra de estado» de ES) | ✅ | Ajustes → «Notificaciones» → «Mostrar el uso del almacenamiento»: notificación fija y silenciosa con el espacio libre, el total y el % usado del teléfono y de la tarjeta SD, que se actualiza cada hora y se quita al apagarlo; al tocarla abre «Limpiar basura». Va en la copia de ajustes; prueba JVM del texto y. Aprobado en el emulador Android 15 (ejecución 37234077192). |

### Multimedia y editores

| Función de ES | OI | Nota |
| --- | --- | --- |
| Visor de imágenes, zoom, deslizar | ✅ | Emulador |
| Recortar imagen y fijarla como fondo | 🟡 | Fondo de pantalla desde el menú «Más». Editor de imagen (visor → «Editar imagen», o «Más» → «Recortar o girar imagen»): recuadro que se arrastra por el centro o por las esquinas, proporciones libre, 1:1, 4:3, 3:4, 16:9 y 9:16, girar a izquierda o derecha y voltear; se guarda como copia («foto (editada).jpg») o reemplazando la original con confirmación, siempre vía un temporal, y respeta la orientación EXIF. Las cuentas del recuadro tienen pruebas JVM; comprobación en el emulador con píxeles preparada |
| Reproductor de audio y vídeo, aleatorio y repetir | ✅ | El audio suena en el emulador y sigue en segundo plano; «Repetir uno», «Repetir todos», sin repetición y «Aleatorio» con comprobación preparada; el vídeo se reproduce al abrirlo desde el editor de vídeo. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Listas de reproducción guardadas | ✅ | Emulador Android 15 (ejecución 37215783930): crear una lista, añadir dos audios desde el explorador, reordenar, quitar y reproducir; la lista sigue al reabrir la app. Formato M3U8 |
| Audio en segundo plano con notificación | ✅ | Emulador Android 15: sigue sonando al salir, con notificación |
| Poner como tono, alarma o notificación | ✅ | Emulador Android 15: el tono de llamada del sistema pasa a ser el archivo elegido |
| Reproducir desde red sin descargar (streaming) | ✅ | Emulador Android 15: un audio de 19 MB en un SFTP limitado a 256 KB/s suena a los 3 s. Servidor local solo en 127.0.0.1 y con clave por enlace (el de ES estaba abierto a toda la red, CVE-2019-6447) |
| Visor de PDF | ✅ | Emulador |
| Editor de texto: codificación, buscar y reemplazar, tamaño de letra | ✅ | Guardado comprobado en el emulador |
| Editor de texto: resaltado de sintaxis, sangría automática, mayúsculas y minúsculas, duplicar línea, guardado automático | ✅ | Sangría y guardado automáticos comprobados en el emulador; resaltado sencillo. Añadidos mayúsculas/minúsculas, duplicar línea, barra de símbolos con Tab (espacios o tabulador, tamaño 2/4/8), mostrar espacios en blanco y mayúscula automática. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Editor de vídeo: recortar, rotar, velocidad, recorte de imagen, música, subtítulos, imágenes, fondo, intro/outro | 🟡 | Todo, también intro y outro: una foto recortada al centro para llenar el cuadro, un texto sobre un color o ambos, de 2, 3 o 5 s, y el texto, los subtítulos y la imagen superpuesta no tapan la intro ni el outro. Comprobación en el emulador preparada con vídeos reales: grabaciones de screenrecord recortadas de 1 s a 3 s, giradas 90° y a doble velocidad (duración y tamaño del MP4), y un vídeo con sonido con intro azul, outro con foto, texto y subtítulos, comprobado decodificando sus fotogramas y su audio con PyAV. Música, imagen superpuesta y fondo sin comprobar |
| Unir vídeos y convertir vídeo a GIF | 🟡 | Unir (suma de duraciones) y GIF (cabecera, tamaño y fotogramas) con comprobación en el emulador preparada; el codificador de GIF ya tenía pruebas JVM con el lector de GIF del JDK |

### Aplicaciones

| Función de ES | OI | Nota |
| --- | --- | --- |
| Lista de apps, abrir, desinstalar, compartir, información | 🟡 | Lista, búsqueda, respaldar APK y desinstalar (con copia previa y por lotes) usados en el emulador; información de la app, compartir APK y abrir otra app con comprobación preparada |
| Copia de seguridad de APK (también divididos) | ✅ | Emulador Android 15 (APK simple) |
| Instalar o desinstalar varias apps a la vez | ✅ | Varios APK desde el explorador y varias apps desde Aplicaciones (mantener pulsado), de una en una con la confirmación de Android. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Aviso de los permisos de una app recién instalada («Notificarme los permisos de aplicaciones» de ES) | ✅ | Al terminar de instalar un APK desde OI Archivos, una notificación dice qué permisos delicados pide (ubicación, cámara, micrófono…) y al tocarla abre «Analizar permisos»; se desactiva en Ajustes → «Aplicaciones» y va en la copia de ajustes. Solo cubre lo instalado desde la app (Android no deja avisar de instalaciones ajenas sin un receptor que ya no se permite). Pruebas JVM del texto y. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Copia antes de desinstalar, limpiar carpetas asociadas | ✅ | Copia del APK antes de desinstalar comprobada en el emulador. Limpiar carpetas: tras desinstalar una app desde OI Archivos, se proponen las carpetas de la raíz del almacenamiento con el nombre exacto de la app o de su paquete (nunca las de Android ni nombres genéricos); el usuario elige cuáles y van a la papelera. ES las saca de su base de datos en línea; aquí solo se compara el nombre, por eso es conservador. Android ya borra Android/data, media y obb. Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Desinstalar apps del sistema (root) | ❌ | |
| Ver el contenido de un APK | ✅ | Emulador Android 15 |

### Red, nube y dispositivos

| Función de ES | OI | Nota |
| --- | --- | --- |
| FTP, SFTP, WebDAV | ✅ | Servidores reales en el host; descarga SFTP reanudada en el emulador |
| FTPS, SMB 1/2 | ✅ | FTPS explícito e implícito (puerto 990, que antes se trataba como explícito y fallaba) probados con servidores reales, con certificado validado, nombre comprobado y reanudación. SMB 2/3 con firma obligatoria probado contra Samba: listar, subir, bajar, renombrar y borrar. SMB 1 no se ofrece a propósito (es inseguro y Android ya no lo admite) |
| Buscar equipos en la red local (LAN) | ✅ | Emulador Android 15: encuentra el servidor FTP del equipo de CI (10.0.2.2:21) y abre la conexión ya rellenada. Anuncios mDNS y puertos 445, 21, 990 y 22; SFTP y FTP se confirman por su saludo |
| NFS | 🟡 | Cliente NFS versión 3 (biblioteca nfs-client de Dell EMC, Apache 2.0): listar, leer, leer desde un desplazamiento (reanudar), escribir de forma atómica (temporal y renombrado), renombrar, borrar y crear carpetas. Probado con un servidor NFS real (nfs-ganesha, que el CI inicia) en 3 pruebas JVM, entre ellas que una exportación inexistente se rechace; usuario y grupo numéricos (uid:gid, por omisión «nobody»). Sin cifrado, como el propio NFS 3. Comprobación en Android contra el servidor del CI preparada |
| Google Drive, Dropbox, OneDrive, Box, Yandex, S3, Baidu, SugarSync | 🟡 | Faltan registros OAuth y cuentas reales |
| MediaFire, Flickr, Instagram, Facebook, Nutstore (坚果云), China Mobile Cloud (中国移动云盘) | ❌ | |
| Varias cuentas por servicio | 🟡 | |
| Copia automática a la nube (fotos, música, vídeo; solo con Wi-Fi; carpetas) | ✅ | Emulador Android 15 (ejecución 37215783930) contra el SFTP real del CI: «Copiar ahora» sube la foto y una foto nueva se sube sola (trabajo lanzado por MediaStore); se comprueba en el disco del servidor. Sirve cualquier conexión guardada (SFTP, FTP, WebDAV, SMB o nube), solo con Wi-Fi si se quiere, y sigue donde iba si se corta |
| Subir automáticamente un archivo remoto editado en otra app | ✅ | Al abrir un archivo de un servidor se baja una copia y, si cambia (en otra app o en el editor propio), se sube sola al volver a OI Archivos; si el servidor también cambió, se pregunta (sustituir, subir como copia o descartar). Subida segura: nunca se pierde el original aunque falle a medias. Pruebas locales con un servidor en memoria; en el emulador contra el SFTP de CI con el editor propio (otras apps usan el mismo mecanismo, no se pueden manejar desde la prueba). Aprobado en el emulador Android 15 (ejecución 37234077192). |
| Servidor FTP para gestionar el teléfono desde el PC | 🟡 | Modo pasivo (PASV, EPSV) y activo (PORT, EPRT, solo hacia la propia dirección del cliente y puertos desde 1024, contra el ataque «FTP bounce»), puerto fijo opcional (si está ocupado lo dice) y codificación de los nombres (UTF-8, ISO-8859-1, Windows-1252, GBK, Shift_JIS; UTF8 solo se anuncia si de verdad se usa). Contraseña fija opcional (de 8 a 64 caracteres; si no, una nueva en cada inicio) y «Detener el servidor al salir de la app». Todo va en la copia de ajustes salvo la contraseña. 10 pruebas JVM con un cliente FTP real (commons-net) y comprobaciones en el emulador preparadas (puerto, contraseña fija y rechazo de la incorrecta, FEAT, OPTS y PORT por el socket; el servicio se detiene al salir). Falta el acceso directo para arrancarlo |
| Servidor HTTP desde el navegador | ✅ | Emulador |
| Enviar archivos entre teléfonos (ES Sender: misma Wi-Fi, punto de acceso, código QR) | 🟠 | Misma Wi-Fi: enviar y recibir comprobados en el emulador, con aceptación y SHA-256 por archivo. Código QR: el que recibe muestra un QR y el otro lo lee con su cámara, lo que abre OI Archivos listo para enviar (solo redes locales y con confirmación); comprobación en el emulador preparada. Falta el punto de acceso Wi-Fi, que el emulador no puede probar |
| Crear un punto de acceso Wi-Fi para transferir | ❌ | |
| Enviar a la TV: DLNA/UPnP y Chromecast | 🟡 | DLNA comprobado en el emulador con una TV de prueba (reproducir, pausa, detener; solo esa TV puede leer el archivo). Chromecast con el protocolo abierto CASTV2, sin el SDK de Google: se busca por mDNS o por su IP, se abre el reproductor por omisión y se le pasa el enlace, que solo puede leer ese Chromecast; reproducir, pausa, posición y detener. Probado en la JVM contra un receptor de prueba y, con TLS, contra el receptor en Python que usa el emulador; comprobación en el emulador preparada. Falta probarlo con un Chromecast físico |
| Instalar y gestionar una Android TV por ADB | 🟡 | Cliente ADB propio por red (puerto 5555), sin binarios externos: clave RSA de la app, la TV pregunta «¿Permitir la depuración?» la primera vez y luego basta la firma. Instalar uno o varios APK desde el explorador (por streaming en Android 7+, con copia sync y pm install en los viejos), listar, abrir y desinstalar apps y mando a distancia (cruceta, Aceptar, Atrás, Inicio, volumen, encendido). Probado en la JVM contra un adbd de prueba que verifica la firma; comprobación en el emulador preparada contra una TV falsa que verifica la firma RSA en Python. Falta probarlo con una Android TV física |
| Bluetooth: compartir y explorar (cliente OBEX) | 🟡 | Falta un dispositivo real |
| Bluetooth: servidor OBEX FTP (que otros exploren el teléfono) | ❌ | |
| USB OTG y tarjeta SD (SAF) | 🟡 | Falta hardware |
| Expulsar USB de forma segura y aviso al conectarlo | 🟡 | Al montar una memoria USB o tarjeta SD sale un aviso con «Abrir» y «Expulsar», que sigue mientras está conectada, y otro si se quita sin expulsar (se puede desactivar en Ajustes). La unidad aparece en Inicio con su nombre y un botón de expulsar. Android no deja a una app desmontar unidades sin root: «Expulsar» comprueba que no queda ninguna copia en curso, sale de la unidad y abre Ajustes → Almacenamiento para pulsar «Expulsar». Comprobación en el emulador preparada con un disco virtual de vold |
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

| Estado | Filas |
| --- | ---: |
| ✅ Comprobadas en su entorno real | 58 |
| 🟡 Implementadas; su comprobación en el emulador está escrita y espera su pasada | 20 |
| 🟠 Parciales | 1 |
| ❌ Faltan | 6 |
| **Total de filas** | **85** |

**OI Archivos no cubre todavía el mínimo.** Lo que falta, por valor para el usuario:

1. Enviar entre teléfonos por punto de acceso Wi-Fi (misma Wi-Fi y código QR ya están).
2. Lo que exige hardware, root o cuentas que el CI no tiene: servidor OBEX, punto de acceso propio, funciones root y nubes minoritarias.

Las filas en 🟡 pasan a ✅ cuando las aprueba una pasada del emulador en el CI.

Los recuentos son por fila de esta matriz; una fila puede agrupar varias funciones pequeñas. Solo 58 de 85 cumplen ya el criterio de terminación (conectadas y comprobadas en su entorno real).

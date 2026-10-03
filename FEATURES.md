# Funciones de OI Archivos

Comparación con todo lo que ofrece ES File Explorer hoy, más extras propios.

✅ = incluido en la versión actual · 🟡 = parcial · 🔜 = planeado (fase indicada)

## Gestión de archivos

| Función | Estado |
|---|---|
| Explorar almacenamiento interno, tarjeta SD y raíz del sistema `/` | ✅ |
| Copiar, cortar, pegar, renombrar, eliminar | ✅ |
| Crear carpetas y archivos vacíos | ✅ |
| Selección múltiple (toque largo), seleccionar todo, invertir | ✅ |
| Selección por rango (marca el primero y el último) | ✅ |
| Conflictos al pegar: conservar ambos (renombrar), reemplazar/combinar carpetas, omitir | ✅ |
| Progreso con velocidad, archivos, bytes y tiempo restante; cancelable | ✅ |
| Renombrado en lote (buscar/reemplazar, prefijo, sufijo, numeración, extensión) con vista previa | ✅ |
| Papelera de reciclaje (restaurar o eliminar definitivamente) | ✅ |
| Propiedades: tamaño exacto en bytes, nº de archivos y carpetas, permisos, tipo | ✅ |
| Hashes MD5, SHA-1 y SHA-256 | ✅ |
| Copiar ruta al portapapeles | ✅ |
| Compartir uno o varios archivos (incluye enviar por Bluetooth desde el menú de compartir) | ✅ |
| Abrir con… (elegir app) | ✅ |
| Transferencias que siguen con la app cerrada (servicio con notificación) | 🔜 Fase 2 |
| Arrastrar y soltar | 🔜 Fase 4 |
| Gestos personalizables | 🔜 Fase 4 |

## Navegación y vista

| Función | Estado |
|---|---|
| Pestañas (varias ventanas) | ✅ |
| Barra de ruta tocable | ✅ |
| Vistas: lista, lista detallada y cuadrícula | ✅ |
| Miniaturas de imágenes, videos e íconos reales de APK | ✅ |
| Ordenar por nombre (orden natural), fecha, tamaño o tipo; ascendente/descendente | ✅ |
| Mostrar/ocultar archivos ocultos | ✅ |
| Marcadores | ✅ |
| Categorías: imágenes, música, videos, documentos, APK, comprimidos, recientes | ✅ |
| Recuerda la posición de la lista al volver atrás | ✅ |
| Temas claro, oscuro y del sistema; Material You | ✅ |
| Accesos directos a carpetas en la pantalla de inicio de Android | 🔜 Fase 2 |
| Tamaños de ícono ajustables y temas personalizados | 🔜 Fase 2 |
| Historial de carpetas visitadas | 🔜 Fase 2 |

## Búsqueda

| Función | Estado |
|---|---|
| Búsqueda por nombre en la carpeta actual y subcarpetas | ✅ |
| Seleccionar resultados y operar con ellos; "Abrir ubicación" | ✅ |
| Filtros por tipo, tamaño y fecha | 🔜 Fase 2 |
| Búsqueda dentro del contenido de archivos de texto | 🔜 Fase 2 |

## Comprimidos

| Función | Estado |
|---|---|
| Crear ZIP | ✅ |
| Extraer ZIP / APK / JAR (con protección contra rutas maliciosas) | ✅ |
| Explorar el ZIP sin extraerlo | 🔜 Fase 2 |
| Extraer RAR, 7z, TAR, GZ | 🔜 Fase 2 |
| ZIP con contraseña | 🔜 Fase 4 |

## Herramientas integradas

| Función | Estado |
|---|---|
| Editor de texto UTF-8 (aviso de cambios sin guardar) | ✅ |
| Gestor de aplicaciones: abrir, respaldar APK, compartir, información, desinstalar | ✅ |
| Instalar APK desde el explorador | ✅ |
| Visor de imágenes con zoom y deslizamiento | 🔜 Fase 2 |
| Reproductor de música y video con listas | 🔜 Fase 2 |
| Analizador de espacio (qué ocupa más) | 🔜 Fase 2 |
| Limpiador de archivos basura y caché | 🔜 Fase 2 |
| Guardar en OI Archivos lo que se comparte desde otras apps | 🔜 Fase 2 |

## Red y nube

| Función | Estado |
|---|---|
| Servidor FTP (acceder al teléfono desde la PC) | 🔜 Fase 3 |
| Clientes FTP, FTPS, SFTP, SMB (carpetas de Windows) y WebDAV | 🔜 Fase 3 |
| Google Drive, Dropbox, OneDrive | 🔜 Fase 3 |
| Bluetooth: explorar archivos de otro dispositivo | 🔜 Fase 3 |

## Root y seguridad

| Función | Estado |
|---|---|
| Explorador root (con Magisk) | 🔜 Fase 4 |
| Acceso a `Android/data` y `Android/obb` mediante Shizuku | 🔜 Fase 4 |
| Cifrado de archivos con AES-256 (en lugar del formato propio de ES) | 🔜 Fase 4 |

## Extras que ES no tiene

| Función | Estado |
|---|---|
| Sin anuncios, sin rastreo, sin permiso de internet en el núcleo | ✅ |
| Papelera propia con ruta original y fecha | ✅ |
| SHA-256 además de MD5/SHA-1 | ✅ |
| Doble panel (dos carpetas lado a lado) | 🔜 Fase 2 |
| Buscador de archivos duplicados | 🔜 Fase 2 |
| Vista previa de PDF | 🔜 Fase 2 |
| Editor con resaltado de sintaxis, números de línea y buscar/reemplazar | 🔜 Fase 2 |
| Ver manifiesto y permisos de un APK antes de instalarlo | 🔜 Fase 2 |
| Transferencia por Wi‑Fi desde el navegador de la PC (sin instalar nada) | 🔜 Fase 3 |
| Bóveda privada cifrada con huella digital | 🔜 Fase 4 |
| Servidor MCP para que Claude pueda explorar y organizar los archivos | 🔜 Fase 4 |

## Fases

1. **Fase 1 (actual):** núcleo del explorador, operaciones, papelera, ZIP, categorías, editor, apps.
2. **Fase 2:** multimedia (visor, reproductor), analizador de espacio, filtros de búsqueda, más formatos comprimidos, servicio en segundo plano, doble panel.
3. **Fase 3:** red (servidor FTP, clientes LAN, nube, transferencia Wi‑Fi). Requiere agregar el permiso de internet solo para esas funciones.
4. **Fase 4:** root, Shizuku, cifrado, bóveda, MCP y extras.

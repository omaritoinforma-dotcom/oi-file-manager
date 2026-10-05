# Reporte: lo que falta y cambios propuestos

Fecha: 5 de octubre de 2026 · Rama `ccr-cc9d8418-yd5iu0` · PR #1

## Estado de la comparación con ES File Explorer

Según COMPARACION_ES.md, de 85 filas:

| Estado | Filas | Qué significa |
| --- | ---: | --- |
| ✅ | 69 | Implementado y comprobado en el emulador Android 15 del CI o contra un servidor real |
| 🟡 | 15 | Implementado y conectado a la interfaz; su comprobación ya está escrita y espera la próxima pasada del emulador, o necesita hardware real |
| 🟠 | 0 | — |
| ❌ | 1 | Nubes minoritarias |

La versión entregada compila sin errores, y `lintDebug` no tiene errores. Está firmada con la clave propia nueva: huella SHA-256 `9D:5B:0C:3E:D5:33:BB:AF:D8:2A:0E:43:11:DB:31:3C:E2:1E:E6:81:54:3E:A9:3C:42:7A:E6:B2:33:A1:F2:AA`.

Como la firma cambió, hay que desinstalar las versiones anteriores, firmadas con la clave de depuración, antes de instalar esta.

## Hecho en esta última tanda (pendiente de la pasada del emulador)

Cada función tiene pruebas JVM y su comprobación en el emulador ya escrita.

| Función | Cómo se prueba |
| --- | --- |
| Android TV por ADB: instalar APK, listar, abrir y quitar apps, mando a distancia | Cliente ADB propio con clave RSA. Se prueba contra una TV falsa que verifica la firma |
| Aviso al conectar una memoria USB o tarjeta SD, y botón de expulsar | Android no deja desmontar a las apps: «Expulsar» comprueba que no haya copias en curso y abre Ajustes → Almacenamiento. Se prueba con un disco virtual del emulador |
| Root: quitar y devolver apps del sistema, montar el sistema en lectura y escritura, editar el hosts | Se prueba con un `su` de prueba (scripts/test_root) que imita a Magisk |
| Root: el explorador lista cada carpeta con una sola orden | Antes eran tres por elemento |
| Envío entre teléfonos por punto de acceso propio (sin router) | El emulador no puede crear puntos de acceso (diagnóstico de la ejecución 37257397887). Se prueba la parte del que envía |
| Servidor OBEX FTP por Bluetooth: otros equipos exploran la carpeta compartida | El cliente y el servidor se prueban entre sí en la JVM |
| Arreglo de las pruebas del emulador | Editor de vídeo y Apps |

## Lo que falta

1. **Nubes minoritarias** (MediaFire, Flickr, Instagram, Facebook, Nutstore, China Mobile Cloud): es la única fila ❌. Hace falta registrar la app en cada servicio para obtener las claves OAuth, y tener cuentas reales para probarlas. Instagram y Facebook ya no ofrecen API para leer archivos del usuario, así que probablemente no se puedan hacer.
2. **Los 5 fallos de la última pasada del emulador** (37331516858, 84 de 89): DLNA y Chromecast (la «TV» del CI no recibe el archivo por la redirección del emulador; aprobaban en 521f954), USB/SD (la prueba no encontraba la notificación en la cortina), root (la app no veía el su de prueba) y GIF (lectura con `adb pull`). Las pruebas ya están ajustadas y guardan diagnóstico; falta su pasada.
3. **Comprobar en hardware real** lo que el emulador no tiene:
   - punto de acceso Wi-Fi;
   - otro equipo Bluetooth, para el cliente y el servidor OBEX;
   - un Chromecast y una Android TV físicos;
   - un teléfono con Magisk;
   - una memoria USB OTG.
4. **Firma en el CI:** confirmar en el registro del próximo build de main que el APK sale con la huella `9D:5B:0C:3E…`. Los secretos ya están creados.

## Cambios propuestos

1. **Publicar versiones desde el CI:** al etiquetar `v*`, que el CI compile, firme y suba el APK a una *Release* de GitHub. Así el enlace de descarga sale siempre del CI y no de una compilación local.
2. **Probar root con Magisk real:** una imagen de emulador con Magisk en un job aparte (por ejemplo, rootAVD), para pasar las filas de root de 🟡 a ✅ sin el `su` de prueba.
3. **Probar Bluetooth entre dos emuladores:** dos emuladores con Bluetooth virtual (netsim) en el mismo job, para probar el cliente y el servidor OBEX extremo a extremo.
4. **Actualizar dependencias:** lint avisa de 28 versiones nuevas, entre ellas core-ktx 1.19 y la API objetivo 35. Conviene subirlas en un PR propio, con una pasada completa del emulador.
5. **Separar el PR #1:** ya es muy grande. Propongo fusionarlo cuando el emulador quede en verde y seguir con PR pequeños por función.
6. **Seguridad de cuentas:**
   - revocar el token de GitHub y cambiar la contraseña SFTP que se pegaron en el chat;
   - guardar la clave de firma (firma-oi-archivos.zip) fuera del teléfono y del repositorio: sin ella no se pueden publicar actualizaciones que se instalen encima.

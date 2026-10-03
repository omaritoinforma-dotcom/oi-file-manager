# OI Archivos

Explorador de archivos para Android, de uso personal. La meta: **todas las funciones de ES File Explorer y más**, sin anuncios, sin rastreo y sin el historial de problemas de seguridad de ES.

- Kotlin + Jetpack Compose (Material 3, colores dinámicos en Android 12+)
- Android 8.0 (API 26) o superior
- **Sin permiso de internet** en esta versión: nada sale del teléfono
- Cada `push` a `main` compila el APK en GitHub Actions y lo publica en **Releases**

## Instalar

1. Abre la pestaña **Releases** del repositorio y descarga `OI-Archivos-v0.1.N.apk`.
2. Instálalo (Android pedirá permitir "instalar apps desconocidas").
3. Al abrirla, concede **"Acceso a todos los archivos"**.

Todas las versiones se firman con la misma llave (`app/debug.keystore`), así que cada APK nuevo se instala encima del anterior sin perder ajustes.

## Qué incluye la v0.1

Ver la lista completa, con lo que falta y en qué fase llega, en [FEATURES.md](FEATURES.md).

## Estructura

```
app/src/main/java/com/omaritoinforma/oiarchivos/
├── data/        Operaciones: listar, copiar/mover, papelera, ZIP, categorías, apps
├── util/        Formato, tipos de archivo, rutas, abrir/compartir
└── ui/          ViewModel, pantallas (inicio, explorador, editor, apps, papelera, ajustes)
```

# Validación al detener el desarrollo

Fecha: 4 de octubre de 2026 (UTC). Rama `codex/remote-transfer-safety`, basada en `6f53398`. Se conserva el código tal como quedó al recibir la orden de parar.

```sh
OI_BUILD_NUMBER=11 ./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --max-workers=2
```

Resultado: **BUILD FAILED**, salida 1, por seis aserciones fallidas en la suite unitaria. Duración: 2 min 8 s. Registro completo en [gradle-final.log](gradle-final.log); resumen legible por máquinas en [summary.json](summary.json); resultados detallados en [unit-tests/](unit-tests/).

| Comprobación | Resultado |
| --- | --- |
| Kotlin de producción | Compila; advertencias de iconos Compose deprecados |
| Kotlin de pruebas unitarias | Compila |
| Pruebas unitarias | 141 ejecutadas, 135 aprobadas, 6 fallidas, 0 errores, 0 omitidas |
| Kotlin de instrumentación Android | Compila; advertencia de `File?` en limpieza SAF y API `Movie` deprecada |
| APK de instrumentación | Ensamblado no completado al fallar la suite |
| Instrumentación / smoke en Android | No ejecutados para el snapshot nuevo |
| Lint del snapshot | No llegó a ejecutarse en el comando final |
| `git diff --check` | Sin errores |
| `python3 -m py_compile scripts/smoke_android.py scripts/run_android_tests.py` | Correcto |
| CI/release de este checkpoint | No iniciada; commit de checkpoint con `[skip ci]`, sin merge a main ni publicación |

## Suite unitaria final

| Clase | Pruebas | Fallos |
| --- | ---: | ---: |
| CloudConditionalDeleteTest | 4 | 0 |
| CloudPaginationTest | 13 | 0 |
| CloudUploadPublicationTest | 7 | 0 |
| CloudXmlTest | 3 | 0 |
| DocumentTransactionsTest | 20 | 0 |
| DocumentTransfersTest | 10 | 0 |
| DurableCopyTest | 7 | 0 |
| DurableDownloadTest | 15 | 0 |
| DurableRemoteTransferTest | 10 | 4 |
| DurableUploadTest | 19 | 2 |
| FileSafetyTest | 21 | 0 |
| GifWriterTest | 2 | 0 |
| NativeArchiveTest (motor de host) | 4 | 0 |
| ProtocolTest | 4 | 0 |
| SubtitleTest | 2 | 0 |
| **Total** | **141** | **6** |

## Fallos conservados, sin ocultarlos ni omitir pruebas

Los cuatro fallos de `DurableRemoteTransferTest` son:

- `publishedUploadRecoversDeathBeforeChildCompletionWithoutDuplicateWrites`: esperaba un borrado del origen; no ocurrió.
- `deletionIntentRecoversDeathAfterSourceDeleteWithoutSecondDelete`: esperaba una interrupción tras borrar; no ocurrió ese borrado.
- `localMoveDeletesOnlyAfterDestinationHasCommitted`: esperaba un borrado del origen; no ocurrió.
- `completedChildrenSurviveParentFailureAndAreVerifiedInsteadOfResent`: esperaba que el origen ya estuviera eliminado al recuperar.

Los dos de `DurableUploadTest` son:

- `nestedFoldersAndOverlappingSourcesAreUploadedOnce`: la lista conserva el marcador `.oi-upload-owner-*` además del hijo.
- `aFolderPublishedBeforeJournalSaveIsRecognizedByItsMarker`: la carpeta publicada conserva ese marcador además del archivo.

La implementación acaba de sustituir borrados ordinarios por `deleteIfUnchanged`/`deleteEmptyDirectory`. Los servidores en memoria de estas pruebas no implementan las operaciones nuevas: el valor por defecto conserva archivos y marcadores. Esto explica las aserciones observadas, pero no sustituye la revisión de la implementación ni la validación real pendiente. Al retomar, dar capacidades explícitas al fake, comprobar también que un proveedor sin ellas conserva el original y añadir concurrencia y recuperación de cada fase. No rebajar la protección de producción para satisfacer aserciones antiguas.

## Evidencia previa y límites

El registro [focused-before-stop/gradle.log](focused-before-stop/gradle.log) corresponde a una ejecución anterior de 39 pruebas focalizadas aprobadas (13 paginación, 7 publicación, 19 subida). La suite final reemplazó los XML anteriores; se conservan solo sus resultados finales en `unit-tests`. No confundir las dos revisiones.

Hay 12 métodos de prueba instrumentada escritos (4 comprimidos, 7 vídeo, 1 SAF con varios recorridos), pendientes de ejecución en Android. El entorno local no dispone de `/dev/kvm`. Las 20 comprobaciones aprobadas en Android 15 de la versión anterior están en [ci-run-10](../ci-run-10/); no comprueban el código nuevo.

El [reporte de pendientes](../../../REPORTE_PENDIENTES_2026-10-04.md) describe las rutas antiguas de movimiento aún sin protección, los proveedores que conservan originales, la integración pendiente y las cuentas/hardware necesarios.

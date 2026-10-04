package com.omaritoinforma.oiarchivos.data

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import java.io.File

/**
 * «Eliminar Cache» de ES: borra la caché de la app (miniaturas, vistas previas de red y de
 * documentos). Los registros de transferencias no están en la caché y no se tocan.
 */
object CacheCleaner {
    private fun folders(ctx: Context): List<File> = listOfNotNull(ctx.cacheDir, ctx.externalCacheDir)

    fun size(ctx: Context): Long = folders(ctx).sumOf { folder -> folder.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    /** Devuelve los bytes liberados. No hace nada mientras una operación usa la caché. */
    @OptIn(ExperimentalCoilApi::class)
    fun clear(ctx: Context): Long {
        if (TransferService.isBusy) return 0
        val before = size(ctx)
        ctx.imageLoader.memoryCache?.clear()
        runCatching { ctx.imageLoader.diskCache?.clear() }
        folders(ctx).forEach { folder -> folder.listFiles()?.forEach { it.deleteRecursively() } }
        return (before - size(ctx)).coerceAtLeast(0)
    }
}

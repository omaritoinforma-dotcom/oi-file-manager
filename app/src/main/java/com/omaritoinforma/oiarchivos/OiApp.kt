package com.omaritoinforma.oiarchivos

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class OiApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.omaritoinforma.oiarchivos.data.RemoteFiles.appContext = applicationContext
        // El idioma se aplica antes que nada: también lo usan los servicios y los avisos en segundo plano.
        com.omaritoinforma.oiarchivos.data.I18n.apply(
            this, com.omaritoinforma.oiarchivos.data.Prefs(this).appLanguage)
        com.omaritoinforma.oiarchivos.data.NativeArchives.executable =
            java.io.File(applicationInfo.nativeLibraryDir, "lib7zz.so")
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}

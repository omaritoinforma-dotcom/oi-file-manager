package com.omaritoinforma.oiarchivos.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

object RootlessDebugger {
    @Volatile private var remote: IRootlessDebugger? = null
    private var binding: ServiceConnection? = null
    private var arguments: Shizuku.UserServiceArgs? = null
    fun status(): JSONObject {
        val running=Shizuku.pingBinder()
        val granted=running && runCatching { Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val uid=if(running)runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        return JSONObject().put("shizukuRunning",running).put("permissionGranted",granted)
            .put("uid",uid).put("rootlessReady",granted && uid==2000)
            .put("withoutShizuku",AndroidApiDiagnostics.capabilities())
    }
    fun requestPermission() {
        if (!Shizuku.pingBinder())throw IOException("Abre Shizuku e inícialo con depuración inalámbrica o ADB")
        if (Shizuku.getUid()!=2000)throw IOException("El depurador requiere Shizuku iniciado con ADB, sin root")
        Shizuku.requestPermission(804)
    }
    @Synchronized private fun service(context: Context): IRootlessDebugger {
        if (!status().getBoolean("rootlessReady")) throw IOException("Autoriza Shizuku en modo ADB desde el depurador del móvil")
        remote?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val ready=CountDownLatch(1)
        val args=Shizuku.UserServiceArgs(ComponentName(context.packageName,RootlessDebugService::class.java.name))
            .daemon(false).processNameSuffix("rootless_debug").version(1)
        val connection=object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote=IRootlessDebugger.Stub.asInterface(binder);ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {remote=null}
        }
        arguments=args;binding=connection
        Shizuku.bindUserService(args,connection)
        if (!ready.await(8,TimeUnit.SECONDS)) throw IOException("No se inició el servicio de depuración")
        return remote ?: throw IOException("Servicio de depuración desconectado")
    }
    fun execute(context: Context,action: String,args: JSONObject,fromRemote: Boolean = true): JSONObject {
        val config=BridgeConfig(context)
        if(action=="debug_status")return status().put("remoteEnabled",config.allowDebug)
        if (fromRemote && !config.allowDebug) throw IOException("Activa el depurador remoto en el móvil")
        if(action=="debug_device_info")return JSONObject().put("manufacturer",android.os.Build.MANUFACTURER)
            .put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE)
            .put("sdk",android.os.Build.VERSION.SDK_INT).put("appVersion",com.omaritoinforma.oiarchivos.BuildConfig.VERSION_NAME)
            .put("freeStorage",android.os.Environment.getExternalStorageDirectory().usableSpace)
            .put("debugger",status())
        if(action=="debug_list_apps") {
            val all=AppsRepo.list(context,true).filter { args.optString("query").let { q ->
                q.isBlank()||it.packageName.contains(q,true)||it.label.contains(q,true) } }
            val offset=args.optInt("offset",0).coerceAtLeast(0);val items=JSONArray()
            all.drop(offset).take(100).forEach {items.put(JSONObject().put("packageName",it.packageName)
                .put("label",it.label).put("version",it.versionName).put("system",it.isSystem))}
            return JSONObject().put("items",items).put("total",all.size)
                .put("nextOffset",if(offset+100<all.size)offset+100 else JSONObject.NULL)
        }
        val command=args.getString("action")
        if(fromRemote && command in DebugCommands.changes && !config.allowChanges)throw IOException("Los cambios están desactivados")
        if(fromRemote && command in setOf("clear_data","uninstall") && !config.allowDelete)throw IOException("El borrado definitivo está desactivado")
        // Validate before binding; argv is rebuilt and checked again in the shell process.
        DebugCommands.argv(command,args.optString("packageName"),args.optString("value"),args.optInt("lines",200),
            args.optInt("x"),args.optInt("y"),args.optInt("x2"),args.optInt("y2"))
        // API reads never need shell privileges; installation uses Android's visible UI.
        if(command in setOf("install","uninstall"))
            return RootlessUserActions.request(context,command,args,fromRemote)
        if(command in AndroidApiDiagnostics.readActions && !status().getBoolean("rootlessReady"))
            return AndroidApiDiagnostics.execute(context,command,args)
        if(!status().getBoolean("rootlessReady"))throw IOException(
            "Esta operación requiere privilegios ADB/Shizuku. El puente normal no puede concederlos ni acceder a datos privados de otras apps.")
        if(command=="screenshot") {
            val file=FileAgent(context,true,false).resolveUploadTarget(args.getString("value"))
            if(file.parentFile?.isDirectory!=true)throw IOException("La carpeta de captura no existe")
        }
        val reply=JSONObject(service(context).execute(args.toString()))
        if(command=="screenshot" && reply.optInt("exitCode",-1)==0)reply.put("path",args.getString("value"))
        return reply
    }
    fun disconnect() {
        val a=arguments;val c=binding
        if(a!=null && c!=null)runCatching {Shizuku.unbindUserService(a,c,true)}
        remote=null;binding=null;arguments=null
    }
}

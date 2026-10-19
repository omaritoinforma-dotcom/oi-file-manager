package com.omaritoinforma.oiarchivos.data

import android.os.Process
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Shizuku UserService; refuses UID 0 even if Shizuku itself was started with root. */
@Keep
class RootlessDebugService : IRootlessDebugger.Stub() {
    override fun destroy() { System.exit(0) }
    override fun execute(request: String): String {
        check(Process.myUid()==2000) { "Inicia Shizuku mediante ADB, sin root" }
        val a=JSONObject(request)
        val argv=DebugCommands.argv(a.getString("action"),a.optString("packageName"),a.optString("value"),
            a.optInt("lines",200),a.optInt("x"),a.optInt("y"),a.optInt("x2"),a.optInt("y2"))
        val process=ProcessBuilder(argv).redirectErrorStream(true).start()
        val scheduler=Executors.newSingleThreadScheduledExecutor()
        val timedOut=AtomicBoolean(false)
        val timeout=scheduler.schedule({timedOut.set(true);process.destroyForcibly()},12,TimeUnit.SECONDS)
        try {
            val bytes=ByteArrayOutputStream();val buffer=ByteArray(4096)
            process.inputStream.use { stream ->
                while(bytes.size()<=65536) {
                    val n=stream.read(buffer,0,minOf(buffer.size,65537-bytes.size()))
                    if(n<0)break
                    if(n>0)bytes.write(buffer,0,n)
                }
            }
            val truncated=bytes.size()>65536
            if(truncated)process.destroyForcibly()
            process.waitFor(2,TimeUnit.SECONDS)
            return JSONObject().put("uid",Process.myUid()).put("exitCode",runCatching { process.exitValue() }.getOrDefault(-1))
                .put("stdout",String(bytes.toByteArray().take(65536).toByteArray(),Charsets.UTF_8))
                .put("truncated",truncated).put("timedOut",timedOut.get()).toString()
        } finally {timeout.cancel(false);scheduler.shutdownNow();process.destroy()}
    }
}

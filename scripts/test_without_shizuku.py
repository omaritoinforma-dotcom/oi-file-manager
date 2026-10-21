"""Run ordinary-app API tests on a disposable emulator, with no Shizuku installed."""
import pathlib
import subprocess

out=pathlib.Path('rootless-test-output');out.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.run(['adb',*args],capture_output=True,text=True,check=True,timeout=180).stdout
apks=list(pathlib.Path('rootless-apks').rglob('*.apk'))
app=next(p for p in apks if p.name=='app-debug.apk')
tests=next(p for p in apks if p.name=='app-debug-androidTest.apk')
adb('install','-r',str(app));adb('install','-r',str(tests))
package='com.omaritoinforma.oiarchivos'
assert 'moe.shizuku' not in adb('shell','pm','list','packages')
adb('shell','appops','set',package,'MANAGE_EXTERNAL_STORAGE','allow')
adb('shell','pm','revoke',package,'android.permission.POST_NOTIFICATIONS')
result=adb('shell','am','instrument','-w','-r',package+'.test/androidx.test.runner.AndroidJUnitRunner')
(out/'instrumentation.txt').write_text(result)
assert 'OK (6 tests)' in result and 'FAILURES' not in result and 'INSTRUMENTATION_FAILED' not in result,result
print(result)

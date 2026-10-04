"""Run the instrumented APK tests and fail CI when Android reports a test failure."""
import pathlib
import re
import subprocess

output = pathlib.Path("smoke-output")
output.mkdir(exist_ok=True)
for pattern in ("app-debug.apk", "app-debug-androidTest.apk"):
    apk = next(pathlib.Path("android-tests").rglob(pattern))
    subprocess.run(["adb", "install", "-r", str(apk)], check=True, timeout=120)
subprocess.run(["adb", "shell", "appops", "set", "com.omaritoinforma.oiarchivos",
                "MANAGE_EXTERNAL_STORAGE", "allow"], check=True, timeout=30)
result = subprocess.run(
    ["adb", "shell", "am", "instrument", "-w", "-r",
     "com.omaritoinforma.oiarchivos.test/androidx.test.runner.AndroidJUnitRunner"],
    capture_output=True, text=True, timeout=1200,
)
log = result.stdout + result.stderr
(output / "instrumentation.txt").write_text(log, encoding="utf-8")
print(log, flush=True)
assert result.returncode == 0, "Instrumentation command failed"
assert re.search(r"OK \(\d+ tests?\)", log), "Android did not report all instrumented tests passing"
assert "FAILURES!!!" not in log and "INSTRUMENTATION_FAILED" not in log, "Android test failure"

# 7-Zip 26.03

Official source: https://github.com/ip7z/7zip/releases/tag/26.03

The source archive and license are included. Run `scripts/build_archives.py ABI`
with `ANDROID_NDK_HOME` set to Android NDK 27.2.12479018. The source SHA-256 is
checked by the script before compilation. No external executable is downloaded
or executed by the app. RAR code may be used only for decompression, as explained
in LICENSE.txt.

Android binaries are PIE executables, linked with static libc++, with 16 KiB ELF
page alignment. They are installed as `lib7zz.so` in the app's native library
directory. The app runs the executable directly without a shell. Extraction
streams each validated entry through standard output; 7-Zip never selects a
destination path or writes archive entries to the filesystem.

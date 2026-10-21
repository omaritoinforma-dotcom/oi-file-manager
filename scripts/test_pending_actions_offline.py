"""Compile and run pure JVM tests from the preserved Kotlin/JUnit artifact cache.

This checks the private request queue and command validation, not Android UI/APIs.
OI_MAVEN_CACHE must point at the restored files-2.1 directory; JAVA_HOME needs JDK17.
"""
import os,pathlib,subprocess

root=pathlib.Path(__file__).resolve().parents[1]
cache=pathlib.Path(os.environ['OI_MAVEN_CACHE'])
java=pathlib.Path(os.environ['JAVA_HOME'])/'bin/java'
def jar(group,artifact,version):
    matches=list((cache/group/artifact/version).glob('*/*.jar'))
    assert len(matches)==1,(group,artifact,version,matches)
    return matches[0]
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.0')
junit=jar('junit','junit','4.13.2');hamcrest=jar('org.hamcrest','hamcrest-core','1.3')
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.0'),stdlib,
          jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.0'),
          jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
          jar('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.1.0'),
          jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
          jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4'),
          jar('org.jetbrains','annotations','13.0')]
out=root/'build/without-shizuku-jvm';out.mkdir(parents=True,exist_ok=True)
sources=[root/'app/src/main/java/com/omaritoinforma/oiarchivos/data'/f'{n}.kt'
         for n in ['PendingUserActionStore','DebugCommands']]
sources += [root/'app/src/test/java/com/omaritoinforma/oiarchivos/data'/f'{n}.kt'
            for n in ['PendingUserActionStoreTest','DebugCommandsTest']]
subprocess.run([str(java),'-cp',os.pathsep.join(map(str,compiler)),
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',
                '-classpath',os.pathsep.join(map(str,[stdlib,junit,hamcrest])),'-d',str(out),*map(str,sources)],check=True)
subprocess.run([str(java),'-cp',os.pathsep.join(map(str,[out,stdlib,junit,hamcrest])),
                'org.junit.runner.JUnitCore','com.omaritoinforma.oiarchivos.data.PendingUserActionStoreTest',
                'com.omaritoinforma.oiarchivos.data.DebugCommandsTest'],check=True)

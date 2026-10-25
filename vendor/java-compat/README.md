# Android Java compatibility source

The Android build uses the unmodified Maven artifact `com.android.tools:desugar_jdk_libs_nio:2.1.5` and its matching configuration. D8 includes the required backported Java APIs for the declared API 23 minimum, including NIO atomic file replacement. The archive here is source material, not an APK asset or another music runtime.

`desugar-jdk-2.1.5-source.zip` retains the official source tree at revision `092407c51c3eaaaa9e46f7b7e436dc642f614064`. Both `VERSION_JDK11_NIO.txt` and `DEPENDENCIES_JDK11_NIO.txt` identify 2.1.5. Original build/source instructions and all source headers remain in the archive.

- Source: https://github.com/google/desugar_jdk_libs/tree/092407c51c3eaaaa9e46f7b7e436dc642f614064
- Archive origin: https://codeload.github.com/google/desugar_jdk_libs/zip/092407c51c3eaaaa9e46f7b7e436dc642f614064
- SHA-256: `b6fc9a0d46dacd3948bd06d6d52bc1f727680ca2c0045aa008edc395ca2cbee9`.
- Maven binary: https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs_nio/2.1.5/desugar_jdk_libs_nio-2.1.5.jar

The project retains its GPL v2 text, Classpath Exception, assembly exception and additional licensing information. Those notices are copied into `legal/licenses/java-compat` and the APK's complete license collection. The Classpath Exception permits linking independent application modules under their own licenses, subject to its text. No private signing material is needed to rebuild this application with a separately signed APK.

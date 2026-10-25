# Vutron Music - Mobile 1.0.1 runtime

This directory contains the Android runtime helpers, embedded JavaScript source and ARM64 libraries used by Vutron Music - Mobile 1.0.1. The Capacitor application includes these sources and libraries through its Android build configuration.

- `src/main/java` contains five platform helpers for the embedded source runtime, local track matching, playback quality, source settings and artwork selection. They are compiled into the Capacitor application; the mobile UI and Android playback and overlay services live under `apps/android-vutron`.
- `src/main/assets/sources-runtime` contains the corresponding JavaScript source, including its vendored production dependencies and their licenses. This `node_modules` directory is shipped runtime source, not a development installation; do not omit it when redistributing the source.
- `src/main/jniLibs/arm64-v8a` contains Nodejs-Mobile 18.20.4 and its C++ runtime. License and component provenance are retained under the runtime `licenses` directory and in the repository legal notices.

See [Redistribution](../../legal/REDISTRIBUTION.md) and [Third-party notices](../../THIRD_PARTY_NOTICES.md) at the repository root. Changing a matcher module and rebuilding the APK is supported; no library-integrity restriction prevents replacing the LGPL JavaScript modules.

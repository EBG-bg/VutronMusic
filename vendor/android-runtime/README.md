# Vutron Music - Mobile runtime

This directory contains the Android platform runtime already used by the personal test builds. Public Mobile uses the same Java sources, JavaScript matcher, matching rules and ARM64 binaries; copying the runtime here keeps personal UI, test APKs and local account data outside the public source tree.

- Five Java platform helpers are compiled into the Capacitor application.
- `src/main/assets/sources-runtime` contains the corresponding JavaScript source, including its vendored production dependencies and their licenses. This `node_modules` directory is shipped runtime source, not a development installation; do not omit it when redistributing the source.
- `src/main/jniLibs/arm64-v8a` contains Nodejs-Mobile 18.20.4 and its C++ runtime. License and component provenance are retained under the runtime `licenses` directory and in the repository legal notices.

See `legal/REDISTRIBUTION.md` and `THIRD_PARTY_NOTICES.md` at the repository root. Changing a matcher module and rebuilding the APK is supported; no library-integrity restriction prevents replacing the LGPL JavaScript modules.

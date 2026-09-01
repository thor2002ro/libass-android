# libass-android

[![ass - Version](https://img.shields.io/maven-central/v/io.github.peerless2012/ass?label=ass)](https://central.sonatype.com/artifact/io.github.peerless2012/ass)
[![ass-kt - Version](https://img.shields.io/maven-central/v/io.github.peerless2012/ass-kt?label=ass-kt)](https://central.sonatype.com/artifact/io.github.peerless2012/ass-kt)
[![ass-media - Version](https://img.shields.io/maven-central/v/io.github.peerless2012/ass-media?label=ass-media)](https://central.sonatype.com/artifact/io.github.peerless2012/ass-media)

Android libraries for rendering ASS/SSA subtitles with [libass](https://github.com/libass/libass), including native Prefab, Kotlin/JNI, and Media3 modules.

This fork builds libass and its native dependencies directly from their current Git HEAD. It does not clone or depend on `libass-cmake`.

## Modules

### `lib_ass`

Builds and packages `libass.so` for native C/C++ consumers. The AAR publishes libass headers through Prefab.

Expat, Fontconfig, FreeType, FriBidi, HarfBuzz, and libunibreak are built as static libraries and linked into the shared `libass.so`. Android NDK zlib is linked separately, and `libc++_shared.so` is packaged with the AAR.

### `lib_ass_kt`

Kotlin/JNI wrapper around the native libass API. Windows builds compile against the prebuilt `OUTPUT/lib_ass-release.aar`; Linux builds can use the local `lib_ass` project directly.

### `lib_ass_media`

Media3 integration that adds ASS subtitle rendering to a Media3 player through `lib_ass_kt`.

## Native build configuration

| Setting | Value |
|---|---|
| Android NDK | `29.0.14206865` from `gradle.properties` |
| Compiler | Android NDK Clang |
| Minimum Android API | 21 |
| CMake | 3.22.1 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Release optimization | Unified `-O3 -flto=thin` compile and link flags for every ABI |
| Arm SIMD | NEON is enabled by the NDK for both Arm ABIs; ARMv7 also receives explicit NEON and Thumb flags |
| Parallelism | All available processors through `nproc` |

`cmake/native-flags.cmake` is the single source for release optimization and ARMv7 flags. Both native modules and every dependency build consume those values. The dependency build itself is defined in `lib_ass/src/main/cpp/CMakeLists.txt`.

Project-owned libass patches are stored in `patches/libass/` and applied after each fresh fetch. A patch conflict stops the build instead of silently producing an unpatched library.

## Rebuild the native AAR

The rebuild always replaces `lib_ass/src/main/cpp/sources/` and fetches the current default-branch HEAD of:

- libass
- Expat
- Fontconfig
- FreeType, including its Git submodules
- FriBidi
- HarfBuzz
- libunibreak

Meson is also updated from its `master` branch in the user cache. Builds therefore require network access and intentionally follow upstream development rather than pinned release tags.

### Windows with WSL2

Run from PowerShell or Command Prompt:

```bat
rebuild-libass-wsl.bat
```

The batch file forwards the repository path to WSL2 and runs the Linux build there. Missing Linux tools and Android SDK packages are installed when supported; WSL may ask for the user's `sudo` password.

### Linux

Run:

```bash
./rebuild-libass-wsl.sh
```

The script supports Debian/Ubuntu-style `apt` systems and Arch-style `pacman` systems for missing tools. On other distributions, install the tools listed by the script manually.

### Output

The completed AAR is copied to:

```text
OUTPUT/lib_ass-release.aar
```

The AAR is checked in so Windows builds can consume it without rebuilding the native module. After a verified rebuild, stage the updated artifact normally:

```bash
git add OUTPUT/lib_ass-release.aar
```

Keep the generated AAR in a separate commit immediately after its corresponding source/build commit.

## Continuous integration

The test and release workflows run the same direct-source rebuild before emulator tests or Maven Central publication. This keeps local WSL2 builds and Linux automation on the same native build path.

## Issues and pull requests

- [Report an issue](https://github.com/peerless2012/libass-android/issues/new)
- [Create a pull request](https://github.com/peerless2012/libass-android/compare)

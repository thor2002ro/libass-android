#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_ndk_version="$(sed -n 's/^androidNdkVersion=//p' "$repo/gradle.properties" | head -n 1 | tr -d '\r')"
[[ -n "$android_ndk_version" ]] || {
    echo "androidNdkVersion is missing from $repo/gradle.properties"
    exit 1
}

required_tools=(java git autoreconf autoconf automake libtoolize autopoint gperf make curl unzip pkg-config perl python3 ninja)
android_sdk_packages=(
    "platform-tools"
    "platforms;android-36"
    "build-tools;36.0.0"
    "cmake;3.22.1"
    "ndk;$android_ndk_version"
)

missing_tools() {
    local missing=()
    for tool in "${required_tools[@]}"; do
        command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
    done
    ((${#missing[@]})) && printf '%s\n' "${missing[@]}"
}

install_missing_tools() {
    mapfile -t missing < <(missing_tools)
    ((${#missing[@]} == 0)) && return

    echo "Missing WSL tools: ${missing[*]}"
    echo "Installing missing packages may ask for your WSL sudo password."
    if ! sudo -v; then
        echo "Sudo authentication failed."
        exit 1
    fi
    if command -v apt-get >/dev/null 2>&1; then
        apt_packages=()
        for tool in "${missing[@]}"; do
            case "$tool" in
                java) apt_packages+=(openjdk-21-jdk) ;;
                autoreconf | autoconf) apt_packages+=(autoconf) ;;
                automake) apt_packages+=(automake) ;;
                libtoolize) apt_packages+=(libtool) ;;
                pkg-config) apt_packages+=(pkg-config) ;;
                ninja) apt_packages+=(ninja-build) ;;
                *) apt_packages+=("$tool") ;;
            esac
        done
        mapfile -t apt_packages < <(printf '%s\n' "${apt_packages[@]}" | sort -u)
        apt_missing=()
        for package in "${apt_packages[@]}"; do
            dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q "install ok installed" || apt_missing+=("$package")
        done
        if ((${#apt_missing[@]})); then
            sudo apt-get update
            sudo apt-get install -y "${apt_missing[@]}" ||
                sudo apt-get install -y "${apt_missing[@]/openjdk-21-jdk/default-jdk}"
        fi
    elif command -v pacman >/dev/null 2>&1; then
        pacman_packages=()
        for tool in "${missing[@]}"; do
            case "$tool" in
                java) pacman_packages+=(jdk21-openjdk) ;;
                autoreconf | autoconf) pacman_packages+=(autoconf) ;;
                libtoolize) pacman_packages+=(libtool) ;;
                pkg-config) pacman_packages+=(pkgconf) ;;
                *) pacman_packages+=("$tool") ;;
            esac
        done
        mapfile -t pacman_packages < <(printf '%s\n' "${pacman_packages[@]}" | sort -u)
        pacman_missing=()
        for package in "${pacman_packages[@]}"; do
            pacman -Q "$package" >/dev/null 2>&1 || pacman_missing+=("$package")
        done
        ((${#pacman_missing[@]})) && sudo pacman -Sy --needed --noconfirm "${pacman_missing[@]}"
    else
        echo "Unsupported WSL distro. Install these tools manually: ${required_tools[*]}"
        exit 1
    fi

    mapfile -t missing < <(missing_tools)
    if ((${#missing[@]})); then
        echo "Still missing WSL tools after install: ${missing[*]}"
        exit 1
    fi
}

install_missing_tools
export CMAKE_BUILD_PARALLEL_LEVEL="$(nproc)"

meson_root="${XDG_CACHE_HOME:-$HOME/.cache}/libass-android/meson"
if [[ -d "$meson_root/.git" ]]; then
    git -C "$meson_root" fetch --depth 1 origin master
    git -C "$meson_root" checkout --detach FETCH_HEAD
elif [[ -e "$meson_root" ]]; then
    echo "Meson cache path exists but is not a Git checkout: $meson_root" >&2
    exit 1
else
    git clone --depth 1 --branch master https://github.com/mesonbuild/meson.git "$meson_root"
fi
export LIBASS_MESON="$meson_root/meson.py"

install_android_sdk() {
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
    local sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
    local android_cli="$sdk_root/cmdline-tools/latest/bin/android"

    if [[ "$sdk_root" == /mnt/* && ! -x "$sdk_root/build-tools/36.0.0/aapt" ]]; then
        sdk_root="$HOME/android-sdk"
        sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
        android_cli="$sdk_root/cmdline-tools/latest/bin/android"
    fi

    if [[ ! -x "$sdkmanager" ]]; then
        local tmp_zip
        tmp_zip="$(mktemp)"
        mkdir -p "$sdk_root/cmdline-tools"
        curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" -o "$tmp_zip"
        rm -rf "$sdk_root/cmdline-tools/latest"
        unzip -q "$tmp_zip" -d "$sdk_root/cmdline-tools"
        mv "$sdk_root/cmdline-tools/cmdline-tools" "$sdk_root/cmdline-tools/latest"
        rm -f "$tmp_zip"
    fi

    export ANDROID_HOME="$sdk_root"
    export ANDROID_SDK_ROOT="$sdk_root"

    android_sdk_package_installed() {
        case "$1" in
            platform-tools) [[ -x "$sdk_root/platform-tools/adb" ]] ;;
            platforms\;android-*) [[ -f "$sdk_root/platforms/${1#platforms;}/android.jar" ]] ;;
            build-tools\;*) [[ -x "$sdk_root/build-tools/${1#build-tools;}/aapt" ]] ;;
            cmake\;*) [[ -x "$sdk_root/cmake/${1#cmake;}/bin/cmake" ]] ;;
            ndk\;*) [[ -f "$sdk_root/ndk/${1#ndk;}/source.properties" ]] ;;
            *) return 1 ;;
        esac
    }

    android_cli_package_name() {
        case "$1" in
            platform-tools) printf '%s\n' "$1" ;;
            platforms\;android-*) printf 'platforms/%s\n' "${1#platforms;}" ;;
            build-tools\;*) printf 'build-tools/%s\n' "${1#build-tools;}" ;;
            cmake\;*) printf 'cmake/%s\n' "${1#cmake;}" ;;
            ndk\;*) printf 'ndk/%s\n' "${1#ndk;}" ;;
            *) return 1 ;;
        esac
    }

    sdk_missing=()
    sdk_install_args=()
    for package in "${android_sdk_packages[@]}"; do
        if ! android_sdk_package_installed "$package"; then
            sdk_missing+=("$package")
            sdk_install_args+=("$(android_cli_package_name "$package")")
        fi
    done

    if ((${#sdk_missing[@]})); then
        echo "Installing Android SDK packages: ${sdk_install_args[*]}"
        set +o pipefail
        yes | "$android_cli" --no-metrics sdk install "${sdk_install_args[@]}"
        set -o pipefail
    fi
}

install_android_sdk

cd "$repo"
local_properties="$repo/local.properties"
local_properties_backup="$(mktemp)"
had_local_properties=false
if [[ -f "$local_properties" ]]; then
    cp "$local_properties" "$local_properties_backup"
    had_local_properties=true
fi

restore_local_configuration() {
    rm -f "$repo/.gradlew-wsl"
    if $had_local_properties; then
        cp "$local_properties_backup" "$local_properties"
    else
        rm -f "$local_properties"
    fi
    rm -f "$local_properties_backup"
}
trap restore_local_configuration EXIT

printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
tr -d '\r' < gradlew > .gradlew-wsl
chmod +x .gradlew-wsl

sources_dir="$repo/lib_ass/src/main/cpp/sources"
rm -rf "$sources_dir"
mkdir -p "$sources_dir"

clone_source() {
    local name="$1"
    local url="$2"
    git clone --depth 1 --recurse-submodules "$url" "$sources_dir/$name"
    git -C "$sources_dir/$name" submodule foreach --recursive \
        'git fetch --depth 1 origin HEAD && git checkout --detach FETCH_HEAD'
}

clone_source ass https://github.com/libass/libass.git
clone_source expat https://github.com/libexpat/libexpat.git
clone_source fontconfig https://gitlab.freedesktop.org/fontconfig/fontconfig.git
clone_source freetype https://gitlab.freedesktop.org/freetype/freetype.git
clone_source fribidi https://github.com/fribidi/fribidi.git
clone_source harfbuzz https://github.com/harfbuzz/harfbuzz.git
clone_source unibreak https://github.com/adah1972/libunibreak.git

rm -rf "$repo/lib_ass/.cxx"
perl -pi -e 's/\r$//' \
    "$sources_dir/unibreak/autogen.sh" \
    "$sources_dir/fribidi/autogen.sh" \
    "$sources_dir/ass/autogen.sh" \
    "$sources_dir/expat/expat/Makefile.am" \
    "$sources_dir/expat/expat/configure.ac" \
    "$sources_dir/expat/expat/conftools/get-version.sh"
./.gradlew-wsl :lib_ass:clean
./.gradlew-wsl :lib_ass:applyLibassPatches

libass_commit="$(git -C "$sources_dir/ass" rev-parse HEAD)"
[[ "$libass_commit" =~ ^[0-9a-f]{40}$ ]] || {
    echo "Could not resolve the exact libass commit." >&2
    exit 1
}
patch_index="$(mktemp)"
rm -f "$patch_index"
GIT_INDEX_FILE="$patch_index" git -C "$sources_dir/ass" read-tree HEAD
GIT_INDEX_FILE="$patch_index" git -C "$sources_dir/ass" add -A
patch_tree="$(GIT_INDEX_FILE="$patch_index" git -C "$sources_dir/ass" write-tree)"
rm -f "$patch_index"
[[ "$patch_tree" =~ ^[0-9a-f]{40}$ ]] || {
    echo "Could not resolve the patched libass tree." >&2
    exit 1
}
libass_version="$(tr -d '\r[:space:]' < "$sources_dir/ass/RELEASEVERSION")"
[[ "$libass_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
	echo "Could not resolve LIBASS_VERSION: $libass_version" >&2
	exit 1
}
libass_version_hex="$(sed -n 's/^#define LIBASS_VERSION[[:space:]]\+//p' "$sources_dir/ass/libass/ass.h" | head -n 1 | tr -d '[:space:]')"
[[ "$libass_version_hex" =~ ^0x[0-9A-Fa-f]{8}$ ]] || {
	echo "Could not resolve hexadecimal LIBASS_VERSION: $libass_version_hex" >&2
	exit 1
}
wrapper_version="$(sed -n 's/^VERSION_NAME=//p' "$repo/gradle.properties" | head -n 1 | tr -d '\r[:space:]')"
[[ "$wrapper_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-thor$ ]] || {
    echo "Invalid libass Android version: $wrapper_version" >&2
    exit 1
}
provider_version="$wrapper_version.${libass_commit:0:12}"

(cd "$sources_dir/unibreak" && NOCONFIGURE=1 ./autogen.sh)
(cd "$sources_dir/fribidi" && NOCONFIGURE=1 ./autogen.sh)
(cd "$sources_dir/ass" && ./autogen.sh)
(cd "$sources_dir/expat/expat" && tr -d '\r' < ./buildconf.sh | bash)

./.gradlew-wsl :lib_ass:assembleRelease

mkdir -p OUTPUT
provider_aar="$repo/OUTPUT/lib_ass-release.aar"
cp -f lib_ass/build/outputs/aar/lib_ass-release.aar "$provider_aar"

metadata_root="$(mktemp -d)"
mkdir -p "$metadata_root/META-INF"
cat > "$metadata_root/META-INF/libass-android-provider.properties" <<EOF
group=io.github.peerless2012
artifact=libass-android-provider
version=$provider_version
libass_version=$libass_version
libass_version_hex=$libass_version_hex
libass_commit=$libass_commit
patch_tree=$patch_tree
ndk_version=$android_ndk_version
abis=armeabi-v7a,arm64-v8a,x86,x86_64
optimization=O3,thin-lto,armv7-neon,armv7-thumb,arm64-neon
EOF
jar --update --file "$provider_aar" -C "$metadata_root" META-INF
rm -rf "$metadata_root"

maven_version_dir="$repo/OUTPUT/maven/io/github/peerless2012/libass-android-provider/$provider_version"
mkdir -p "$maven_version_dir"
cp -f "$provider_aar" "$maven_version_dir/libass-android-provider-$provider_version.aar"
cat > "$maven_version_dir/libass-android-provider-$provider_version.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.peerless2012</groupId>
  <artifactId>libass-android-provider</artifactId>
  <version>$provider_version</version>
  <packaging>aar</packaging>
</project>
EOF
cat > "$repo/OUTPUT/libass-provider.properties" <<EOF
group=io.github.peerless2012
artifact=libass-android-provider
version=$provider_version
libass_version=$libass_version
libass_version_hex=$libass_version_hex
libass_commit=$libass_commit
patch_tree=$patch_tree
ndk_version=$android_ndk_version
EOF

echo "Saved shared libass provider $provider_version to $provider_aar"

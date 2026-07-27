#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

required_tools=(java git autoreconf autoconf automake libtoolize autopoint gperf make curl unzip pkg-config perl)
android_sdk_packages=(
    "platform-tools"
    "platforms;android-36"
    "build-tools;36.0.0"
    "cmake;3.22.1"
    "ndk;30.0.15729638"
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
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
tr -d '\r' < gradlew > .gradlew-wsl
chmod +x .gradlew-wsl
trap 'rm -f "$repo/.gradlew-wsl"' EXIT

cmake_dir="$repo/lib_ass/src/main/cpp/libass-cmake"
for source_dir in unibreak fribidi fontconfig ass expat harfbuzz freetype; do
    git -C "$cmake_dir/src/$source_dir" -c core.autocrlf=false checkout-index -f -a
done
git -C "$cmake_dir/src/ass" -c core.autocrlf=false reset --hard HEAD
git -C "$cmake_dir/src/ass" -c core.autocrlf=false clean -fdx
./.gradlew-wsl :lib_ass:applyLibassPatches

(cd "$cmake_dir/src/unibreak" && NOCONFIGURE=1 ./autogen.sh)
(cd "$cmake_dir/src/fribidi" && NOCONFIGURE=1 ./autogen.sh)
(cd "$cmake_dir/src/fontconfig" && NOCONFIGURE=1 ./autogen.sh)
(cd "$cmake_dir/src/ass" && ./autogen.sh)
(cd "$cmake_dir/src/expat/expat" && ./buildconf.sh)

./.gradlew-wsl :lib_ass:assembleRelease

mkdir -p OUTPUT
cp -f lib_ass/build/outputs/aar/lib_ass-release.aar OUTPUT/lib_ass-release.aar
echo "Saved $repo/OUTPUT/lib_ass-release.aar"

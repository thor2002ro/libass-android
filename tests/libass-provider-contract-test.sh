#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: libass-provider-contract-test.sh PROVIDER_AAR" >&2
    exit 2
fi

provider_aar="$1"
[[ -f "$provider_aar" ]] || {
    echo "Provider AAR not found: $provider_aar" >&2
    exit 1
}
command -v unzip >/dev/null || {
    echo "unzip is required to validate the provider AAR." >&2
    exit 1
}

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_ndk_version="$(sed -n 's/^androidNdkVersion=//p' "$project_dir/gradle.properties" | head -n 1 | tr -d '\r')"
[[ "$android_ndk_version" =~ ^[0-9]+(\.[0-9]+)+$ ]] || {
    echo "androidNdkVersion is missing or invalid in $project_dir/gradle.properties" >&2
    exit 1
}

entries="$(unzip -Z1 "$provider_aar")"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

require_entry() {
    local entry="$1"
    grep -Fxq "$entry" <<<"$entries" || {
        echo "Missing provider entry: $entry" >&2
        exit 1
    }
}

metadata_entry=META-INF/libass-android-provider.properties
require_entry "$metadata_entry"
metadata="$(unzip -p "$provider_aar" "$metadata_entry" | tr -d '\r')"
external_metadata_file="$project_dir/OUTPUT/libass-provider.properties"
[[ -f "$external_metadata_file" ]] || {
    echo "External provider metadata not found: $external_metadata_file" >&2
    exit 1
}
external_metadata="$(tr -d '\r' < "$external_metadata_file")"
for key in group artifact version libass_version libass_version_hex libass_commit patch_tree ndk_version; do
    expected_line="$(sed -n "s/^$key=/$key=/p" <<<"$metadata")"
    grep -Fxq "$expected_line" <<<"$external_metadata" || {
        echo "External provider metadata does not match AAR: $key" >&2
        exit 1
    }
done
for expected in \
    'group=io.github.peerless2012' \
    'artifact=libass-android-provider' \
    "ndk_version=$android_ndk_version" \
    'abis=armeabi-v7a,arm64-v8a,x86,x86_64' \
    'optimization=O3,thin-lto,armv7-neon,armv7-thumb,arm64-neon'
do
    grep -Fxq "$expected" <<<"$metadata" || {
        echo "Provider metadata is missing: $expected" >&2
        exit 1
    }
done

provider_version="$(sed -n 's/^version=//p' <<<"$metadata")"
libass_version="$(sed -n 's/^libass_version=//p' <<<"$metadata")"
libass_version_hex="$(sed -n 's/^libass_version_hex=//p' <<<"$metadata")"
libass_commit="$(sed -n 's/^libass_commit=//p' <<<"$metadata")"
patch_tree="$(sed -n 's/^patch_tree=//p' <<<"$metadata")"
[[ "$provider_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-thor\.[0-9a-f]{12}$ ]] || {
    echo "Provider version is invalid: $provider_version" >&2
    exit 1
}
[[ "$libass_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
	echo "libass version is invalid: $libass_version" >&2
	exit 1
}
[[ "$libass_version_hex" =~ ^0x[0-9A-Fa-f]{8}$ ]] || {
	echo "hexadecimal libass version is invalid: $libass_version_hex" >&2
	exit 1
}
[[ "$libass_commit" =~ ^[0-9a-f]{40}$ ]] || {
    echo "libass commit is invalid: $libass_commit" >&2
    exit 1
}
[[ "$patch_tree" =~ ^[0-9a-f]{40}$ ]] || {
    echo "libass patch tree is invalid: $patch_tree" >&2
    exit 1
}
[[ "$provider_version" == *".${libass_commit:0:12}" ]] || {
    echo "Provider version does not identify libass commit $libass_commit: $provider_version" >&2
    exit 1
}

require_entry prefab/prefab.json
require_entry prefab/modules/ass/module.json
require_entry prefab/modules/ass/include/ass/ass.h
require_entry prefab/modules/ass/include/ass/ass_types.h

source_dir="$project_dir/lib_ass/src/main/cpp/sources/ass"
patch_dir="$project_dir/patches/libass"
expected_index="$temporary_dir/libass-expected.index"
GIT_INDEX_FILE="$expected_index" git -C "$source_dir" read-tree HEAD
while IFS= read -r patch_file; do
    GIT_INDEX_FILE="$expected_index" git -C "$source_dir" -c core.autocrlf=false \
        apply --cached "$patch_file"
done < <(find "$patch_dir" -maxdepth 1 -type f -name '*.patch' | sort)
expected_libass_commit="$(git -C "$source_dir" rev-parse HEAD)"
[[ "$libass_commit" == "$expected_libass_commit" ]] || {
    echo "Provider libass commit does not match source: $libass_commit" >&2
    exit 1
}
expected_patch_tree="$(GIT_INDEX_FILE="$expected_index" git -C "$source_dir" write-tree)"
[[ "$patch_tree" == "$expected_patch_tree" ]] || {
    echo "Provider patch tree does not match source: $patch_tree" >&2
    exit 1
}
for header in ass.h ass_types.h; do
    expected_header="$temporary_dir/expected-$header"
    packaged_header="$temporary_dir/packaged-$header"
    GIT_INDEX_FILE="$expected_index" git -C "$source_dir" show ":libass/$header" > "$expected_header"
    unzip -p "$provider_aar" "prefab/modules/ass/include/ass/$header" | tr -d '\r' > "$packaged_header"
    cmp -s "$expected_header" "$packaged_header" || {
        echo "Prefab header does not match patched libass source: $header" >&2
        exit 1
    }
done

for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    require_entry "jni/$abi/libass.so"
    require_entry "jni/$abi/libc++_shared.so"
    require_entry "prefab/modules/ass/libs/android.$abi/abi.json"
    require_entry "prefab/modules/ass/libs/android.$abi/libass.so"
done

find_readelf() {
    if [[ -n "${READELF:-}" && -x "$READELF" ]]; then
        printf '%s\n' "$READELF"
        return
    fi
    if command -v llvm-readelf >/dev/null; then
        command -v llvm-readelf
        return
    fi
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -n "$sdk_root" ]]; then
        local candidate="$sdk_root/ndk/$android_ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    fi
    echo "NDK llvm-readelf not found; set READELF or ANDROID_SDK_ROOT." >&2
    exit 1
}

readelf="$(find_readelf)"
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    library="$temporary_dir/libass-$abi.so"
    unzip -p "$provider_aar" "jni/$abi/libass.so" > "$library"
    "$readelf" -d "$library" | grep -Eq '\(SONAME\).+\[libass\.so\]' || {
        echo "Invalid libass SONAME for $abi" >&2
        exit 1
    }
    symbols="$($readelf --dyn-syms --wide "$library")"
    grep -Eq 'GLOBAL.+ass_library_init(@@LIBASS_PROVIDER_1)?$' <<<"$symbols" || {
        echo "Missing public libass API for $abi" >&2
        exit 1
    }
    if grep -Eq 'GLOBAL.+(FT_Init_FreeType|hb_buffer_create|FcInit)(@@[^[:space:]]+)?$' <<<"$symbols"; then
        echo "Embedded dependency symbol leaked for $abi" >&2
        exit 1
    fi
done

echo "Validated shared libass provider $provider_version"

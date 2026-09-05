#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

fixture="$temporary_dir/project"
source_dir="$fixture/lib_ass/src/main/cpp/sources/ass"
mkdir -p "$fixture/tests" "$fixture/patches/libass" "$fixture/OUTPUT" "$source_dir/libass"
cp "$project_dir/tests/libass-provider-contract-test.sh" "$fixture/tests/"
printf 'androidNdkVersion=99.1.2\n' > "$fixture/gradle.properties"
printf '#define LIBASS_VERSION 0x01704000\n' > "$source_dir/libass/ass.h"
printf 'typedef struct ass_event { int value; } ASS_Event;\n' > "$source_dir/libass/ass_types.h"
git -C "$source_dir" init -q
git -C "$source_dir" config core.autocrlf false
git -C "$source_dir" add libass/ass.h libass/ass_types.h
git -C "$source_dir" -c user.name=test -c user.email=test@example.invalid commit -qm initial
libass_commit="$(git -C "$source_dir" rev-parse HEAD)"
patch_tree="$(git -C "$source_dir" rev-parse 'HEAD^{tree}')"

aar_root="$temporary_dir/aar"
mkdir -p "$aar_root/META-INF" "$aar_root/prefab/modules/ass/include/ass"
cp "$source_dir/libass/ass.h" "$aar_root/prefab/modules/ass/include/ass/"
cp "$source_dir/libass/ass_types.h" "$aar_root/prefab/modules/ass/include/ass/"
printf '{}\n' > "$aar_root/prefab/prefab.json"
printf '{}\n' > "$aar_root/prefab/modules/ass/module.json"

write_metadata() {
    local recorded_commit="$1"
    local recorded_tree="$2"
    cat > "$aar_root/META-INF/libass-android-provider.properties" <<EOF
group=io.github.peerless2012
artifact=libass-android-provider
version=0.5.1-thor.${recorded_commit:0:12}
libass_version=0.17.4
libass_version_hex=0x01704000
libass_commit=$recorded_commit
patch_tree=$recorded_tree
ndk_version=99.1.2
abis=armeabi-v7a,arm64-v8a,x86,x86_64
optimization=O3,thin-lto,armv7-neon,armv7-thumb,arm64-neon
EOF
}

write_external_metadata() {
    grep -E '^(group|artifact|version|libass_version|libass_version_hex|libass_commit|patch_tree|ndk_version)=' \
        "$aar_root/META-INF/libass-android-provider.properties" > "$fixture/OUTPUT/libass-provider.properties"
}

write_metadata "$libass_commit" "$patch_tree"
write_external_metadata
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    mkdir -p "$aar_root/jni/$abi" "$aar_root/prefab/modules/ass/libs/android.$abi"
    printf 'fixture\n' > "$aar_root/jni/$abi/libass.so"
    printf 'fixture\n' > "$aar_root/jni/$abi/libc++_shared.so"
    printf 'fixture\n' > "$aar_root/prefab/modules/ass/libs/android.$abi/libass.so"
    printf '{}\n' > "$aar_root/prefab/modules/ass/libs/android.$abi/abi.json"
done

sdk_readelf="$fixture/sdk/ndk/99.1.2/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
mkdir -p "$(dirname "$sdk_readelf")"
cat > "$sdk_readelf" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == "-d" ]]; then
    echo ' 0x000000000000000e (SONAME) Library soname: [libass.so]'
else
    echo ' 1: 00000000 0 FUNC GLOBAL DEFAULT 1 ass_library_init'
fi
EOF
chmod +x "$sdk_readelf"

provider_aar="$temporary_dir/provider.aar"
(cd "$aar_root" && jar --create --file "$provider_aar" .)
ANDROID_SDK_ROOT="$fixture/sdk" "$fixture/tests/libass-provider-contract-test.sh" "$provider_aar"

write_metadata 1111111111111111111111111111111111111111 "$patch_tree"
write_external_metadata
(cd "$aar_root" && jar --create --file "$provider_aar" .)
if output="$(ANDROID_SDK_ROOT="$fixture/sdk" "$fixture/tests/libass-provider-contract-test.sh" "$provider_aar" 2>&1)"; then
    echo "Provider validator accepted metadata for a different libass commit." >&2
    exit 1
fi
grep -Fq "Provider libass commit does not match source" <<<"$output"

write_metadata "$libass_commit" 2222222222222222222222222222222222222222
write_external_metadata
(cd "$aar_root" && jar --create --file "$provider_aar" .)
if output="$(ANDROID_SDK_ROOT="$fixture/sdk" "$fixture/tests/libass-provider-contract-test.sh" "$provider_aar" 2>&1)"; then
    echo "Provider validator accepted metadata for a different patch tree." >&2
    exit 1
fi
grep -Fq "Provider patch tree does not match source" <<<"$output"

write_metadata "$libass_commit" "$patch_tree"
write_external_metadata
(cd "$aar_root" && jar --create --file "$provider_aar" .)
sed -i 's/^libass_version=.*/libass_version=9.9.9/' "$fixture/OUTPUT/libass-provider.properties"
if output="$(ANDROID_SDK_ROOT="$fixture/sdk" "$fixture/tests/libass-provider-contract-test.sh" "$provider_aar" 2>&1)"; then
    echo "Provider validator accepted external metadata that differs from the AAR." >&2
    exit 1
fi
grep -Fq "External provider metadata does not match AAR" <<<"$output"

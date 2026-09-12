#!/usr/bin/env bash
# Builds astropng-core (the shared conversion pipeline, see
# third_party/astropng-core/VERSION for the pinned tag) and drops the shared
# library where core/.../Native.scala expects it
# (third_party/astropng-core/lib/<libname>). Requires a Rust toolchain
# (cargo) on PATH. Safe to re-run; re-clones/rebuilds only if the pinned tag
# or cached checkout changed.
#
# Unlike the Go/Zig ports (which statically link libastropng_core.a), the
# JVM's Foreign Function & Memory API loads a *dynamic* library at runtime,
# so this builds the cdylib instead: libastropng_core.so (Linux),
# libastropng_core.dylib (macOS), or astropng_core.dll (Windows). No C
# toolchain matching (unlike cgo/Zig's linker) is needed here, so unlike
# AstroGoPNG's build-core.sh, rustup's default target is fine everywhere.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tag="$(cat "$repo_root/third_party/astropng-core/VERSION")"
cache_dir="${ASTROPNG_CORE_CACHE:-$HOME/.cache/astropng-core}/$tag"
lib_dir="$repo_root/third_party/astropng-core/lib"

if [[ ! -d "$cache_dir" ]]; then
    echo "Cloning astropng-core@$tag into $cache_dir"
    git clone --quiet --depth 1 --branch "$tag" \
        https://github.com/peterbuitho/astropng-core "$cache_dir"
fi

echo "Building astropng-core@$tag (release)"
(cd "$cache_dir" && cargo build --release --lib)

mkdir -p "$lib_dir"
case "$(uname -s)" in
    Darwin) libname="libastropng_core.dylib" ;;
    MINGW*|MSYS*|CYGWIN*) libname="astropng_core.dll" ;;
    *) libname="libastropng_core.so" ;;
esac
cp "$cache_dir/target/release/$libname" "$lib_dir/"
echo "Ready: $lib_dir/$libname"

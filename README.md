# AstroScalaPNG

Batch-convert **XISF** (PixInsight) and **FITS** astronomical images to **PNG**,
optionally resized to 4K with the object's name and catalogue info stamped in
the corner ("Andromeda Galaxy (M 31)" with NGC/IC ids, type and coordinates
underneath, looked up from SIMBAD). Comes as a command-line tool and a small
desktop app, both on the JVM, for Windows, macOS and Linux.

AstroScalaPNG is a **Scala 3 / JVM CLI and desktop app** on top of
[`astropng-core`](https://github.com/peterbuitho/astropng-core) — see
[Architecture](#architecture) — a shared conversion pipeline also used by
this program's Rust/Go/Zig ports.

Each image's full data range is linearly scaled to 0–255 (a plain min/max
stretch — no STF/MTF astronomical stretch). Only the first image in a file is
converted. Mono and RGB images are supported.

## Modules

| Module | What it is                                                                     |
| ------ | ------------------------------------------------------------------------------ |
| `core` | `Native` — Foreign Function & Memory (Panama) bindings into `astropng-core`, and `Batch`, the thin public API (`Options`/`Progress`/`Summary`/`run`) the CLI and GUI call. |
| `cli`  | `astroscalapng` — the command-line tool.        |
| `gui`  | `astroscalapng-gui` — a JavaFX desktop app. |

## Architecture

The conversion pipeline (XISF/FITS parsing, stretch, resize/stamp, WCS,
SIMBAD lookup, batch orchestration) lives in
[`astropng-core`](https://github.com/peterbuitho/astropng-core), a Rust
library shared with this program's Rust/Go/Zig ports. `core`'s `Native.scala`
binds to its C ABI directly via `java.lang.foreign` (stable since JDK 22 — no
JNI glue code, no header parser needed; the C struct layouts are hand-declared
`MemoryLayout`s, checked against the real library at build time). `cli.Main`
and `gui.GuiApp` are unaware of this; they only ever called `Batch`'s public
API. See `third_party/astropng-core/VERSION` for the pinned version and
`scripts/build-core.sh` for how the shared library is built.

## Build and run

Requires a JDK **25** (Temurin recommended; any 22+ JDK works, since that's
when the Foreign Function & Memory API stabilized) and a
[Rust toolchain](https://rustup.rs) (to build `astropng-core`), plus
[sbt](https://www.scala-sbt.org/).

```
bash scripts/build-core.sh   # builds third_party/astropng-core/lib/<libname>
sbt compile          # all three modules
sbt test             # unit tests
sbt "cli/run --help" # run the CLI from sbt
sbt gui/run          # run the GUI from sbt
```

### Packaging

Packaging uses [sbt-native-packager](https://sbt-native-packager.readthedocs.io/).

```
sbt cli/stage        # cli/target/universal/stage - launcher + bundled Java runtime (jlink)
sbt gui/stage        # gui/target/universal/stage - launcher + JavaFX jars for this OS
sbt cli/Universal/packageBin   # the same, as a .zip
```

The CLI image is built with `JlinkPlugin`, so `cli/target/universal/stage`
contains a stripped-down `jre/` folder (~110 MB total) and runs on a machine
with no Java installed:

```
cli/target/universal/stage/bin/astroscalapng --help          # Linux / macOS
cli\target\universal\stage\bin\astroscalapng.bat --help      # Windows
```

The GUI is packaged with `JavaAppPackaging` only (JavaFX's native libraries do
not combine cleanly with a jlink image built from a plain classpath), so it
needs a Java 22+ runtime on the machine. The JavaFX jars are selected for the
platform that runs the build, so each OS's archive is built on its own
runner, which also builds and bundles that platform's `astropng-core` shared
library into the staged app's `lib/` folder.

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) compiles and tests on
Linux, Windows and macOS for every push and pull request.
[`.github/workflows/release.yml`](.github/workflows/release.yml) builds the
per-OS archives (`astroscalapng-<os>-<arch>.zip`) on a `v*` tag and attaches
them to a GitHub Release; it can also be run manually from the Actions tab to
check that everything still builds, in which case the archives are only
uploaded as workflow artifacts.

## Command line

```
astroscalapng [input_dir] [output_dir] [--recursive|-r] [--overwrite] [--resize4k] [--filename] [--font <file>] [-j N]
astroscalapng [input_dir] [output_dir] --png-only [--recursive|-r] [--overwrite] [--filename] [--font <file>]
astroscalapng <file>... [output_dir] [--overwrite] [--resize4k] [--filename] [--font <file>]
```

Every `.xisf`, `.fits`, `.fit` and `.fts` file found is converted. If
`input_dir` is omitted, the current folder is used. If `output_dir` is omitted,
PNGs are written next to their source files (or, with `--png-only`, edited in
place). Explicit files may also be given instead of a folder; each is handled
by its extension (`.png` = resize/stamp only) and written next to itself, or
flat into `output_dir` if one is given.

Files are converted **in parallel** — one worker thread per CPU (capped at 8,
override with `-j`). The heavy work (decode, stretch, resize, stamp, encode)
runs concurrently; the online object lookup is serialised behind a shared
cache, so a folder of 300 subs of one target still costs only one or two
SIMBAD requests. Progress is printed in completion order.

| Option              | Meaning                                                     |
| ------------------- | ----------------------------------------------------------- |
| `-r`, `--recursive` | Recurse into subfolders; the output tree mirrors the input. |
| `--overwrite`       | Overwrite existing `.png` files (default: skip them).       |
| `--resize4k`        | Scale each PNG (up or down, aspect ratio kept) to cover 3840×2160, then center-crop to exactly 3840×2160 — no padding — and stamp the object name in the bottom-right corner (see [Object names](#object-names)). |
| `--filename`        | Stamp the plain file name: ignore the header `OBJECT`, never go online. (`--no-lookup` and `--offline` are aliases.) |
| `--png-only`        | Skip XISF/FITS conversion entirely: pick up existing `.png` files in `input_dir` and only run the `--resize4k` step on them (implies `--resize4k`). With no `output_dir` the PNGs are modified in place; with one they are copied there first, honouring `--overwrite`. |
| `--font <file>`     | A `.ttf` / `.otf` font file for the stamp. Default: the bundled DejaVu Sans Condensed Bold. `--font=<file>` also works. |
| `-j`, `--concurrency N` | Convert `N` files in parallel. Default: number of CPUs, capped at 8. `-jN` also works. |
| `-V`, `--version`   | Print the version.                                          |
| `-h`, `--help`      | Show help.                                                  |

Per-file output lines: `OK <path>`, `SKIP <path>`, `ERROR <path>: <reason>`.
A bad file is reported and the batch continues. Exit code is `1` if any file
failed, `2` for a usage error, `0` otherwise.

Examples:

```
astroscalapng "D:\captures" "D:\previews" --recursive
astroscalapng "D:\previews" --png-only --recursive
astroscalapng ~/astro/previews --png-only --font ~/fonts/Futura-CondensedBold.ttf
astroscalapng --png-only                      # PNGs in the current folder, in place
```

## GUI

Run `astroscalapng-gui` (or `sbt gui/run`). Pick the input folder, optionally
an output folder and a stamp font, adjust the options (the 4K resize with
object-name stamp is on by default), and press **Convert**. Progress and a
per-file log stream in as the batch runs; **Cancel** stops after the current
file.

Instead of a folder you can work on an explicit set of files: **Add files…**,
or pass them on the command line (`astroscalapng-gui a.xisf b.fits`). Files are
converted next to themselves unless an output folder is set; `.png` files in
the selection are only resized and stamped.

## Object names

With `--resize4k` (or `--png-only`) the stamp is a two-line label:

```
                    Andromeda Galaxy (M 31)
NGC 224  ·  UGC 454  ·  Spiral galaxy  ·  RA 00h 42m 44s  Dec +41° 16′ 08″
```

The object is identified from three sources and cross-checked:

1. the `OBJECT` keyword in the FITS header or XISF header (what your capture
   software was told the target was);
2. a catalogue designation in the file name — `M31`, `NGC_7000`, `IC 1396`,
   `Sh2-155`, `B33`, `LDN1235`, `vdB141`, `Cr399`, `Mel15`, `Ced214`,
   `Arp273`, `UGC`, `PGC`, `HD`, `HIP` are recognised, in any case and with
   `_`, `-` or a space as separator. Filter letters and exposure times such as
   `_B_120s` are not mistaken for catalogue ids;
3. the image coordinates: the plate solution if the file has one (WCS keywords
   `CRVAL1/2`, `CRPIX1/2`, `CD` matrix or `CDELT`), otherwise the mount target
   (`OBJCTRA`/`OBJCTDEC`, `RA`/`DEC`, or XISF `Observation:Center:RA/Dec`).

Names are resolved through the [CDS Sesame](https://cds.unistra.fr/cgi-bin/Sesame)
service (SIMBAD), which understands free-form names too (`OBJECT = 'Pleiades'`
gives "Pleiades (M 45)"). The rules:

- Header and file name disagree → the file name wins, with a `note:` line.
- The named object is inside the frame but a *different* notable object sits at
  the image centre → both are stamped: "Heart Nebula (IC 1805) & Fish Head
  Nebula (NGC 896)", or, when they share a name, "Leo Triplet (M 65 & M 66)".
- The named object is more than 2° (plus half the field of view) from where the
  frame points → SIMBAD is asked what deep-sky object actually sits at the
  image centre; a prominent one is stamped instead, with a note saying why.
- No usable name at all (`Light_0001.fits`) but coordinates present → the frame
  is identified from its coordinates alone.
- Nothing resolves, or you are offline → the plain file name is stamped.
- SIMBAD files a cluster and the nebula around it as separate objects (NGC 7380
  is "an open cluster"; the Wizard Nebula is Sh2-142). When the resolved object
  is a cluster or nebula without a common name, the named nebula at the same
  position is adopted, giving "Wizard Nebula (NGC 7380)".

Each distinct name or position is looked up once per run. `--filename` disables
all of this.

**Caldwell numbers and nicknames.** SIMBAD does not know the Caldwell catalogue
and lacks many popular nicknames, so the tool carries its own tables: all 109
Caldwell objects and about 150 common names. Curated names take precedence over
SIMBAD's.

**Your own names.** Put a text file with one `designation = Name` per line
(`#` starts a comment) in any of these places, first found wins:

- the path in the `ASTROSCALAPNG_NAMES` environment variable;
- `astroscalapng-names.txt` next to the application jar;
- `names.txt` in the per-user config folder: `%APPDATA%\astroscalapng\` on
  Windows, `~/Library/Application Support/astroscalapng/` on macOS,
  `~/.config/astroscalapng/` (or `$XDG_CONFIG_HOME/astroscalapng/`) on Linux.

```
# my names
NGC 2403 = Hidden Galaxy
Sh2-155  = Cave Nebula (Cepheus)
```

Your names override everything else.

> **Renamed from the Rust original.** xisf2png uses `XISF2PNG_NAMES`,
> `xisf2png-names.txt` and a `xisf2png` config folder. This port uses
> `ASTROSCALAPNG_NAMES`, `astroscalapng-names.txt` and an `astroscalapng`
> config folder, so the two tools can be installed side by side. If you already
> have an xisf2png names file, copy or point `ASTROSCALAPNG_NAMES` at it — the
> file format is identical.

## Format support

**XISF** (monolithic `.xisf`, version 1.0):

- Sample formats: `UInt8/16/32/64`, `Float32/64` (not `Complex`).
- Pixel storage: planar and interleaved (`Normal`); little- and big-endian.
- Data location: `attachment`, `embedded`, `inline` (base64 / hex).
- Compression: `zlib`, `lz4`, `lz4hc`, `zstd`, with or without byte-shuffle
  (`+sh`).

**FITS** (`.fits`, `.fit`, `.fts`), image in the primary HDU:

- `BITPIX` 8, 16, 32, 64 (integers, with `BZERO`/`BSCALE` applied, so the usual
  unsigned-16-bit camera files work) and −32, −64 (floats).
- `NAXIS` 2 (mono) or 3 with `NAXIS3` = 1 or 3 (RGB planes).
- Row order: FITS stores the bottom row first, so images are flipped to display
  orientation by default. Files that carry `ROWORDER = 'TOP-DOWN'` are left
  as-is.
- Not supported: images stored in extensions, tile-compressed `.fz` files, and
  `.fits.gz`.

## Credits and licensing

- Original project: **[xisf2png](https://github.com/peterbuitho/xisf2png)** by
  peterbuitho. The Rust repository carries no `LICENSE` file, so no license is
  asserted here either; if you plan to redistribute, ask the original author.
- Stamp font: **DejaVu Sans Condensed Bold**, embedded in `astropng-core`; its
  license is in
  [`astropng-core/assets/fonts/LICENSE-DejaVu.txt`](https://github.com/peterbuitho/astropng-core/blob/main/assets/fonts/LICENSE-DejaVu.txt).
- Object data comes from the [CDS Sesame](https://cds.unistra.fr/cgi-bin/Sesame)
  name resolver and [SIMBAD](https://simbad.cds.unistra.fr/); please cite them
  if you publish images produced with this tool.

package astroscalapng

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown for fatal, batch-level setup problems (bad input dir, unreadable
  * font, an astropng-core internal error); per-file problems are reported
  * through `FileStatus.Failed` and counted in the summary instead.
  */
final class BatchError(message: String) extends Exception(message)

/** A thin wrapper around astropng-core's C ABI
  * (https://github.com/peterbuitho/astropng-core), which holds the actual
  * conversion pipeline (XISF/FITS parsing, stretch, resize/stamp, WCS,
  * SIMBAD lookup, batch orchestration). Also used, via the same C ABI, by
  * the Go/Rust/Zig ports of this program.
  *
  * The public surface here (Options/FileStatus/Progress/Summary/run) is
  * unchanged from before this object called into the shared core, so
  * `cli.Main` and `gui.GuiApp` need no changes. See [[Native]] for the
  * Foreign Function & Memory (Panama) bindings themselves.
  */
object Batch:

  /** Extensions (lower-case) treated as source images in normal mode. */
  val ImageExts: Vector[String] = Vector("xisf", "fits", "fit", "fts")

  final case class Options(
      /** Folder to scan. Defaults to the current directory when the CLI gets no
        * positional argument.
        */
      inputDir: Path = Paths.get("."),
      /** Where to write PNGs; `None` means next to the sources (or, with
        * `pngOnly`, edit them in place).
        */
      outputDir: Option[Path] = None,
      recursive: Boolean = false,
      overwrite: Boolean = false,
      /** Scale to exactly 3840x2160 and stamp the file name. */
      resize4k: Boolean = false,
      /** Skip XISF conversion: operate on existing `.png` files. Implies
        * `resize4k`.
        */
      pngOnly: Boolean = false,
      /** A TrueType / OpenType font file for the stamp; `None` = bundled. */
      font: Option[Path] = None,
      /** Look the object up online (CDS Sesame / SIMBAD) and stamp its proper
        * name and catalogue info instead of the bare file name. Only relevant
        * when stamping; the file name is the fallback.
        */
      lookup: Boolean = false,
      /** Explicit files to process (e.g. from a right-click selection or drag
        * and drop). When non-empty, `inputDir`, `recursive` and `pngOnly` are
        * not used for scanning: each file is handled by its own extension
        * (`.png` = resize/stamp only) and written next to itself unless
        * `outputDir` is set, in which case all outputs go flat into it.
        */
      files: Vector[Path] = Vector.empty,
      /** Number of files to convert in parallel. `0` (the default) means the
        * core's own default (`min(availableParallelism, 8)`).
        */
      concurrency: Int = 0
  ):
    /** Human-readable description of the input file kind, for messages such as
      * "No .xisf / .fits files found."
      */
    def inputKind: String = if pngOnly then ".png" else ".xisf / .fits"

  enum FileStatus:
    case Ok
    case Skipped
    case Failed(error: String)

  /** One progress report, sent after each file has been handled. */
  final case class Progress(
      /** 1-based index of the file just handled. */
      index: Int,
      total: Int,
      /** Path relative to the input directory. */
      rel: Path,
      status: FileStatus,
      /** Title that was stamped on the image, when it differs from the file
        * name (i.e. the object was identified online).
        */
      label: Option[String],
      /** Something the user should know about this file (e.g. header and file
        * name disagree about the object).
        */
      note: Option[String]
  )

  final case class Summary(
      total: Int = 0,
      converted: Int = 0,
      skipped: Int = 0,
      failed: Int = 0,
      /** True when the run was stopped early via the cancel flag. */
      cancelled: Boolean = false,
      /** Run-level warnings (e.g. the online lookup was unreachable). */
      warnings: Vector[String] = Vector.empty
  )

  /** Run the whole batch. `report` is called once per file, in completion
    * order, from a single thread; set `cancel` from another thread to stop
    * after the current file. A [[BatchError]] is thrown for fatal setup
    * problems (bad input dir, unreadable font); per-file problems are
    * reported through `FileStatus.Failed` and counted in the summary.
    */
  def run(opts: Options, cancel: AtomicBoolean, report: Progress => Unit): Summary =
    Native.run(opts, cancel, report)

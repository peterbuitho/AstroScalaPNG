package astroscalapng

import java.awt.image.{BufferedImage, DataBufferByte}
import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Executors, LinkedBlockingQueue}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters.*

/** The batch job itself: find files, convert / post-process each one, report
  * progress. Used by both the CLI and the GUI.
  *
  * Port of `src/batch.rs`.
  */
object Batch:

  /** Extensions (lower-case) treated as source images in normal mode. */
  val ImageExts: Vector[String] = Vector("xisf", "fits", "fit", "fts")
  private val FitsExts          = Vector("fits", "fit", "fts")
  private val PngExts           = Vector("png")

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
      /** Number of files to convert in parallel. `0` (the default) means
        * `min(availableProcessors, 8)`.
        */
      concurrency: Int = 0
  ):
    /** Effective output directory. */
    def effectiveOutputDir: Path = outputDir.getOrElse(inputDir)

    /** File extensions that will be scanned for. */
    def inputExts: Vector[String] = if pngOnly then PngExts else ImageExts

    /** Human-readable description of the input file kind, for messages such as
      * "No .xisf / .fits files found."
      */
    def inputKind: String = if pngOnly then ".png" else ".xisf / .fits"

    /** `pngOnly` is pointless without the 4K step, so it implies it. */
    def resize4kEffective: Boolean = resize4k || pngOnly

  /** Resolve `Options.concurrency` to an actual worker count for `jobCount`
    * files.
    */
  private[astroscalapng] def workerCount(requested: Int, jobCount: Int): Int =
    val n = if requested > 0 then requested else math.min(Runtime.getRuntime.availableProcessors, 8)
    math.min(math.max(n, 1), math.max(jobCount, 1))

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

  /** Result of handling one file. */
  private final case class Outcome(
      written: Boolean,
      label: Option[String],
      note: Option[String]
  )

  private final case class Job(src: Path, dest: Path, rel: Path)

  /** Run the whole batch. `report` is called once per file; set `cancel` from
    * another thread to stop after the current file. A [[BatchError]] is thrown
    * for fatal setup problems (bad input dir, unreadable font); per-file
    * problems are reported through `FileStatus.Failed` and counted in the
    * summary.
    */
  def run(opts: Options, cancel: AtomicBoolean, report: Progress => Unit): Summary =
    val explicit = opts.files.nonEmpty
    if !explicit && !Files.isDirectory(opts.inputDir) then
      throw new BatchError(s"Input directory not found: ${opts.inputDir}")

    val stamper: Option[Stamper] =
      if opts.resize4kEffective then
        Some(opts.font match
          case Some(path) => Stamper.fromFile(path)
          case None       => Stamper.bundled()
        )
      else None

    val files: Vector[Path] =
      if explicit then opts.files
      else
        val buf = Vector.newBuilder[Path]
        collectFiles(opts.inputDir, opts.recursive, opts.inputExts, buf)
        buf.result().sortBy(_.toString.toLowerCase)

    if files.isEmpty then return Summary(total = 0)

    // Resolve each file's display name and destination up front. Scanned files
    // keep their position relative to the input folder; explicit files go flat.
    val jobs: Vector[Job] = files.map { file =>
      val rel: Path =
        if explicit then Option(file.getFileName).getOrElse(file)
        else stripPrefix(file, opts.inputDir)
      val dest = (opts.outputDir, explicit) match
        case (Some(out), _)  => withPngExtension(out.resolve(rel))
        case (None, true)    => withPngExtension(file)
        case (None, false)   => withPngExtension(opts.inputDir.resolve(rel))
      Job(file, dest, rel)
    }

    // The online lookup runs behind a lock: one network round-trip at a time,
    // shared cache across workers (a run of 300 subs of one target still costs
    // one or two SIMBAD requests). The heavy work - decode, stretch, resize,
    // stamp, encode - runs in parallel.
    val resolver     = new Lookup.Resolver(opts.lookup && stamper.isDefined)
    val resolverLock = new Object
    val workers      = workerCount(opts.concurrency, jobs.length)
    val next         = new AtomicInteger(0)

    sealed trait Msg
    final case class Item(idx: Int, status: FileStatus, label: Option[String], note: Option[String])
        extends Msg
    case object WorkerDone extends Msg

    val queue = new LinkedBlockingQueue[Msg]()
    val pool  = Executors.newFixedThreadPool(workers)

    for _ <- 0 until workers do
      pool.execute(() => {
        var running = true
        while running do
          if cancel.get() then running = false
          else
            val idx = next.getAndIncrement()
            if idx >= jobs.length then running = false
            else
              val job = jobs(idx)
              val msg =
                try
                  processOne(job.src, job.dest, opts, stamper, resolver, resolverLock) match
                    case Outcome(true, label, note) => Item(idx, FileStatus.Ok, label, note)
                    case Outcome(_, _, note)        => Item(idx, FileStatus.Skipped, None, note)
                catch
                  case e: Throwable =>
                    Item(idx, FileStatus.Failed(Xisf.msg(e)), None, None)
              queue.put(msg)
        queue.put(WorkerDone)
      })
    pool.shutdown()

    // Collector: reports in completion order, on this (single) thread.
    var converted = 0
    var skipped   = 0
    var failed    = 0
    var done      = 0
    while done < workers do
      queue.take() match
        case WorkerDone => done += 1
        case Item(idx, status, label, note) =>
          status match
            case FileStatus.Ok        => converted += 1
            case FileStatus.Skipped   => skipped += 1
            case FileStatus.Failed(_) => failed += 1
          report(Progress(idx + 1, jobs.length, jobs(idx).rel, status, label, note))

    val warnings = resolver.failure.toVector.map(e =>
      s"Online object lookup unavailable ($e); file names were stamped instead."
    )

    Summary(
      total = jobs.length,
      converted = converted,
      skipped = skipped,
      failed = failed,
      cancelled = cancel.get(),
      warnings = warnings
    )

  /** Handle one file. */
  private def processOne(
      src: Path,
      dest: Path,
      opts: Options,
      stamper: Option[Stamper],
      resolver: Lookup.Resolver,
      resolverLock: Object
  ): Outcome =
    // A PNG source is only ever resized/stamped, never "converted".
    val isPng    = hasExt(src, PngExts)
    val inPlace  = isPng && isSameFile(src, dest)
    if !inPlace && Files.exists(dest) && !opts.overwrite then return Outcome(false, None, None)

    var headerObject: Option[String] = None
    var headerCoords: Option[SkyCoords] = None
    var img: BufferedImage =
      if isPng then
        val read =
          try ImageIO.read(src.toFile)
          catch case e: Exception => throw new XisfError(s"cannot read PNG: ${Xisf.msg(e)}")
        if read == null then throw new XisfError("cannot read PNG: unsupported or corrupt file")
        read
      else
        val data = if hasExt(src, FitsExts) then Fits.read(src) else Xisf.read(src)
        headerObject = data.`object`
        headerCoords = data.coords
        image8ToBuffered(Pixels.toImage(data))

    var label: Option[String] = None
    var note: Option[String]  = None
    stamper.foreach { st =>
      val stem = fileStem(dest)
      val id = resolverLock.synchronized {
        Lookup.identify(resolver, headerObject, headerCoords, stem)
      }
      if id.label != Label.plain(stem) then label = Some(id.label.title)
      note = id.note
      img = st.resizeAndLabel(img, id.label)
    }

    Option(dest.getParent).foreach { parent =>
      try Files.createDirectories(parent)
      catch
        case e: Exception =>
          throw new XisfError(s"cannot create output folder: ${Xisf.msg(e)}")
    }
    val file = dest.toFile
    val ok =
      try ImageIO.write(img, "png", file)
      catch case e: Exception => throw new XisfError(s"cannot create $dest: ${Xisf.msg(e)}")
    if !ok then throw new XisfError("PNG encoding failed: no PNG writer for this image type")

    Outcome(true, label, note)

  private[astroscalapng] def image8ToBuffered(img: Image8): BufferedImage =
    val w = img.width
    val h = img.height
    img.channels match
      case 1 =>
        if img.pixels.length != w * h then throw new XisfError("pixel buffer size mismatch")
        val out  = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val dest = out.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
        System.arraycopy(img.pixels, 0, dest, 0, img.pixels.length)
        out
      case 3 =>
        if img.pixels.length != w * h * 3 then throw new XisfError("pixel buffer size mismatch")
        val out  = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR)
        val dest = out.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
        var i    = 0
        val n    = w * h
        while i < n do
          dest(i * 3) = img.pixels(i * 3 + 2)     // B
          dest(i * 3 + 1) = img.pixels(i * 3 + 1) // G
          dest(i * 3 + 2) = img.pixels(i * 3)     // R
          i += 1
        out
      case n => throw new XisfError(s"unsupported channel count $n")

  /** Recursively (or not) gather files whose extension (case-insensitive) is
    * one of `exts`, under `dir`.
    */
  def collectFiles(
      dir: Path,
      recursive: Boolean,
      exts: Vector[String],
      out: scala.collection.mutable.Builder[Path, Vector[Path]]
  ): Unit =
    val entries =
      try Some(Files.newDirectoryStream(dir))
      catch case _: Exception => None
    entries.foreach { stream =>
      try
        for path <- stream.iterator().asScala do
          if Files.isDirectory(path) then
            if recursive then collectFiles(path, recursive, exts, out)
          else if Files.isRegularFile(path) && hasExt(path, exts) then out += path
      finally stream.close()
    }

  /** Convenience wrapper returning a sorted vector, as the batch runner uses. */
  def collectFiles(dir: Path, recursive: Boolean, exts: Vector[String]): Vector[Path] =
    val b = Vector.newBuilder[Path]
    collectFiles(dir, recursive, exts, b)
    b.result().sortBy(_.toString.toLowerCase)

  private[astroscalapng] def hasExt(path: Path, exts: Vector[String]): Boolean =
    extensionOf(path).exists(e => exts.exists(_.equalsIgnoreCase(e)))

  /** Rust's `Path::extension`: nothing for a name without a dot, or whose only
    * dot starts the name.
    */
  private[astroscalapng] def extensionOf(path: Path): Option[String] =
    val name = Option(path.getFileName).map(_.toString).getOrElse("")
    val idx  = name.lastIndexOf('.')
    if idx <= 0 then None else Some(name.substring(idx + 1))

  /** Rust's `Path::file_stem`. */
  private[astroscalapng] def fileStem(path: Path): String =
    val name = Option(path.getFileName).map(_.toString).getOrElse("")
    val idx  = name.lastIndexOf('.')
    if idx <= 0 then name else name.substring(0, idx)

  /** Rust's `Path::with_extension("png")`. */
  private[astroscalapng] def withPngExtension(path: Path): Path =
    val name    = Option(path.getFileName).map(_.toString).getOrElse("")
    val newName = if extensionOf(path).isDefined then fileStem(path) + ".png" else name + ".png"
    Option(path.getParent) match
      case Some(p) => p.resolve(newName)
      case None    => Paths.get(newName)

  /** Rust's `Path::strip_prefix`, falling back to the path itself. */
  private[astroscalapng] def stripPrefix(file: Path, prefix: Path): Path =
    if file.startsWith(prefix) then
      val rel = prefix.relativize(file)
      if rel.toString.isEmpty then file else rel
    else file

  /** True when both paths refer to the same existing file (handles `.` vs
    * `./`, trailing separators, drive-letter case, ...).
    */
  private[astroscalapng] def isSameFile(a: Path, b: Path): Boolean =
    try a.toRealPath() == b.toRealPath()
    catch case _: Exception => a == b

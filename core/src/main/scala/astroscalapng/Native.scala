package astroscalapng

import java.lang.foreign.*
import java.lang.invoke.{MethodHandle, MethodHandles, MethodType}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicBoolean

import astroscalapng.Batch.{FileStatus, Options, Progress, Summary}

/** Foreign Function & Memory (Panama) bindings for astropng-core's C ABI
  * (https://github.com/peterbuitho/astropng-core), which holds the actual
  * conversion pipeline (XISF/FITS parsing, stretch, resize/stamp, WCS,
  * SIMBAD lookup, batch orchestration). Also used, via the same C ABI, by
  * the Go/Rust/Zig ports of this program.
  *
  * The struct layouts below mirror astropng_core.h exactly (field order,
  * types, and C struct alignment/padding) — there is no header parser on the
  * JVM side, so these are hand-declared, verified against the real library
  * at [[main]] test time (byte sizes 64/56/40 confirmed).
  */
private[astroscalapng] object Native:
  import MemoryLayout.PathElement.groupElement as elem

  private val MinJavaFeature = 22

  /** The Foreign Function & Memory API this code relies on (`Arena.allocateFrom`,
    * etc.) only stabilized in JDK 22; running on an older JDK doesn't
    * necessarily fail at class-load time, but throws a cryptic
    * `NoSuchMethodError` the first time a since-renamed/added method is hit.
    * Called from [[run]] itself (never from this object's own initializer —
    * throwing there would be wrapped in an uncatchable
    * `ExceptionInInitializerError` instead of surfacing as a plain
    * [[BatchError]]) so it's the first thing checked, before any other
    * `java.lang.foreign` use.
    */
  private def checkJavaVersion(): Unit =
    val feature = Runtime.version().feature()
    if feature < MinJavaFeature then
      throw new BatchError(
        s"This build needs Java $MinJavaFeature or newer, but is running on Java $feature.\n\n${javaUpgradeAdvice()}"
      )

  /** Platform-appropriate one-liner to install a current JDK, since the exact
    * command (and package manager) differs by OS.
    */
  private def javaUpgradeAdvice(): String =
    val os = System.getProperty("os.name", "").toLowerCase
    val command =
      if os.contains("win") then "winget install EclipseAdoptium.Temurin.25.JDK"
      else if os.contains("mac") then "brew install --cask temurin@25"
      else "curl -s \"https://get.sdkman.io\" | bash && sdk install java 25-tem"
    s"""Install a newer JDK (Temurin 25 recommended) and try again:
       |  $command
       |Or download it manually from https://adoptium.net/temurin/releases/""".stripMargin

  private val linker = Linker.nativeLinker()

  private val lookup: SymbolLookup =
    val libPath = locateLibrary()
    System.load(libPath.toAbsolutePath.toString)
    SymbolLookup.loaderLookup()

  /** Finds the shared library, checking (in order): an explicit override, a
    * `lib/` folder next to the running jar (where release packaging places
    * it), `third_party/astropng-core/lib/` relative to the working directory
    * (dev convenience, matching `scripts/build-core.sh`'s output), then a
    * bare library name for the system loader to resolve.
    */
  private def locateLibrary(): Path =
    val libName = System.mapLibraryName("astropng_core")
    val candidates = List(
      Option(System.getenv("ASTROPNG_CORE_LIB")).map(Paths.get(_)),
      appDir().map(_.resolve("lib").resolve(libName)),
      Some(Paths.get("third_party", "astropng-core", "lib", libName))
    ).flatten
    candidates.find(Files.isRegularFile(_)).getOrElse(Paths.get(libName))

  /** The folder the application was started from: the JVM equivalent of
    * Rust's `current_exe().parent()`. With sbt-native-packager this is the
    * `lib` folder's parent (the staged app root).
    */
  private def appDir(): Option[Path] =
    try
      val src = classOf[Native.type].getProtectionDomain.getCodeSource
      Option(src).map(s => Paths.get(s.getLocation.toURI)).flatMap(p => Option(p.getParent).map(_.getParent))
    catch case _: Exception => None

  // --- struct layouts, matching astropng_core.h ---------------------------

  private val OPTIONS: StructLayout = MemoryLayout.structLayout(
    ValueLayout.ADDRESS.withName("input_dir"),
    ValueLayout.ADDRESS.withName("output_dir"),
    ValueLayout.JAVA_BOOLEAN.withName("recursive"),
    ValueLayout.JAVA_BOOLEAN.withName("overwrite"),
    ValueLayout.JAVA_BOOLEAN.withName("resize4k"),
    ValueLayout.JAVA_BOOLEAN.withName("png_only"),
    MemoryLayout.paddingLayout(4),
    ValueLayout.ADDRESS.withName("font_path"),
    ValueLayout.JAVA_BOOLEAN.withName("lookup"),
    MemoryLayout.paddingLayout(7),
    ValueLayout.ADDRESS.withName("files"),
    ValueLayout.JAVA_LONG.withName("files_len"),
    ValueLayout.JAVA_LONG.withName("concurrency")
  )

  private val PROGRESS: StructLayout = MemoryLayout.structLayout(
    ValueLayout.JAVA_LONG.withName("index"),
    ValueLayout.JAVA_LONG.withName("total"),
    ValueLayout.ADDRESS.withName("rel_path"),
    ValueLayout.JAVA_INT.withName("status"),
    MemoryLayout.paddingLayout(4),
    ValueLayout.ADDRESS.withName("status_message"),
    ValueLayout.ADDRESS.withName("label"),
    ValueLayout.ADDRESS.withName("note")
  )

  private val SUMMARY: StructLayout = MemoryLayout.structLayout(
    ValueLayout.JAVA_LONG.withName("total"),
    ValueLayout.JAVA_INT.withName("converted"),
    ValueLayout.JAVA_INT.withName("skipped"),
    ValueLayout.JAVA_INT.withName("failed"),
    ValueLayout.JAVA_BOOLEAN.withName("cancelled"),
    MemoryLayout.paddingLayout(3),
    ValueLayout.ADDRESS.withName("warnings"),
    ValueLayout.JAVA_LONG.withName("warnings_len")
  )

  private def off(layout: StructLayout, name: String): Long = layout.byteOffset(elem(name))

  // --- downcall handles -----------------------------------------------

  private def handle(name: String, desc: FunctionDescriptor): MethodHandle =
    linker.downcallHandle(lookup.find(name).orElseThrow(() => new LinkageError(s"symbol not found: $name")), desc)

  private val hVersion = handle("astropng_version", FunctionDescriptor.of(ValueLayout.ADDRESS))
  private val hCancelNew = handle("astropng_cancel_token_new", FunctionDescriptor.of(ValueLayout.ADDRESS))
  private val hCancelCancel =
    handle("astropng_cancel_token_cancel", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
  private val hCancelFree = handle("astropng_cancel_token_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
  private val hRun = handle(
    "astropng_run",
    FunctionDescriptor.of(
      ValueLayout.JAVA_INT,
      ValueLayout.ADDRESS, // opts
      ValueLayout.ADDRESS, // cancel
      ValueLayout.ADDRESS, // callback
      ValueLayout.ADDRESS, // user_data
      ValueLayout.ADDRESS, // out_summary
      ValueLayout.ADDRESS // out_error
    )
  )
  private val hSummaryFree = handle("astropng_summary_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
  private val hStringFree = handle("astropng_string_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

  def version(): String =
    val ptr = hVersion.invoke().asInstanceOf[MemorySegment]
    readCString(ptr, 64)

  private def readCString(ptr: MemorySegment, maxLen: Long): String =
    if ptr == MemorySegment.NULL then "" else ptr.reinterpret(maxLen).getString(0)

  private def readCStringOpt(ptr: MemorySegment, maxLen: Long): Option[String] =
    if ptr == MemorySegment.NULL then None else Some(readCString(ptr, maxLen))

  private val StrMax = 1L << 20 // strings from the core are short; this is just a safety cap

  /** Run the whole batch via astropng-core. `report` is called once per
    * file, in completion order, from a single thread (astropng_run
    * guarantees callback invocations never overlap). `cancel` is polled from
    * a small watcher thread, same effect as before.
    */
  def run(opts: Options, cancel: AtomicBoolean, report: Progress => Unit): Summary =
    checkJavaVersion()
    Arena.ofConfined().nn { arena =>
      val cOpts = toCOptions(arena, opts)

      val cancelToken = hCancelNew.invoke().asInstanceOf[MemorySegment]
      val watcherStop = new AtomicBoolean(false)
      val watcher = new Thread(() =>
        while !watcherStop.get() do
          if cancel.get() then
            hCancelCancel.invoke(cancelToken)
            watcherStop.set(true)
          else Thread.sleep(20)
      )
      watcher.setDaemon(true)
      watcher.start()

      try
        // Scala objects don't compile their methods as JVM `static` (only
        // top-level objects get a separate static-forwarder mirror class,
        // and even then it's not reachable via `classOf[X.type]`), so bind
        // an instance method handle to the singleton instead of findStatic.
        val cbHandle = MethodHandles.lookup
          .findVirtual(
            Callback.getClass,
            "invoke",
            MethodType.methodType(java.lang.Void.TYPE, classOf[MemorySegment], classOf[MemorySegment])
          )
          .bindTo(Callback)
        val cbId = Callback.register(report)
        try
          val cbStub = linker.upcallStub(
            cbHandle,
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            arena
          )
          val userData = MemorySegment.ofAddress(cbId)

          val outSummary = arena.allocate(ValueLayout.ADDRESS)
          val outError = arena.allocate(ValueLayout.ADDRESS)

          val rc = hRun
            .invoke(cOpts, cancelToken, cbStub, userData, outSummary, outError)
            .asInstanceOf[Int]

          if rc != 0 then
            val errPtr = outError.get(ValueLayout.ADDRESS, 0)
            val msg = readCString(errPtr, StrMax)
            if errPtr != MemorySegment.NULL then hStringFree.invoke(errPtr)
            throw new BatchError(msg)

          val summaryPtr = outSummary.get(ValueLayout.ADDRESS, 0).reinterpret(SUMMARY.byteSize())
          try fromCSummary(summaryPtr)
          finally hSummaryFree.invoke(summaryPtr)
        finally Callback.unregister(cbId)
      finally
        watcherStop.set(true)
        hCancelFree.invoke(cancelToken)
    }

  extension [A](a: Arena) private def nn[B](f: Arena => B): B = try f(a) finally a.close()

  private def toCOptions(arena: Arena, opts: Options): MemorySegment =
    val seg = arena.allocate(OPTIONS)
    seg.set(ValueLayout.ADDRESS, off(OPTIONS, "input_dir"), arena.allocateFrom(opts.inputDir.toString))
    seg.set(
      ValueLayout.ADDRESS,
      off(OPTIONS, "output_dir"),
      opts.outputDir.map(p => arena.allocateFrom(p.toString)).getOrElse(MemorySegment.NULL)
    )
    seg.set(ValueLayout.JAVA_BOOLEAN, off(OPTIONS, "recursive"), opts.recursive)
    seg.set(ValueLayout.JAVA_BOOLEAN, off(OPTIONS, "overwrite"), opts.overwrite)
    seg.set(ValueLayout.JAVA_BOOLEAN, off(OPTIONS, "resize4k"), opts.resize4k)
    seg.set(ValueLayout.JAVA_BOOLEAN, off(OPTIONS, "png_only"), opts.pngOnly)
    seg.set(
      ValueLayout.ADDRESS,
      off(OPTIONS, "font_path"),
      opts.font.map(p => arena.allocateFrom(p.toString)).getOrElse(MemorySegment.NULL)
    )
    seg.set(ValueLayout.JAVA_BOOLEAN, off(OPTIONS, "lookup"), opts.lookup)
    if opts.files.nonEmpty then
      val arr = arena.allocate(ValueLayout.ADDRESS, opts.files.length)
      opts.files.zipWithIndex.foreach { (p, i) =>
        arr.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(p.toString))
      }
      seg.set(ValueLayout.ADDRESS, off(OPTIONS, "files"), arr)
      seg.set(ValueLayout.JAVA_LONG, off(OPTIONS, "files_len"), opts.files.length.toLong)
    else
      seg.set(ValueLayout.ADDRESS, off(OPTIONS, "files"), MemorySegment.NULL)
      seg.set(ValueLayout.JAVA_LONG, off(OPTIONS, "files_len"), 0L)
    seg.set(ValueLayout.JAVA_LONG, off(OPTIONS, "concurrency"), opts.concurrency.toLong)
    seg

  private def fromCSummary(seg: MemorySegment): Summary =
    val warningsLen = seg.get(ValueLayout.JAVA_LONG, off(SUMMARY, "warnings_len"))
    val warningsPtr = seg.get(ValueLayout.ADDRESS, off(SUMMARY, "warnings"))
    val warnings: Vector[String] =
      if warningsPtr == MemorySegment.NULL || warningsLen == 0 then Vector.empty
      else
        val arr = warningsPtr.reinterpret(warningsLen * ValueLayout.ADDRESS.byteSize())
        (0 until warningsLen.toInt).toVector.map { i =>
          readCString(arr.getAtIndex(ValueLayout.ADDRESS, i), StrMax)
        }
    Summary(
      total = seg.get(ValueLayout.JAVA_LONG, off(SUMMARY, "total")).toInt,
      converted = seg.get(ValueLayout.JAVA_INT, off(SUMMARY, "converted")),
      skipped = seg.get(ValueLayout.JAVA_INT, off(SUMMARY, "skipped")),
      failed = seg.get(ValueLayout.JAVA_INT, off(SUMMARY, "failed")),
      cancelled = seg.get(ValueLayout.JAVA_BOOLEAN, off(SUMMARY, "cancelled")),
      warnings = warnings
    )

  /** Registry mapping an opaque id (passed as the callback's `user_data`) to
    * the Scala closure to invoke — the JVM equivalent of Go's `cgo.Handle` /
    * Zig's stack-local context pointer: the JVM's garbage collector can move
    * or reclaim ordinary objects, so a Scala closure can't safely be
    * referenced directly from `user_data`; a small synchronized map keyed by
    * a plain long index avoids that entirely.
    */
  private object Callback:
    private val registry = scala.collection.mutable.Map.empty[Long, Progress => Unit]
    private var nextId = 0L

    def register(report: Progress => Unit): Long = synchronized {
      val id = nextId
      nextId += 1
      registry(id) = report
      id
    }

    def unregister(id: Long): Unit = synchronized { registry.remove(id) }

    def invoke(progress: MemorySegment, userData: MemorySegment): Unit =
      val id = userData.address()
      val reportOpt = synchronized(registry.get(id))
      reportOpt.foreach { report =>
        val seg = progress.reinterpret(PROGRESS.byteSize())
        val status = seg.get(ValueLayout.JAVA_INT, off(PROGRESS, "status")) match
          case 0 => FileStatus.Ok
          case 1 => FileStatus.Skipped
          case _ =>
            val msg = readCStringOpt(seg.get(ValueLayout.ADDRESS, off(PROGRESS, "status_message")), StrMax)
            FileStatus.Failed(msg.getOrElse("unknown error"))
        report(
          Progress(
            index = seg.get(ValueLayout.JAVA_LONG, off(PROGRESS, "index")).toInt,
            total = seg.get(ValueLayout.JAVA_LONG, off(PROGRESS, "total")).toInt,
            rel = Paths.get(readCString(seg.get(ValueLayout.ADDRESS, off(PROGRESS, "rel_path")), StrMax)),
            status = status,
            label = readCStringOpt(seg.get(ValueLayout.ADDRESS, off(PROGRESS, "label")), StrMax),
            note = readCStringOpt(seg.get(ValueLayout.ADDRESS, off(PROGRESS, "note")), StrMax)
          )
        )
      }

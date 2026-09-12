package astroscalapng.cli

import astroscalapng.Batch.{FileStatus, Options}
import astroscalapng.{Batch, BatchError, Version}

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicBoolean

/** astroscalapng command-line interface. Port of `src/main.rs`. */
object Main:

  def main(args: Array[String]): Unit = System.exit(run(args))

  def run(args: Array[String]): Int =
    var opts       = Options(lookup = true)
    var inputDir: Option[String]  = None
    var outputDir: Option[String] = None

    var i = 0
    while i < args.length do
      val a = args(i)
      a match
        case "--recursive" | "-r"      => opts = opts.copy(recursive = true)
        case "--overwrite"             => opts = opts.copy(overwrite = true)
        case "--resize4k" | "-resize4k" => opts = opts.copy(resize4k = true)
        case "--png-only"              => opts = opts.copy(pngOnly = true)
        case "--filename" | "--no-lookup" | "--offline" => opts = opts.copy(lookup = false)
        case "-j" | "--concurrency" =>
          i += 1
          parsePositive(if i < args.length then Some(args(i)) else None) match
            case Some(n) => opts = opts.copy(concurrency = n)
            case None =>
              Console.err.println("-j requires a positive integer")
              usage()
              return 2
        case _ if a.startsWith("-j") =>
          parsePositive(Some(a.substring(2))) match
            case Some(n) => opts = opts.copy(concurrency = n)
            case None =>
              Console.err.println("-j requires a positive integer")
              usage()
              return 2
        case "--font" =>
          i += 1
          val f = if i < args.length then Some(args(i)) else None
          f.filter(_.nonEmpty) match
            case Some(path) => opts = opts.copy(font = Some(Paths.get(path)))
            case None =>
              Console.err.println("--font requires a path to a .ttf/.otf file")
              usage()
              return 2
        case _ if a.startsWith("--font=") =>
          val f = a.substring("--font=".length)
          if f.isEmpty then
            Console.err.println("--font requires a path to a .ttf/.otf file")
            usage()
            return 2
          opts = opts.copy(font = Some(Paths.get(f)))
        case "--version" | "-V" =>
          println(s"astroscalapng ${Version.value}")
          return 0
        case "--help" | "-h" | "/?" =>
          usage()
          return 0
        case _ =>
          if a.startsWith("-") then
            Console.err.println(s"Unknown option: $a")
            usage()
            return 2
          // Positional: existing files are inputs to process; anything else is
          // a folder (input first, then output).
          if Files.isRegularFile(Paths.get(a)) then
            opts = opts.copy(files = opts.files :+ Paths.get(a))
          else if inputDir.isEmpty && opts.files.isEmpty then inputDir = Some(a)
          else if outputDir.isEmpty then outputDir = Some(a)
          else
            Console.err.println(s"Unexpected argument: $a")
            usage()
            return 2
      i += 1

    // "astroscalapng out_dir a.xisf": with explicit files the only folder that
    // makes sense is the output folder.
    if opts.files.nonEmpty && outputDir.isEmpty then
      outputDir = inputDir
      inputDir = None
    // No input folder given: work on the current directory.
    opts = opts.copy(
      inputDir = Paths.get(inputDir.getOrElse(".")),
      outputDir = outputDir.map(Paths.get(_))
    )

    val kind = opts.inputKind
    val verb = if opts.pngOnly then "Processed" else "Converted"

    val summary =
      try
        Batch.run(
          opts,
          new AtomicBoolean(false),
          p =>
            val rel = p.rel.toString
            p.status match
              case FileStatus.Ok =>
                p.label match
                  case Some(label) => println(s"OK    $rel  ->  $label")
                  case None        => println(s"OK    $rel")
              case FileStatus.Skipped   => println(s"SKIP  $rel")
              case FileStatus.Failed(e) => println(s"ERROR $rel: $e")
            p.note.foreach(note => println(s"      note: $note"))
        )
      catch
        case e: BatchError =>
          Console.err.println(e.getMessage)
          return 2

    if summary.total == 0 then
      println(s"No $kind files found.")
      return 0

    println()
    println(
      s"$verb: ${summary.converted}   Skipped: ${summary.skipped}   Failed: ${summary.failed}"
    )
    summary.warnings.foreach(w => println(s"WARNING: $w"))

    if summary.failed > 0 then 1 else 0

  private def parsePositive(s: Option[String]): Option[Int] =
    s.flatMap { v =>
      try
        val n = Integer.parseInt(v)
        if n >= 1 then Some(n) else None
      catch case _: NumberFormatException => None
    }

  private def usage(): Unit =
    Console.err.println(
      s"""astroscalapng ${Version.value} - batch convert XISF and FITS astronomical images to PNG
         |
         |Usage:
         |  astroscalapng [input_dir] [output_dir] [--recursive|-r] [--overwrite] [--resize4k] [--filename] [-j N]
         |  astroscalapng [input_dir] [output_dir] --png-only [--recursive|-r] [--overwrite] [--filename]
         |  astroscalapng <file>... [output_dir] [--overwrite] [--resize4k] [--filename]
         |
         |Converts every .xisf, .fits, .fit and .fts file found in input_dir, or
         |exactly the files given (.png files are only resized/stamped).
         |If input_dir is omitted, the current folder is used.
         |If output_dir is omitted, PNGs are written next to their source files.
         |
         |Options:
         |  -r, --recursive   recurse into subfolders (output mirrors structure)
         |      --overwrite    overwrite existing .png files (default: skip)
         |      --resize4k     scale each PNG to exactly 3840x2160 (aspect kept,
         |                     center-cropped, no padding) and stamp the object
         |                     name bottom-right. The object is identified from
         |                     the header OBJECT keyword and/or a catalogue id in
         |                     the file name (M31, NGC_7000, Sh2-155, ...), looked
         |                     up via CDS Sesame/SIMBAD, and stamped as e.g.
         |                     "Andromeda Galaxy (M 31)" plus NGC/IC ids, type
         |                     and coordinates. Falls back to the file name.
         |      --filename     stamp the plain file name: no header OBJECT, no
         |                     online lookup (aliases: --no-lookup, --offline)
         |      --png-only     skip XISF/FITS conversion: take existing .png files in
         |                     input_dir and only resize/annotate them (implies
         |                     --resize4k). Edited in place when output_dir is
         |                     omitted, otherwise copied there first.
         |      --font <file>  .ttf/.otf font file for the file-name stamp
         |                     (default: bundled DejaVu Sans Condensed Bold).
         |                     Also accepts --font=<file>.
         |  -j, --concurrency N  convert N files in parallel
         |                     (default: number of CPUs, capped at 8)
         |  -V, --version     print version
         |  -h, --help        show help""".stripMargin
    )

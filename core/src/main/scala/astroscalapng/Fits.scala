package astroscalapng

import java.nio.file.{Files, Path}

/** Minimal FITS reader: the image in the primary HDU of a `.fits` / `.fit` /
  * `.fts` file, as written by PixInsight, N.I.N.A., Siril, APT, SharpCap and
  * most capture software.
  *
  * Supported: BITPIX 8, 16, 32, 64, -32, -64; NAXIS = 2 (mono) or 3 with
  * NAXIS3 = 1 or 3 (RGB planes); BZERO / BSCALE; ROWORDER. Not supported:
  * images stored in extensions, tile-compressed (`.fz`) files, BINTABLEs.
  *
  * Port of `src/fits.rs`.
  */
object Fits:

  private val Block = 2880
  private val Card  = 80

  private def bail(msg: String): Nothing = throw new XisfError(msg)

  def read(path: Path): XisfImageData =
    val bytes =
      try Files.readAllBytes(path)
      catch case e: Exception => bail(s"cannot read file: ${Xisf.msg(e)}")
    parse(bytes)

  /** Parse an in-memory FITS file. */
  def parse(bytes: Array[Byte]): XisfImageData =
    if bytes.length < Block || !startsWith(bytes, "SIMPLE  =") then
      bail("Not a FITS file (missing SIMPLE card).")

    val header = Header.parse(bytes)

    if !header.bool("SIMPLE").contains(true) then bail("Non-standard FITS file (SIMPLE is not T).")
    val bitpix = header.int("BITPIX").getOrElse(bail("BITPIX missing")).toInt
    val naxis  = header.int("NAXIS").getOrElse(bail("NAXIS missing"))

    val (width, height, channels) = naxis match
      case 0 =>
        bail(
          "Primary HDU has no image data (NAXIS = 0); images in extensions are not supported."
        )
      case 2 => (header.axis(1), header.axis(2), 1)
      case 3 =>
        val c = header.axis(3)
        if c != 1 && c != 3 then
          bail(s"Unsupported NAXIS3 = $c (only 1 or 3 channel images are handled).")
        (header.axis(1), header.axis(2), c)
      case n =>
        bail(s"Unsupported NAXIS = $n (only 2-D images and 3-plane RGB are handled).")

    if width == 0 || height == 0 then bail("Image has zero size.")

    val bytesPerSample = bitpix match
      case 8     => 1
      case 16    => 2
      case 32    => 4
      case 64    => 8
      case -32   => 4
      case -64   => 8
      case other => bail(s"Unsupported BITPIX = $other.")

    val countL = width.toLong * height.toLong * channels.toLong
    if countL > Int.MaxValue then bail("Image dimensions overflow")
    val count   = countL.toInt
    val dataLenL = countL * bytesPerSample
    if dataLenL > Int.MaxValue then bail("Image dimensions overflow")
    val dataLen = dataLenL.toInt
    if header.dataOffset + dataLen > bytes.length then
      bail(
        s"Pixel data truncated: need $dataLen bytes after the header, file has " +
          s"${math.max(0, bytes.length - header.dataOffset)}."
      )
    val dataOff = header.dataOffset

    val bzero  = header.float("BZERO").getOrElse(0.0)
    val bscale = header.float("BSCALE").getOrElse(1.0)
    val scaled = bzero != 0.0 || bscale != 1.0

    // FITS stores the first row at the *bottom* of the image unless the writer
    // says otherwise via the (non-standard but widely used) ROWORDER keyword.
    val bottomUp = !header.string("ROWORDER").exists(_.equalsIgnoreCase("TOP-DOWN"))

    val w     = width
    val h     = height
    val c     = channels
    val plane = w * h

    // Fast paths: sample formats the rest of the pipeline reads natively.
    // Everything else (signed integers, BZERO/BSCALE) is converted to f32.
    val (format, rawData) = (bitpix, scaled) match
      case (8, false) =>
        (SampleFormat.UInt8, flipRows(bytes, dataOff, dataLen, w, h, c, 1, bottomUp))
      case (-32, false) =>
        (SampleFormat.Float32, flipRows(bytes, dataOff, dataLen, w, h, c, 4, bottomUp))
      case (-64, false) =>
        (SampleFormat.Float64, flipRows(bytes, dataOff, dataLen, w, h, c, 8, bottomUp))
      case _ =>
        val out = new Array[Byte](count * 4)
        var ch  = 0
        while ch < c do
          var y = 0
          while y < h do
            val dstY = if bottomUp then h - 1 - y else y
            var x    = 0
            while x < w do
              val i   = ch * plane + y * w + x
              val raw = readSample(bitpix, bytes, dataOff + i * bytesPerSample)
              val v   = (bzero + bscale * raw).toFloat
              val o   = (ch * plane + dstY * w + x) * 4
              val bits = java.lang.Float.floatToRawIntBits(v)
              out(o) = ((bits >>> 24) & 0xff).toByte
              out(o + 1) = ((bits >>> 16) & 0xff).toByte
              out(o + 2) = ((bits >>> 8) & 0xff).toByte
              out(o + 3) = (bits & 0xff).toByte
              x += 1
            y += 1
          ch += 1
        (SampleFormat.Float32, out)

    val objectName = header.string("OBJECT").map(_.trim).filter(_.nonEmpty)
    val coords     = Wcs.fromKeywords(header.value, width, height)

    XisfImageData(
      width = width,
      height = height,
      channels = channels,
      format = format,
      planar = true,
      bigEndian = true,
      rawData = rawData,
      `object` = objectName,
      coords = coords
    )

  private def startsWith(bytes: Array[Byte], s: String): Boolean =
    if bytes.length < s.length then false
    else
      var i = 0
      var ok = true
      while ok && i < s.length do
        if bytes(i) != s.charAt(i).toByte then ok = false
        i += 1
      ok

  /** Read one big-endian sample as a Double. */
  private def readSample(bitpix: Int, b: Array[Byte], o: Int): Double =
    def u(i: Int): Long = (b(o + i) & 0xffL)
    bitpix match
      case 8  => u(0).toDouble
      case 16 => (((u(0) << 8) | u(1)).toShort).toDouble
      case 32 => (((u(0) << 24) | (u(1) << 16) | (u(2) << 8) | u(3)).toInt).toDouble
      case 64 =>
        var v = 0L
        var i = 0
        while i < 8 do
          v = (v << 8) | u(i)
          i += 1
        v.toDouble
      case -32 =>
        val bits = ((u(0) << 24) | (u(1) << 16) | (u(2) << 8) | u(3)).toInt
        java.lang.Float.intBitsToFloat(bits).toDouble
      case -64 =>
        var v = 0L
        var i = 0
        while i < 8 do
          v = (v << 8) | u(i)
          i += 1
        java.lang.Double.longBitsToDouble(v)
      case _ => 0.0

  /** Copy planar sample bytes, reversing the row order of every plane when
    * `flip` is set.
    */
  private def flipRows(
      src: Array[Byte],
      off: Int,
      len: Int,
      w: Int,
      h: Int,
      c: Int,
      bps: Int,
      flip: Boolean
  ): Array[Byte] =
    val out = new Array[Byte](len)
    if !flip then
      System.arraycopy(src, off, out, 0, len)
      out
    else
      val row   = w * bps
      val plane = row * h
      var ch    = 0
      while ch < c do
        var y = 0
        while y < h do
          val s = ch * plane + y * row
          val d = ch * plane + (h - 1 - y) * row
          System.arraycopy(src, off + s, out, d, row)
          y += 1
        ch += 1
      out

  /** The primary header: keyword -> raw value text (comment stripped). */
  private final class Header(val cards: Vector[(String, String)], val dataOffset: Int):
    def get(key: String): Option[String] = cards.find(_._1 == key).map(_._2)

    def bool(key: String): Option[Boolean] = get(key).flatMap {
      case "T" => Some(true)
      case "F" => Some(false)
      case _   => None
    }

    def int(key: String): Option[Long] =
      get(key).flatMap { v =>
        try Some(java.lang.Long.parseLong(v))
        catch case _: NumberFormatException => None
      }

    /** FITS allows Fortran-style 'D' exponents. */
    def float(key: String): Option[Double] =
      get(key).flatMap { v =>
        try Some(java.lang.Double.parseDouble(v.replace('D', 'E').replace('d', 'E')))
        catch case _: NumberFormatException => None
      }

    /** Any value as text: strings unquoted, numbers as written. */
    def value(key: String): Option[String] =
      get(key)
        .flatMap(v => if v.startsWith("'") then string(key) else Some(v.trim))
        .filter(_.nonEmpty)

    /** A quoted string value with the quotes removed and '' unescaped. */
    def string(key: String): Option[String] =
      get(key).flatMap { v =>
        if !v.startsWith("'") then None
        else
          val body  = v.substring(1)
          val inner = if body.endsWith("'") then body.dropRight(1) else v
          Some(inner.replace("''", "'").replaceAll("\\s+$", ""))
      }

    def axis(n: Int): Int =
      val key = s"NAXIS$n"
      int(key) match
        case Some(v) if v >= 0 && v <= Int.MaxValue => v.toInt
        case Some(v)                                => bail(s"$key = $v is negative.")
        case None                                   => bail(s"$key missing.")

  private object Header:
    def parse(bytes: Array[Byte]): Header =
      val cards = Vector.newBuilder[(String, String)]
      var pos   = 0
      var done  = false
      while !done do
        if pos + Card > bytes.length then bail("Header has no END card.")
        val card = bytes.slice(pos, pos + Card)
        pos += Card
        val key = new String(card, 0, 8, "ISO-8859-1").replaceAll("\\s+$", "")
        if key == "END" then done = true
        else
          // Only "KEY     = value / comment" cards carry values; COMMENT,
          // HISTORY, CONTINUE and blank cards are ignored.
          if card(8) == '='.toByte && card(9) == ' '.toByte then
            val value = stripComment(new String(card, 10, Card - 10, "ISO-8859-1"))
            cards += (key -> value)
      val dataOffset = ((pos + Block - 1) / Block) * Block
      new Header(cards.result(), dataOffset)

  /** Drop the "/ comment" part of a value field, respecting quoted strings. */
  private[astroscalapng] def stripComment(raw: String): String =
    val field = raw.trim
    if field.startsWith("'") then
      // Find the closing quote, skipping '' escapes.
      val rest = field.substring(1)
      var i    = 0
      while i < rest.length do
        if rest.charAt(i) == '\'' then
          if i + 1 < rest.length && rest.charAt(i + 1) == '\'' then i += 2
          else return field.substring(0, i + 2)
        else i += 1
      field
    else
      val idx = field.indexOf('/')
      if idx >= 0 then field.substring(0, idx).trim else field

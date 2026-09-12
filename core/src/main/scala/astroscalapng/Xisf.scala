package astroscalapng

import java.nio.file.{Files, Path}
import java.util.Base64
import java.util.zip.{Inflater, InflaterInputStream}
import java.io.ByteArrayInputStream
import org.w3c.dom.Element

/** Anything that went wrong while decoding an image file. Mirrors Rust's
  * `XisfError`, whose `Display` is just the message.
  */
final class XisfError(message: String) extends Exception(message)

enum SampleFormat(val bytesPerSample: Int):
  case UInt8   extends SampleFormat(1)
  case UInt16  extends SampleFormat(2)
  case UInt32  extends SampleFormat(4)
  case UInt64  extends SampleFormat(8)
  case Float32 extends SampleFormat(4)
  case Float64 extends SampleFormat(8)

/** Decoded pixel payload of the first `<Image>` element in an XISF file, plus
  * the metadata needed to interpret the raw bytes. Also used to hand FITS
  * images to the shared pipeline.
  */
final case class XisfImageData(
    width: Int,
    height: Int,
    channels: Int,
    format: SampleFormat,
    /** Planar = channel-major storage; otherwise interleaved ("Normal"). */
    planar: Boolean,
    /** True when samples are stored big-endian. */
    bigEndian: Boolean,
    /** Raw, decompressed, un-shuffled sample bytes. */
    rawData: Array[Byte],
    /** Target name from the header (FITS `OBJECT` keyword or the XISF
      * `Observation:Object:Name` property), if present.
      */
    `object`: Option[String],
    /** Image centre from the plate solution or the mount target, if present. */
    coords: Option[SkyCoords]
)

/** Minimal reader for the monolithic XISF 1.0 file format, sufficient to pull
  * the first image out of a PixInsight / NINA generated `.xisf` file.
  *
  * Port of `src/xisf.rs`.
  */
object Xisf:

  private val Signature = "XISF0100".getBytes("US-ASCII")

  private def bail(msg: String): Nothing = throw new XisfError(msg)

  def read(path: Path): XisfImageData =
    val file =
      try Files.readAllBytes(path)
      catch case e: Exception => bail(s"cannot read file: ${msg(e)}")
    parse(file)

  def parse(file: Array[Byte]): XisfImageData =
    // --- Fixed 16-byte prologue ---
    if file.length < 16 || !java.util.Arrays.equals(file.slice(0, 8), Signature) then
      bail("Not an XISF 1.0 file (bad signature).")
    val headerLength: Long =
      (file(8) & 0xffL) | ((file(9) & 0xffL) << 8) | ((file(10) & 0xffL) << 16) |
        ((file(11) & 0xffL) << 24)
    // bytes 12..15 reserved

    val xmlStart = 16
    val xmlEnd   = xmlStart + headerLength
    if file.length.toLong < xmlEnd then bail("Truncated XISF header.")
    val xmlEndI = xmlEnd.toInt
    var end = xmlEndI
    // Strip trailing NUL padding before parsing.
    while end > xmlStart && file(end - 1) == 0 do end -= 1
    val xml = new String(file, xmlStart, end - xmlStart, "UTF-8")

    val doc = XmlUtil.parse(xml) match
      case Right(d) => d
      case Left(e)  => bail(s"Invalid XISF XML header: $e")

    val image = XmlUtil
      .descendants(doc.getDocumentElement)
      .find(n => XmlUtil.name(n) == "Image")
      .getOrElse(bail("No <Image> element in XISF header."))

    def attr(name: String): Option[String] = XmlUtil.attribute(image, name)

    // --- Header metadata for the stamp: target name and coordinates ---
    // FITS keywords carried over by the capture software, then XISF's own
    // properties as a fallback.
    def fitsKeyword(key: String): Option[String] =
      XmlUtil
        .children(image)
        .filter(n => XmlUtil.name(n) == "FITSKeyword")
        .find(n => XmlUtil.attribute(n, "name").exists(_.trim.equalsIgnoreCase(key)))
        .flatMap(n => XmlUtil.attribute(n, "value"))
        .map(v => trimMatches(v.trim, '\'').trim)
        .filter(_.nonEmpty)

    def property(id: String): Option[String] =
      XmlUtil
        .descendants(doc.getDocumentElement)
        .find(n => XmlUtil.name(n) == "Property" && XmlUtil.attribute(n, "id").contains(id))
        .flatMap(n => XmlUtil.text(n).map(_.trim).orElse(XmlUtil.attribute(n, "value")))
        .filter(_.nonEmpty)

    val objectName = fitsKeyword("OBJECT").orElse(property("Observation:Object:Name"))

    // --- Geometry ---
    val geometry = attr("geometry").getOrElse(bail("<Image> missing geometry attribute."))
    val geo =
      try geometry.split(":", -1).map(_.toLong)
      catch case _: NumberFormatException => bail(s"Invalid geometry '$geometry'.")
    if geo.length < 3 then bail(s"Unsupported geometry '$geometry' (expected w:h:channels).")
    val widthL    = geo(0)
    val heightL   = geo(1)
    val channelsL = geo(geo.length - 1)
    if widthL <= 0 || heightL <= 0 || channelsL <= 0 then bail(s"Invalid geometry '$geometry'.")
    if widthL > Int.MaxValue || heightL > Int.MaxValue || channelsL > Int.MaxValue then
      bail(s"Invalid geometry '$geometry'.")
    val width    = widthL.toInt
    val height   = heightL.toInt
    val channels = channelsL.toInt

    val coords = Wcs.fromKeywords(
      key =>
        fitsKeyword(key).orElse(key match
          case "RA"  => property("Observation:Center:RA")
          case "DEC" => property("Observation:Center:Dec")
          case _     => None
        ),
      width,
      height
    )

    // --- Sample format ---
    val format = attr("sampleFormat").getOrElse("UInt16") match
      case "UInt8"   => SampleFormat.UInt8
      case "UInt16"  => SampleFormat.UInt16
      case "UInt32"  => SampleFormat.UInt32
      case "UInt64"  => SampleFormat.UInt64
      case "Float32" => SampleFormat.Float32
      case "Float64" => SampleFormat.Float64
      case other     => bail(s"Unsupported sampleFormat '$other'.")

    val planar    = !attr("pixelStorage").exists(_.equalsIgnoreCase("Normal"))
    val bigEndian = attr("byteOrder").exists(_.equalsIgnoreCase("big"))

    val expectedSamples = width.toLong * height.toLong * channels.toLong
    val expectedBytes   = expectedSamples * format.bytesPerSample.toLong

    // --- Locate + decode payload ---
    val location = attr("location").getOrElse(bail("<Image> missing location attribute."))

    // compression may sit on the <Image> or on an embedded <Data> child.
    var compression: Option[String] = attr("compression")

    val loc = location.split(":", -1)
    var payload: Array[Byte] = loc(0) match
      case "attachment" =>
        if loc.length < 3 then bail(s"Malformed attachment location '$location'.")
        val position =
          try loc(1).toLong
          catch case _: NumberFormatException => bail(s"Malformed attachment location '$location'.")
        val size =
          try loc(2).toLong
          catch case _: NumberFormatException => bail(s"Malformed attachment location '$location'.")
        val endPos = position + size
        if position < 0 || size < 0 || endPos > file.length then
          bail("Attachment extends past end of file.")
        java.util.Arrays.copyOfRange(file, position.toInt, endPos.toInt)
      case "embedded" =>
        val data = XmlUtil
          .children(image)
          .find(n => XmlUtil.name(n) == "Data")
          .getOrElse(bail("Embedded location but no <Data> child."))
        if compression.isEmpty then compression = XmlUtil.attribute(data, "compression")
        val text = XmlUtil.text(data).getOrElse("")
        decodeText(text, XmlUtil.attribute(data, "encoding").getOrElse("base64"))
      case "inline" =>
        val encoding = if loc.length > 1 then loc(1) else "base64"
        val text     = XmlUtil.text(image).getOrElse("")
        decodeText(text, encoding)
      case other => bail(s"Unsupported location kind '$other'.")

    // --- Decompress + un-shuffle ---
    compression.filter(_.nonEmpty).foreach { spec =>
      payload = decompress(payload, spec)
    }

    if payload.length.toLong < expectedBytes then
      bail(s"Pixel data too small: got ${payload.length} bytes, expected $expectedBytes.")

    XisfImageData(
      width = width,
      height = height,
      channels = channels,
      format = format,
      planar = planar,
      bigEndian = bigEndian,
      rawData = payload,
      `object` = objectName,
      coords = coords
    )

  private def decodeText(text: String, encoding: String): Array[Byte] =
    val cleaned = text.filterNot(_.isWhitespace)
    encoding.toLowerCase match
      case "base64" =>
        try Base64.getDecoder.decode(cleaned)
        catch case e: IllegalArgumentException => bail(s"Invalid base64 data: ${msg(e)}")
      case "hex" =>
        hexDecode(cleaned).getOrElse(bail("Invalid hex data: odd length or invalid digit"))
      case other => bail(s"Unsupported data encoding '$other'.")

  private[astroscalapng] def hexDecode(s: String): Option[Array[Byte]] =
    if s.length % 2 != 0 then None
    else
      val out = new Array[Byte](s.length / 2)
      var i   = 0
      while i < out.length do
        val hi = Character.digit(s.charAt(i * 2), 16)
        val lo = Character.digit(s.charAt(i * 2 + 1), 16)
        if hi < 0 || lo < 0 then return None
        out(i) = ((hi << 4) | lo).toByte
        i += 1
      Some(out)

  /** grammar: `codec[+sh]:uncompressedSize[:shuffleItemSize]` */
  private[astroscalapng] def decompress(input: Array[Byte], compression: String): Array[Byte] =
    val parts = compression.split(":", -1)
    if parts.length < 2 then bail(s"Malformed compression spec '$compression'.")
    val codecSpec = parts(0).toLowerCase
    val uncompressedSize =
      try parts(1).toInt
      catch case _: NumberFormatException => bail(s"Malformed compression spec '$compression'.")

    val shuffled = codecSpec.endsWith("+sh")
    val codec    = if shuffled then codecSpec.dropRight(3) else codecSpec
    val shuffleItemSize =
      if parts.length > 2 then
        try parts(2).toInt
        catch case _: NumberFormatException => bail(s"Malformed compression spec '$compression'.")
      else 1

    var output: Array[Byte] = codec match
      // Check lz4hc before lz4 - but both decode with the same block decoder.
      case "lz4" | "lz4hc" =>
        try net.jpountz.lz4.LZ4Factory.fastestInstance().fastDecompressor().decompress(input, uncompressedSize)
        catch case e: Exception => bail(s"LZ4 decode failed: ${msg(e)}")
      case "zlib" =>
        try inflate(input, uncompressedSize)
        catch case e: Exception => bail(s"zlib decode failed: ${msg(e)}")
      case "zstd" =>
        try com.github.luben.zstd.Zstd.decompress(input, uncompressedSize)
        catch case e: Exception => bail(s"zstd decode failed: ${msg(e)}")
      case other => bail(s"Unsupported compression codec '$other'.")

    if output.length != uncompressedSize then
      bail(s"$codec decode produced ${output.length} bytes, expected $uncompressedSize.")

    if shuffled && shuffleItemSize > 1 then output = unshuffle(output, shuffleItemSize)
    output

  private def inflate(input: Array[Byte], hint: Int): Array[Byte] =
    val in  = new InflaterInputStream(new ByteArrayInputStream(input), new Inflater(), 65536)
    val out = new java.io.ByteArrayOutputStream(math.max(hint, 64))
    val buf = new Array[Byte](65536)
    var n   = in.read(buf)
    while n >= 0 do
      out.write(buf, 0, n)
      n = in.read(buf)
    in.close()
    out.toByteArray

  /** Reverse the XISF byte-shuffle: the compressed stream stores byte 0 of
    * every item, then byte 1 of every item, etc. Transpose it back.
    */
  private[astroscalapng] def unshuffle(input: Array[Byte], itemSize: Int): Array[Byte] =
    val items  = input.length / itemSize
    val output = new Array[Byte](input.length)
    var p      = 0
    var b      = 0
    while b < itemSize do
      var i = 0
      while i < items do
        output(i * itemSize + b) = input(p)
        p += 1
        i += 1
      b += 1
    // trailing bytes that don't fill a whole item (shouldn't happen) copied as-is
    var k = items * itemSize
    while k < input.length do
      output(k) = input(k)
      k += 1
    output

  private def trimMatches(s: String, c: Char): String =
    var a = 0
    var b = s.length
    while a < b && s.charAt(a) == c do a += 1
    while b > a && s.charAt(b - 1) == c do b -= 1
    s.substring(a, b)

  private[astroscalapng] def msg(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

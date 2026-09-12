package astroscalapng

/** Interleaved 8-bit samples, row-major. */
final case class Image8(width: Int, height: Int, channels: Int, pixels: Array[Byte])

/** Convert a decoded XISF image into 8-bit PNG pixel data, applying a linear
  * per-image min/max stretch to the full sample range.
  *
  * Port of `src/pixels.rs`.
  */
object Pixels:

  private def bail(msg: String): Nothing = throw new XisfError(msg)

  def toImage(img: XisfImageData): Image8 =
    if img.channels != 1 && img.channels != 3 then
      bail(s"Unsupported channel count ${img.channels} (only mono and RGB are handled).")

    val w     = img.width
    val h     = img.height
    val c     = img.channels
    val count = w * h * c

    var samples = decodeSamples(img, count)

    // Planar (channel-major) -> interleaved (pixel-major).
    if img.planar && c > 1 then
      val interleaved = new Array[Double](count)
      val plane       = w * h
      var ch          = 0
      while ch < c do
        var i = 0
        while i < plane do
          interleaved(i * c + ch) = samples(ch * plane + i)
          i += 1
        ch += 1
      samples = interleaved

    // Global min/max linear scale to [0, 255].
    var min = Double.PositiveInfinity
    var max = Double.NegativeInfinity
    var i   = 0
    while i < count do
      val v = samples(i)
      if !java.lang.Double.isNaN(v) then
        if v < min then min = v
        if v > max then max = v
      i += 1

    val pixels = new Array[Byte](count)
    if !min.isInfinity && !min.isNaN && !max.isInfinity && !max.isNaN && max > min then
      val scale = 255.0 / (max - min)
      var k     = 0
      while k < count do
        val v = samples(k)
        if java.lang.Double.isNaN(v) then pixels(k) = 0
        else
          val s = (v - min) * scale
          pixels(k) =
            if s <= 0.0 then 0.toByte
            else if s >= 255.0 then 255.toByte
            else (s + 0.5).toInt.toByte
        k += 1
    // else: flat / degenerate image -> all black (already zeroed).

    Image8(img.width, img.height, img.channels, pixels)

  private def decodeSamples(img: XisfImageData, count: Int): Array[Double] =
    val raw  = img.rawData
    val bps  = img.format.bytesPerSample
    val swap = img.bigEndian
    val out  = new Array[Double](count)

    if raw.length.toLong < count.toLong * bps then
      bail(s"Pixel data too small: got ${raw.length} bytes, expected ${count.toLong * bps}.")

    def be(o: Int, n: Int): Long =
      var v = 0L
      var i = 0
      while i < n do
        v = (v << 8) | (raw(o + i) & 0xffL)
        i += 1
      v

    def le(o: Int, n: Int): Long =
      var v = 0L
      var i = n - 1
      while i >= 0 do
        v = (v << 8) | (raw(o + i) & 0xffL)
        i -= 1
      v

    var i = 0
    while i < count do
      val o = i * bps
      out(i) = img.format match
        case SampleFormat.UInt8  => (raw(o) & 0xff).toDouble
        case SampleFormat.UInt16 => (if swap then be(o, 2) else le(o, 2)).toDouble
        case SampleFormat.UInt32 => (if swap then be(o, 4) else le(o, 4)).toDouble
        case SampleFormat.UInt64 =>
          val v = if swap then be(o, 8) else le(o, 8)
          // Rust reads this as u64; Java has no unsigned longs.
          if v >= 0 then v.toDouble else v.toDouble + 18446744073709551616.0
        case SampleFormat.Float32 =>
          java.lang.Float.intBitsToFloat((if swap then be(o, 4) else le(o, 4)).toInt).toDouble
        case SampleFormat.Float64 =>
          java.lang.Double.longBitsToDouble(if swap then be(o, 8) else le(o, 8))
      i += 1

    out

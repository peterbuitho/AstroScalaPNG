package astroscalapng

import java.awt.font.{FontRenderContext, TextAttribute}
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.{Color, Font, Graphics2D, RenderingHints}
import java.io.{ByteArrayInputStream, InputStream}
import java.nio.file.{Files, Path}

/** A fatal setup problem (bad input folder, unreadable font). Per-file problems
  * are reported through `FileStatus.Failed` instead.
  */
final class BatchError(message: String) extends Exception(message)

/** What gets stamped on the image: a title (object name or file name) and an
  * optional smaller line underneath it.
  */
final case class Label(title: String, subtitle: Option[String])

object Label:
  /** A single-line label. */
  def plain(title: String): Label = Label(title, None)

/** Post-processing: resize to exactly 3840x2160 (cover + center crop) and stamp
  * the file name in the bottom-right corner. Pure JVM: Java2D does the scaling
  * and AWT rasterises a bundled font, so no external tools are needed on any
  * platform.
  *
  * Port of `src/post.rs`. The resampler is Java2D bicubic (with progressive
  * halving on large downscales) rather than Rust's Lanczos3 - see the README.
  */
object Post:
  val TargetWidth  = 3840
  val TargetHeight = 2160

  /** Title size in pixels (matches ImageMagick's former `-pointsize 48`). */
  private[astroscalapng] val TitlePx = 48.0f

  /** Size of the optional second line (catalogue ids, type, coordinates). */
  private[astroscalapng] val SubtitlePx = 30.0f

  /** Vertical gap between the two lines. */
  private[astroscalapng] val LineGap = 14.0f

  /** Distance of the label from the right edge and bottom edge, in pixels. */
  private[astroscalapng] val MarginRight  = 60.0f
  private[astroscalapng] val MarginBottom = 120.0f

  /** Soft dark shadow offset behind the white text, so the label stays readable
    * over bright nebulosity.
    */
  private[astroscalapng] val ShadowOffset  = 2.0f
  private[astroscalapng] val ShadowOpacity = 0.7f

  private[astroscalapng] val BundledFontResource = "/fonts/DejaVuSansCondensed-Bold.ttf"

  /** The embedded stamp font's raw bytes. */
  def bundledFontBytes(): Array[Byte] =
    val in: InputStream = Option(getClass.getResourceAsStream(BundledFontResource))
      .getOrElse(throw new BatchError("bundled font resource missing"))
    try in.readAllBytes()
    finally in.close()

/** Holds the label font; create once and reuse for every image. */
final class Stamper private (private val baseFont: Font):
  import Post.*

  private val frc = new FontRenderContext(new AffineTransform, true, true)

  private def sized(px: Float): Font =
    val f = baseFont.deriveFont(px)
    // Rust's ab_glyph layout applies kerning; AWT only does when asked.
    f.deriveFont(java.util.Map.of(TextAttribute.KERNING, TextAttribute.KERNING_ON))

  /** Scale `img` (up or down, aspect ratio kept) so it covers 3840x2160,
    * center-crop the overflow to exactly 3840x2160, and draw `label` in the
    * bottom-right corner. Mono images stay mono; RGB stays RGB; anything else
    * is converted to RGB.
    */
  def resizeAndLabel(img: BufferedImage, label: Label): BufferedImage =
    val out = Stamper.resizeToFill(img, TargetWidth, TargetHeight)
    drawLabel(out, label)
    out

  /** Draw the label right-aligned in the bottom-right corner: the subtitle (if
    * any) sits on the original single-line position and the title goes above
    * it, so nothing moves closer to the bottom edge than before.
    */
  private[astroscalapng] def drawLabel(img: BufferedImage, label: Label): Unit =
    val right    = img.getWidth.toFloat - MarginRight
    val bottom   = img.getHeight.toFloat - MarginBottom
    val maxWidth = img.getWidth.toFloat - 2.0f * MarginRight

    val g = img.createGraphics()
    try
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

      val titleMetrics    = sized(TitlePx).getLineMetrics("Hg", frc)
      var titleBaseline   = bottom - math.abs(titleMetrics.getDescent)

      label.subtitle.map(_.trim).filter(_.nonEmpty).foreach { sub =>
        val subFont     = sized(SubtitlePx)
        val subMetrics  = subFont.getLineMetrics("Hg", frc)
        val subBaseline = bottom - math.abs(subMetrics.getDescent)
        drawLine(g, sub, SubtitlePx, right, subBaseline, maxWidth)
        titleBaseline = subBaseline - subMetrics.getAscent - LineGap
      }

      if label.title.trim.nonEmpty then
        drawLine(g, label.title, TitlePx, right, titleBaseline, maxWidth)
    finally g.dispose()

  /** Draw one line of text with its right edge at `right` and its baseline at
    * `baseline`, shrinking the font if it would exceed `max_width`.
    */
  private def drawLine(
      g: Graphics2D,
      text: String,
      px: Float,
      right: Float,
      baseline: Float,
      maxWidth: Float
  ): Unit =
    var font  = sized(px)
    var width = advance(font, text)
    if width > maxWidth && width > 0.0f then
      font = sized(px * maxWidth / width)
      width = advance(font, text)
    val x0 = right - width

    g.setFont(font)
    // Shadow first, then the white text on top.
    g.setColor(new Color(0f, 0f, 0f, ShadowOpacity))
    g.drawString(text, x0 + ShadowOffset, baseline + ShadowOffset)
    g.setColor(Color.WHITE)
    g.drawString(text, x0, baseline)

  private def advance(font: Font, text: String): Float =
    font.getStringBounds(text, frc).getWidth.toFloat

object Stamper:
  import Post.*

  /** Use the bundled font. */
  def bundled(): Stamper =
    val bytes = bundledFontBytes()
    try new Stamper(Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes)))
    catch case e: Exception => throw new BatchError(s"bundled font is invalid: ${Xisf.msg(e)}")

  /** Use a TrueType / OpenType font file supplied by the user. */
  def fromFile(path: Path): Stamper =
    val bytes =
      try Files.readAllBytes(path)
      catch
        case e: Exception =>
          throw new BatchError(s"cannot read font file $path: ${Xisf.msg(e)}")
    try new Stamper(Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes)))
    catch
      case _: Exception =>
        throw new BatchError(s"$path is not a valid TrueType/OpenType font")

  /** True when the image is a plain 8-bit grey image (Rust's `ImageLuma8`). */
  private[astroscalapng] def isGray(img: BufferedImage): Boolean =
    img.getType == BufferedImage.TYPE_BYTE_GRAY

  /** Scale to cover `tw` x `th` keeping the aspect ratio, then centre-crop to
    * exactly that size - no padding. Mirrors `image::DynamicImage::
    * resize_to_fill`, with Java2D bicubic resampling instead of Lanczos3.
    */
  private[astroscalapng] def resizeToFill(src: BufferedImage, tw: Int, th: Int): BufferedImage =
    val outType = if isGray(src) then BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_3BYTE_BGR
    val w       = src.getWidth
    val h       = src.getHeight
    val ratio   = math.max(tw.toDouble / w, th.toDouble / h)
    val nw      = math.max(math.round(w * ratio).toInt, tw)
    val nh      = math.max(math.round(h * ratio).toInt, th)

    val scaled = resample(src, nw, nh, outType)
    val x0     = (nw - tw) / 2
    val y0     = (nh - th) / 2
    if nw == tw && nh == th then scaled
    else
      val out = new BufferedImage(tw, th, outType)
      val g   = out.createGraphics()
      try g.drawImage(scaled, 0, 0, tw, th, x0, y0, x0 + tw, y0 + th, null)
      finally g.dispose()
      out

  /** Bicubic resample. Large downscales are done by repeated halving first,
    * which avoids the aliasing a single bicubic step would leave behind and
    * lands close to what a Lanczos3 kernel produces.
    */
  private def resample(src: BufferedImage, tw: Int, th: Int, outType: Int): BufferedImage =
    var cur = src
    var cw  = src.getWidth
    var ch  = src.getHeight
    while cw / 2 >= tw && ch / 2 >= th && cw / 2 >= 1 && ch / 2 >= 1 do
      val nw = math.max(cw / 2, tw)
      val nh = math.max(ch / 2, th)
      cur = step(cur, nw, nh, outType)
      cw = nw
      ch = nh
    if cw == tw && ch == th && cur.getType == outType then cur
    else step(cur, tw, th, outType)

  private def step(src: BufferedImage, tw: Int, th: Int, outType: Int): BufferedImage =
    val out = new BufferedImage(tw, th, outType)
    val g   = out.createGraphics()
    try
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
      g.drawImage(src, 0, 0, tw, th, null)
    finally g.dispose()
    out

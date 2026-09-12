package astroscalapng

/** Sky coordinates from an image header: either the plate solution (WCS
  * keywords, i.e. where the frame really points) or the mount's target
  * coordinates (`RA`/`DEC`, `OBJCTRA`/`OBJCTDEC`). Used to cross-check the
  * object name and to identify unnamed frames.
  *
  * Port of `src/wcs.rs`.
  */
final case class SkyCoords(
    raDeg: Double,
    decDeg: Double,
    /** Half the image diagonal in degrees, when the pixel scale is known. */
    fovRadiusDeg: Option[Double],
    /** True when derived from a plate solution (WCS) rather than the mount's
      * intended target.
      */
    solved: Boolean
):
  /** How far a named object may sit from the image centre before we doubt the
    * name: generous, because large targets are often framed off-centre and
    * mosaics point at panels, not at the catalogue position.
    */
  def toleranceDeg: Double = 2.0 + fovRadiusDeg.getOrElse(0.0)

  /** Radius for "what is at these coordinates?" searches. */
  def searchRadiusDeg: Double =
    Wcs.clamp(fovRadiusDeg.getOrElse(1.0), 0.25, 2.0)

  def separationDeg(raDeg2: Double, decDeg2: Double): Double =
    Wcs.separationDeg(raDeg, decDeg, raDeg2, decDeg2)

object Wcs:

  private[astroscalapng] def clamp(v: Double, lo: Double, hi: Double): Double =
    if v < lo then lo else if v > hi then hi else v

  /** Build coordinates from header keywords. `get` returns the trimmed,
    * unquoted value of a keyword (FITS card or XISF FITSKeyword / property).
    * `width`/`height` are the image size in pixels.
    */
  def fromKeywords(get: String => Option[String], width: Int, height: Int): Option[SkyCoords] =
    def num(k: String): Option[Double] = get(k).flatMap(parseNumber)

    val w = width.toDouble
    val h = height.toDouble

    // --- Plate solution ---------------------------------------------------
    (num("CRVAL1"), num("CRVAL2")) match
      case (Some(crval1), Some(crval2)) =>
        val ctypeOk = get("CTYPE1").forall(_.toUpperCase.startsWith("RA"))
        if ctypeOk && crval1 >= 0.0 && crval1 <= 360.0 && crval2 >= -90.0 && crval2 <= 90.0 then
          // Linear part of the WCS, degrees per pixel.
          val cd: Option[Array[Double]] = (num("CD1_1"), num("CD2_2")) match
            case (Some(a), Some(d)) =>
              Some(Array(a, num("CD1_2").getOrElse(0.0), num("CD2_1").getOrElse(0.0), d))
            case _ =>
              (num("CDELT1"), num("CDELT2")) match
                case (Some(dx), Some(dy)) =>
                  val rot = math.toRadians(num("CROTA2").getOrElse(0.0))
                  Some(
                    Array(
                      dx * math.cos(rot),
                      -dy * math.sin(rot),
                      dx * math.sin(rot),
                      dy * math.cos(rot)
                    )
                  )
                case _ => None

          var ra  = crval1
          var dec = crval2
          var fov: Option[Double] = None
          cd.foreach { m =>
            val Array(a, b, c, d) = m
            val scale             = math.sqrt(math.abs(a * d - b * c))
            if scale > 0.0 && scale < 1.0 then fov = Some(0.5 * scale * math.hypot(w, h))
            // Move from the reference pixel to the image centre (FITS pixels
            // are 1-based). Tangent-plane linear approximation is plenty for
            // a cross-check.
            (num("CRPIX1"), num("CRPIX2")) match
              case (Some(crpix1), Some(crpix2)) =>
                val dx     = (w + 1.0) / 2.0 - crpix1
                val dy     = (h + 1.0) / 2.0 - crpix2
                val xi     = a * dx + b * dy
                val eta    = c * dx + d * dy
                val cosDec = math.max(math.cos(math.toRadians(crval2)), 1e-6)
                ra = remEuclid(crval1 + xi / cosDec, 360.0)
                dec = clamp(crval2 + eta, -90.0, 90.0)
              case _ => ()
          }
          return Some(SkyCoords(ra, dec, fov, solved = true))
      case _ => ()

    // --- Mount / sequence target -------------------------------------------
    val raOpt = get("OBJCTRA")
      .flatMap(v => parseAngle(v, hours = true))
      .orElse(get("RA").flatMap(v => parseAngle(v, hours = false)))
      .orElse(get("OBJRA").flatMap(v => parseAngle(v, hours = false)))
    val decOpt = get("OBJCTDEC")
      .flatMap(v => parseAngle(v, hours = false))
      .orElse(get("DEC").flatMap(v => parseAngle(v, hours = false)))
      .orElse(get("OBJDEC").flatMap(v => parseAngle(v, hours = false)))

    (raOpt, decOpt) match
      case (Some(ra), Some(dec)) =>
        if ra < 0.0 || ra > 360.0 || dec < -90.0 || dec > 90.0 then None
        else
          // Field of view from pixel size (µm) and focal length (mm), if given.
          val fov = (num("XPIXSZ"), num("FOCALLEN")) match
            case (Some(pix), Some(fl)) if pix > 0.0 && fl > 0.0 =>
              val bin          = num("XBINNING").filter(_ >= 1.0).getOrElse(1.0)
              val arcsecPerPx  = 206.265 * pix * bin / fl
              Some(0.5 * arcsecPerPx / 3600.0 * math.hypot(w, h))
            case _ => None
          Some(SkyCoords(ra, dec, fov, solved = false))
      case _ => None

  private def remEuclid(v: Double, m: Double): Double =
    val r = v % m
    if r < 0.0 then r + m else r

  /** Parse a plain number (FITS allows Fortran 'D' exponents). */
  private[astroscalapng] def parseNumber(s: String): Option[Double] =
    val t = s.trim.replace('D', 'E').replace('d', 'E')
    try Some(java.lang.Double.parseDouble(t))
    catch case _: NumberFormatException => None

  /** Parse an angle in degrees. Accepts decimal degrees, or sexagesimal
    * ("05 35 17.3", "05:35:17", "+41 16 08", "-05d23m28s"). `hours` says a
    * sexagesimal (or bare decimal) value is in hours and must be scaled by 15.
    */
  def parseAngle(raw: String, hours: Boolean): Option[Double] =
    val s = raw.trim
    if s.isEmpty then return None
    val sexagesimal =
      s.contains(' ') || s.contains(':') || s.contains('h') || s.contains('d') || s.contains('m')
    if !sexagesimal then return parseNumber(s).map(v => if hours then v * 15.0 else v)

    val negative = s.startsWith("-")
    val body     = s.dropWhile(c => c == '+' || c == '-')
    val pieces   = body.split(Array(' ', ':', 'h', 'd', 'm', 's', '\'', '"')).filter(_.nonEmpty)
    val parsed   = pieces.map { p =>
      try Some(java.lang.Double.parseDouble(p))
      catch case _: NumberFormatException => None
    }
    if parsed.exists(_.isEmpty) then return None
    val parts = parsed.map(_.get)
    if parts.isEmpty || parts.length > 3 then return None

    var v = parts(0)
    if parts.length > 1 then v += parts(1) / 60.0
    if parts.length > 2 then v += parts(2) / 3600.0
    if negative then v = -v
    Some(if hours then v * 15.0 else v)

  /** Great-circle separation in degrees (haversine). */
  def separationDeg(ra1d: Double, dec1d: Double, ra2d: Double, dec2d: Double): Double =
    val ra1  = math.toRadians(ra1d)
    val dec1 = math.toRadians(dec1d)
    val ra2  = math.toRadians(ra2d)
    val dec2 = math.toRadians(dec2d)
    val s1   = math.sin((dec2 - dec1) / 2.0)
    val s2   = math.sin((ra2 - ra1) / 2.0)
    val hv   = s1 * s1 + math.cos(dec1) * math.cos(dec2) * s2 * s2
    2.0 * math.toDegrees(math.asin(clamp(math.sqrt(hv), 0.0, 1.0)))

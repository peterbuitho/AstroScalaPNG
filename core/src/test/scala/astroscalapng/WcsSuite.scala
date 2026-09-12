package astroscalapng

/** Port of the `#[cfg(test)] mod tests` in `src/wcs.rs`. */
class WcsSuite extends munit.FunSuite:

  private def kw(pairs: (String, String)*): String => Option[String] =
    val m = pairs.toMap
    k => m.get(k)

  test("angles") {
    assert(math.abs(Wcs.parseAngle("05 35 17.3", true).get - 83.822083) < 1e-4)
    assert(math.abs(Wcs.parseAngle("05:35:17", true).get - 83.820833) < 1e-4)
    assert(math.abs(Wcs.parseAngle("-05 23 28", false).get + 5.391111) < 1e-4)
    assert(math.abs(Wcs.parseAngle("+41 16 08", false).get - 41.268889) < 1e-4)
    assertEquals(Wcs.parseAngle("83.82", false), Some(83.82))
    assertEquals(Wcs.parseAngle("5.5", true), Some(82.5))
    assertEquals(Wcs.parseAngle("", false), None)
  }

  test("wcs centre and fov") {
    // 2"/px, reference pixel at the image centre.
    val m = kw(
      "CTYPE1" -> "RA---TAN",
      "CRVAL1" -> "314.7",
      "CRVAL2" -> "44.33",
      "CRPIX1" -> "400.5",
      "CRPIX2" -> "250.5",
      "CD1_1"  -> "-0.000555556",
      "CD1_2"  -> "0",
      "CD2_1"  -> "0",
      "CD2_2"  -> "0.000555556"
    )
    val c = Wcs.fromKeywords(m, 800, 500).get
    assert(c.solved)
    assert(math.abs(c.raDeg - 314.7) < 1e-6 && math.abs(c.decDeg - 44.33) < 1e-6)
    // half diagonal: 0.5 * 2"/px * hypot(800,500) px = 943.4" = 0.262 deg
    assert(math.abs(c.fovRadiusDeg.get - 0.262) < 0.002)

    // Reference pixel at the corner: centre shifts by half the field.
    val m2 = kw(
      "CRVAL1" -> "100.0",
      "CRVAL2" -> "0.0",
      "CRPIX1" -> "0.5",
      "CRPIX2" -> "0.5",
      "CDELT1" -> "-0.001",
      "CDELT2" -> "0.001"
    )
    val c2 = Wcs.fromKeywords(m2, 1000, 1000).get
    assert(math.abs(c2.raDeg - 99.5) < 1e-6 && math.abs(c2.decDeg - 0.5) < 1e-6)
  }

  test("target coords") {
    val m = kw(
      "OBJCTRA"  -> "00 42 44",
      "OBJCTDEC" -> "+41 16 08",
      "XPIXSZ"   -> "3.76",
      "FOCALLEN" -> "400",
      "RA"       -> "999"
    )
    val c = Wcs.fromKeywords(m, 6248, 4176).get
    assert(!c.solved)
    assert(math.abs(c.raDeg - 10.6833) < 1e-3)
    assert(c.fovRadiusDeg.get > 1.9 && c.fovRadiusDeg.get < 2.1)

    val m2 = kw("RA" -> "83.82", "DEC" -> "-5.39")
    val c2 = Wcs.fromKeywords(m2, 100, 100).get
    assertEquals((c2.raDeg, c2.decDeg, c2.fovRadiusDeg), (83.82, -5.39, None))

    assert(Wcs.fromKeywords(_ => None, 100, 100).isEmpty)
  }

  test("separation") {
    assert(math.abs(Wcs.separationDeg(10.0, 40.0, 10.0, 40.0)) < 1e-9)
    assert(math.abs(Wcs.separationDeg(0.0, 0.0, 1.0, 0.0) - 1.0) < 1e-9)
    assert(math.abs(Wcs.separationDeg(0.0, 89.0, 180.0, 89.0) - 2.0) < 1e-6)
  }

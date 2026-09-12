package astroscalapng

import astroscalapng.Lookup.*

/** Port of the `#[cfg(test)] mod tests` in `src/lookup.rs`. */
class LookupSuite extends munit.FunSuite:

  test("designations from names") {
    assertEquals(designationInName("M31_Andromeda_2026-09-05"), Some("M 31"))
    assertEquals(designationInName("2026-09-05_NGC7000_Ha_300s_0001"), Some("NGC 7000"))
    assertEquals(designationInName("ngc_7000 stack"), Some("NGC 7000"))
    assertEquals(designationInName("Sh2-155_RGB"), Some("Sh2-155"))
    assertEquals(designationInName("SH2_101"), Some("Sh2-101"))
    assertEquals(designationInName("IC1396_Elephant"), Some("IC 1396"))
    assertEquals(designationInName("B33_horsehead"), Some("Barnard 33"))
    assertEquals(designationInName("Messier 42"), None)
    assertEquals(designationInName("Messier42"), Some("M 42"))
    // Filter letters and exposure times must not be mistaken for catalogues.
    assertEquals(designationInName("Light_B_120s_0001"), None)
    assertEquals(designationInName("M_31_L"), None)
    assertEquals(designationInName("NGC7000A"), None)
    assertEquals(designationInName("M999"), None)
    assertEquals(designationInName("flat_2026"), None)
    // Caldwell
    assertEquals(designationInName("C7_L_300s"), Some("C 7"))
    assertEquals(designationInName("Caldwell14_RGB"), Some("C 14"))
    assertEquals(designationInName("C 7"), None) // lone letter needs adjacency
    assertEquals(designationInName("C200"), None)
  }

  test("normalisation") {
    assertEquals(normalize("M  31"), "M31")
    assertEquals(normalize("SH 2-155"), "SH2-155")
    assertEquals(normalize("Cl Melotte 22"), "MEL22")
    assertEquals(normalize("Mel 22"), "MEL22")
    assertEquals(normalize("Messier 31"), "M31")
  }

  test("sesame parse") {
    val xml = """<?xml version="1.0"?><Sesame><Target option="S"><name>M31</name>
      <Resolver name="Sc=Simbad"><otype>AGN</otype><jradeg>10.68470833</jradeg><jdedeg>41.26875</jdedeg>
      <oname>M  31</oname><alias>M 31</alias><alias>NAME Andromeda</alias><alias>NAME Andromeda Galaxy</alias>
      <alias>NAME And Nebula</alias><alias>NGC 224</alias><alias>UGC 454</alias><alias>LEDA 2557</alias>
      </Resolver></Target></Sesame>"""
    val info = parseSesame(xml).toOption.get.get
    assertEquals(info.mainId, "M 31")
    assertEquals(info.commonName, Some("Andromeda Galaxy"))
    assertEquals(info.designations, Vector("M 31", "NGC 224", "UGC 454", "PGC 2557"))

    // Caldwell number and curated name are added from the built-in table.
    val xml2 = """<Sesame><Target><name>IC342</name><Resolver name="S"><otype>G</otype>
      <jradeg>56.70</jradeg><jdedeg>68.096</jdedeg><oname>IC  342</oname>
      <alias>IC 342</alias><alias>UGC 2847</alias><alias>LEDA 13826</alias></Resolver></Target></Sesame>"""
    val ic342 = parseSesame(xml2).toOption.get.get
    assertEquals(ic342.designations, Vector("C 5", "IC 342", "UGC 2847", "PGC 13826"))
    assertEquals(ic342.commonName, Some("Hidden Galaxy"))
    assert(ic342.matches("C5"))
    assert(ic342.matches("Caldwell 5"))
    assertEquals(compose(ic342, Some("IC 342")).title, "Hidden Galaxy (IC 342)")
    assertEquals(compose(ic342, Some("C 5")).title, "Hidden Galaxy (C 5)")
    assert(info.matches("m31"))
    assert(info.matches("NGC224"))
    assert(!info.matches("NGC 7000"))
    assertEquals(fmtRa(10.68470833), "00h 42m 44s")
    assertEquals(fmtDec(41.26875), "+41° 16′ 08″")
    val label = compose(info, Some("M 31"))
    assertEquals(label.title, "Andromeda Galaxy (M 31)")
    assertEquals(
      label.subtitle,
      Some(
        "NGC 224  ·  UGC 454  ·  PGC 2557  ·  Galaxy (active nucleus)  ·  " +
          "RA 00h 42m 44s  Dec +41° 16′ 08″"
      )
    )

    val none = parseSesame(
      """<Sesame><Target><name>ZZZ</name><INFO> *** Nothing found *** </INFO></Target></Sesame>"""
    ).toOption.get
    assert(none.isEmpty)
  }

  test("morphology words") {
    assertEquals(morphologyDescription("SAB(s)cd"), Some("Spiral galaxy"))
    assertEquals(morphologyDescription("SA(s)b"), Some("Spiral galaxy"))
    assertEquals(morphologyDescription("Sc"), Some("Spiral galaxy"))
    assertEquals(morphologyDescription("SB(r)b"), Some("Barred spiral galaxy"))
    assertEquals(morphologyDescription("SB(s)m"), Some("Barred spiral galaxy"))
    assertEquals(morphologyDescription("E+0-1 pec"), Some("Elliptical galaxy"))
    assertEquals(morphologyDescription("E3"), Some("Elliptical galaxy"))
    assertEquals(morphologyDescription("S0 pec"), Some("Lenticular galaxy"))
    assertEquals(morphologyDescription("SAB0^0"), Some("Lenticular galaxy"))
    assertEquals(morphologyDescription("I0"), Some("Irregular galaxy"))
    assertEquals(morphologyDescription("IB(s)m"), Some("Irregular galaxy"))
    assertEquals(morphologyDescription("dE"), Some("Dwarf elliptical galaxy"))
    assertEquals(morphologyDescription("dSph"), Some("Dwarf spheroidal galaxy"))
    assertEquals(morphologyDescription("cD"), Some("Giant elliptical galaxy"))
    assertEquals(morphologyDescription("~"), None)
    assertEquals(morphologyDescription(""), None)

    // NGC 2403 is "AGN" to SIMBAD but SAB(s)cd morphologically.
    val xml = """<Sesame><Target><name>NGC2403</name><Resolver name="S"><otype>AGN</otype>
      <MType>SAB(s)cd</MType><jradeg>114.214</jradeg><jdedeg>65.6025</jdedeg>
      <oname>NGC  2403</oname><alias>NGC 2403</alias><alias>UGC 3918</alias></Resolver></Target></Sesame>"""
    val g = parseSesame(xml).toOption.get.get
    assertEquals(g.typeDescription, "Spiral galaxy")
    assertEquals(g.designations, Vector("C 7", "NGC 2403", "UGC 3918"))
    val label = compose(g, None)
    assertEquals(label.title, "NGC 2403")
    assert(label.subtitle.get.startsWith("C 7  ·  UGC 3918  ·  Spiral galaxy"))
  }

  test("pairs and same region") {
    def mk(main: String, aliases: Seq[String], otype: String, ra: Double, dec: Double) =
      ObjectInfo.fromAliases(main, aliases.toVector, otype, None, Some(ra), Some(dec))
    val centre = SkyCoords(170.0, 13.0, Some(0.5), solved = true)

    // Same name -> merged designations.
    val m65 = mk("M 65", Seq("M 65", "NGC 3623"), "GiP", 169.73, 13.09)
    val m66 = mk("M 66", Seq("M 66", "NGC 3627"), "AGN", 170.06, 12.99)
    assert(!sameRegion(m65, m66))
    val l = composePair(m65, Some("M 65"), m66, centre)
    assertEquals(l.title, "Leo Triplet (M 65 & M 66)")
    assert(
      l.subtitle.get.startsWith("NGC 3623  ·  Galaxy in a pair   +   NGC 3627")
    )

    // Different names -> "A & B".
    val heart = mk("IC 1805", Seq("IC 1805"), "OpC", 38.21, 61.47)
    val fish  = mk("NGC 896", Seq("NGC 896"), "HII", 36.4, 62.0)
    assert(!sameRegion(heart, fish))
    assertEquals(
      composePair(heart, Some("IC 1805"), fish, centre).title,
      "Heart Nebula (IC 1805) & Fish Head Nebula (NGC 896)"
    )

    // Same region under a variant name -> no pairing.
    val ic1396 = mk("IC 1396", Seq("IC 1396"), "OpC", 324.7, 57.5)
    val trunk =
      mk("Elephant Trunk Nebula", Seq("NAME Elephant Trunk Nebula"), "HII", 324.0, 57.5)
    assert(sameRegion(ic1396, trunk))
    val a = mk("A", Seq("NAME North America Nebula"), "HII", 0.0, 0.0)
    val b = mk("B", Seq("NAME North America"), "HII", 0.0, 0.0)
    assert(sameRegion(a, b))
  }

  test("tap ranking prefers prominent objects") {
    // Closest-first rows as SIMBAD returns them: obscure PNe inside M31 come
    // before M31 itself; the Messier object must still win.
    val tsv = "main_id\totype\tra\tdec\td\tids\n" +
      "\"[PSC2013] 9\"\t\"PN\"\t10.6835\t41.2690\t0.0009\t\"[PSC2013] 9\"\n" +
      "\"Ford M 31 574\"\t\"PN\"\t10.6873\t41.2678\t0.0022\t\"Ford M 31 574|[B2015] M31 B127-33\"\n" +
      "\"NGC  206\"\t\"Cl*\"\t10.10\t40.73\t0.7\t\"NGC   206|OB 78\"\n" +
      "\"M  31\"\t\"AGN\"\t10.6847\t41.2687\t0.9\t\"NAME Andromeda Galaxy|M  31|NGC   224|UGC   454\"\n"
    val best = pickFromTapTsv(tsv, false).get
    assertEquals(best.mainId, "M 31")
    assertEquals(best.designations(0), "M 31")
    assertEquals(best.commonName, Some("Andromeda Galaxy"))

    // Only obscure objects -> nothing worth stamping.
    val tsv2 = "main_id\totype\tra\tdec\td\tids\n\"[PSC2013] 9\"\t\"PN\"\t1\t2\t0.1\t\"[PSC2013] 9\"\n"
    assert(pickFromTapTsv(tsv2, false).isEmpty)

    // Named-nebula mode skips unnamed objects even if they are prominent,
    // rejects catalogue-ish "names", and prefers "... Nebula" over a closer
    // NGC-numbered fragment.
    val tsv3 = "main_id\totype\tra\tdec\td\tids\n" +
      "\"LBN 511\"\t\"HII\"\t1\t2\t0.01\t\"LBN 511\"\n" +
      "\"AFGL 333\"\t\"MoC\"\t1\t2\t0.015\t\"NAME AFGL 333 Cloud|AFGL 333\"\n" +
      "\"NGC  2238\"\t\"HII\"\t1\t2\t0.02\t\"NAME Rosette B|NGC  2238\"\n" +
      "\"SH  2-142\"\t\"HII\"\t1\t2\t0.03\t\"NAME Wizard Nebula|LBN 511|SH 2-142\"\n"
    val neb = pickFromTapTsv(tsv3, true).get
    assertEquals(neb.commonName, Some("Wizard Nebula"))
    assertEquals(neb.designations, Vector("Sh2-142", "LBN 511"))

    assert(isCleanName("Wizard Nebula"))
    assert(isCleanName("h Persei Cluster"))
    assert(isCleanName("Barnard's Loop"))
    assert(!isCleanName("AFGL 333 Cloud"))
    assert(!isCleanName("Rosette B"))
    assert(!isCleanName("Lo 2"))
    assert(!isCleanName("NIPSS 1548C27 IRS 1"))
  }

  /** Talks to SIMBAD. Run with: sbt "core/testOnly *LookupSuite -- --include-tags=live" */
  test("live simbad".ignore) {
    val r = new Resolver(true)
    for name <- Seq("IC 1805", "NGC 7380", "NGC 2244", "M 31", "NGC 2403", "M 82", "M 87", "NGC 5128")
    do
      val info0 = r.resolve(name).getOrElse(fail(s"$name resolves"))
      val info  = r.adoptCompanionNebula(info0)
      println(s"$name -> ${compose(info, Some(name)).title}")
      assert(r.failure.isEmpty, s"network: ${r.failure}")
  }

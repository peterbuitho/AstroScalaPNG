package astroscalapng

/** Port of the `#[cfg(test)] mod tests` in `src/catalog.rs`. */
class CatalogSuite extends munit.FunSuite:

  test("caldwell lookups") {
    assertEquals(Catalog.caldwellNumber(Seq("NGC 2403")), Some(7))
    assertEquals(Catalog.caldwellNumber(Seq("UGC 454", "NGC  884")), Some(14))
    assertEquals(Catalog.caldwellNumber(Seq("NGC 224")), None)
    assertEquals(Catalog.caldwellTarget("C7"), Some("NGC 2403"))
    assertEquals(Catalog.caldwellTarget("Caldwell 5"), Some("IC 342"))
    assertEquals(Catalog.caldwellTarget("C 14"), Some("NGC 869"))
    assertEquals(Catalog.caldwellTarget("C 110"), None)
    assertEquals(Catalog.caldwellTarget("NGC 7"), None)
    assertEquals(Catalog.CALDWELL.length, 109)
    Catalog.CALDWELL.zipWithIndex.foreach { case ((n, ds, _), i) =>
      assertEquals(n, i + 1, "Caldwell table out of order")
      assert(ds.nonEmpty)
    }
  }

  test("names") {
    assertEquals(Catalog.popularName(Seq("IC 342")), Some("Hidden Galaxy"))
    assertEquals(Catalog.popularName(Seq("NGC 2403")), None)
    assertEquals(Catalog.popularName(Seq("IC 1848")), Some("Soul Nebula"))
    assertEquals(Catalog.popularName(Seq("M  42")), Some("Orion Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 2682", "M 67")), Some("Golden Eye Cluster"))
    assertEquals(Catalog.popularName(Seq("NGC 1491")), Some("Fossil Footprint Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 1333")), Some("Embryo Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 3521")), Some("Bubble Galaxy"))
    assertEquals(Catalog.popularName(Seq("NGC 6572")), Some("Blue Racquetball Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 7027")), Some("Jewel Bug Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 7822")), Some("Teddy Bear Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 7538")), Some("Northern Lagoon Nebula"))
    assertEquals(Catalog.popularName(Seq("SH 2-158")), Some("Northern Lagoon Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 6939")), Some("Ghost Bush Cluster"))
    assertEquals(Catalog.popularName(Seq("IC 4406")), Some("Retina Nebula"))
    assertEquals(Catalog.popularName(Seq("M 65")), Some("Leo Triplet"))
    assertEquals(Catalog.popularName(Seq("M 66")), Some("Leo Triplet"))
    assertEquals(Catalog.popularName(Seq("SH 2-206")), Some("Fossil Footprint Nebula"))
    // Cross-checked against OpenNGC: a correction (was wrongly "Lobster
    // Nebula", which SIMBAD actually attaches to M 17) and two gaps.
    assertEquals(Catalog.popularName(Seq("NGC 6357")), Some("War and Peace Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 1317")), Some("Fornax B"))
    assertEquals(Catalog.popularName(Seq("NGC 4435")), Some("Eyes Galaxies"))
    assertEquals(Catalog.popularName(Seq("NGC 2537")), Some("Bear's Paw Galaxy"))
    // Every table entry must be in canonical pretty form so it matches.
    Catalog.POPULAR_NAMES.foreach { (d, _) =>
      assert(
        d.startsWith("M ") || d.startsWith("NGC ") || d.startsWith("IC ") || d.startsWith("Sh2-")
          || d.startsWith("Barnard ") || d.startsWith("LDN ") || d.startsWith("vdB ")
          || d.startsWith("UGC ") || d.startsWith("Mel ") || d.startsWith("Cr "),
        s"unexpected designation form: $d"
      )
    }
    assertEquals(Catalog.popularName(Seq("SH 2-101")), Some("Tulip Nebula"))
    assertEquals(Catalog.popularName(Seq("NGC 9999")), None)
  }

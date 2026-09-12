package astroscalapng

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.Locale
import scala.collection.mutable

/** Identify the object in an image and fetch its catalogue information from the
  * CDS Sesame name resolver (backed by SIMBAD), to stamp a proper name like
  * "Andromeda Galaxy (M 31)" instead of the bare file name.
  *
  * Sources of identity, in order of trust:
  *   1. the `OBJECT` keyword from the FITS / XISF header (what the capture
  *      software was told the target was);
  *   1. a catalogue designation found in the file name (`M31`, `NGC_7000`,
  *      `Sh2-155`, ...).
  *
  * When both exist they are cross-checked against the resolved object's alias
  * list; on disagreement the file name wins (it is what the user named the
  * image) and a note is attached. If nothing resolves, the file name is used
  * verbatim.
  *
  * Port of `src/lookup.rs`.
  */
object Lookup:

  private val SesameUrl = "https://cds.unistra.fr/cgi-bin/nph-sesame/-oxI/S?"
  private val TapUrl    = "https://simbad.cds.unistra.fr/simbad/sim-tap/sync"

  /** SIMBAD object types worth stamping when identifying a frame by its
    * coordinates: deep-sky objects, not the thousands of stars in any field.
    */
  private val DsoTypes = Vector(
    "G", "AGN", "GiG", "GiP", "GiC", "IG", "PaG", "GrG", "ClG", "SBG", "EmG", "LIN", "SyG", "Sy1",
    "Sy2", "HII", "PN", "SNR", "RNe", "DNe", "GNe", "MoC", "Cld", "ISM", "EmO", "bub", "OpC",
    "GlC", "Cl*", "As*", "SFR", "glb"
  )

  /** Nebula types: what astrophotographers actually mean when they point at a
    * cluster embedded in one (NGC 7380 = the Wizard Nebula, NGC 2244 = the
    * Rosette). SIMBAD files the cluster and the nebula as separate objects.
    */
  private val NebulaTypes = Vector(
    "HII", "RNe", "DNe", "GNe", "MoC", "Cld", "ISM", "EmO", "bub", "SNR", "SFR", "PN"
  )

  /** Objects whose SIMBAD entry may be "the cluster" while the picture is of
    * the surrounding nebula: worth looking for a named companion nebula.
    */
  private val CompanionHostTypes = Vector(
    "OpC", "Cl*", "As*", "SFR", "HII", "GNe", "ISM", "Cld", "MoC", "EmO", "RNe", "DNe"
  )

  /** How far a companion nebula's catalogue position may sit from the
    * cluster's.
    */
  private[astroscalapng] val CompanionRadiusDeg = 0.5

  /** Catalogues we recognise in file names and prefer when presenting aliases.
    * Order = display priority.
    *
    * @param keys          upper-case prefixes accepted in file names
    * @param pretty        how we print it: prefix followed by the number
    * @param simbad        prefixes SIMBAD uses in its alias list
    *                      (case-insensitive, whitespace collapsed)
    * @param max           highest valid number (sanity filter)
    * @param adjacentOnly  single-letter catalogues: the number must follow the
    *                      letter directly ("M31", not "M_31") so that filter
    *                      letters like `_B_` are not mistaken for Barnard ids
    */
  private final case class Catalogue(
      keys: Vector[String],
      pretty: String,
      simbad: Vector[String],
      max: Long,
      adjacentOnly: Boolean
  )

  private val Catalogues = Vector(
    Catalogue(Vector("M", "MESSIER"), "M ", Vector("M "), 110, true),
    // Caldwell is not in SIMBAD: no alias prefixes; numbers come from the
    // built-in table in `Catalog.scala` and queries are translated there.
    Catalogue(Vector("C", "CALDWELL"), "C ", Vector(), 109, true),
    Catalogue(Vector("NGC"), "NGC ", Vector("NGC "), 7840, false),
    Catalogue(Vector("IC"), "IC ", Vector("IC "), 5386, false),
    Catalogue(Vector("SH2", "SH"), "Sh2-", Vector("SH 2-", "SH2-"), 313, false),
    Catalogue(Vector("B", "BARNARD"), "Barnard ", Vector("Barnard "), 370, true),
    Catalogue(Vector("LBN"), "LBN ", Vector("LBN "), 1125, false),
    Catalogue(Vector("LDN"), "LDN ", Vector("LDN "), 1802, false),
    Catalogue(Vector("VDB"), "vdB ", Vector("VdB ", "vdB "), 158, false),
    Catalogue(Vector("CR", "COLLINDER"), "Cr ", Vector("Cr ", "Cl Collinder "), 471, false),
    Catalogue(Vector("MEL", "MELOTTE"), "Mel ", Vector("Cl Melotte ", "Mel "), 245, false),
    Catalogue(Vector("CED", "CEDERBLAD"), "Ced ", Vector("Ced "), 215, false),
    Catalogue(Vector("ARP"), "Arp ", Vector("APG ", "Arp "), 338, false),
    Catalogue(Vector("UGC"), "UGC ", Vector("UGC "), 12921, false),
    Catalogue(Vector("PGC"), "PGC ", Vector("LEDA ", "PGC "), 9999999L, false),
    Catalogue(Vector("HD"), "HD ", Vector("HD "), 359083, false),
    Catalogue(Vector("HIP"), "HIP ", Vector("HIP "), 120404, false)
  )

  private def fmt(pattern: String, args: Any*): String =
    String.format(Locale.ROOT, pattern, args.map(_.asInstanceOf[AnyRef])*)

  // ---------------------------------------------------------------------
  // ObjectInfo
  // ---------------------------------------------------------------------

  /** What Sesame told us about one object. */
  final case class ObjectInfo(
      /** SIMBAD main identifier, whitespace collapsed (e.g. "M 31"). */
      mainId: String,
      /** Best common name, if any (e.g. "Andromeda Galaxy"). */
      commonName: Option[String],
      /** Recognised catalogue designations, pretty-printed, display priority. */
      designations: Vector[String],
      /** Every alias, normalised for comparison. */
      aliasesNorm: Set[String],
      /** SIMBAD object type code (e.g. "G", "HII", "OpC"). */
      otype: String,
      /** Hubble morphological type for galaxies (e.g. "SAB(s)cd"), if known. */
      morphType: Option[String],
      raDeg: Option[Double],
      decDeg: Option[Double]
  ):
    /** Does `designation` (any spacing/case) refer to this object? Checks
      * genuine SIMBAD aliases, plus the built-in Caldwell number (kept out of
      * `aliasesNorm` itself: a Caldwell entry can list *several* physical
      * objects, e.g. C14 = NGC 869 & NGC 884, and folding it into the alias
      * set would make [[sameObject]] treat those two as one object).
      */
    def matches(designation: String): Boolean =
      val norm = normalize(designation)
      aliasesNorm.contains(norm) ||
      Catalog.caldwellNumber(designations).exists(n => norm == normalize(s"C $n"))

    /** Do the two records describe the same object (share any genuine SIMBAD
      * identifier)? Deliberately ignores the Caldwell number for the reason
      * given on [[matches]].
      */
    def sameObject(other: ObjectInfo): Boolean =
      aliasesNorm.exists(other.aliasesNorm.contains)

    /** Notable enough to override a name the user gave: Messier, Caldwell, NGC,
      * IC, Sharpless or Barnard, or anything with a common name. An LBN/LDN
      * entry near the pointing position is not evidence the user mislabelled
      * the image.
      */
    private[astroscalapng] def isNotable: Boolean = prominence <= 5 || commonName.isDefined

    /** Prominence tier for ranking cone-search hits: 0 = Messier ... n = lesser
      * catalogues, then "has a common name", then obscure.
      */
    private[astroscalapng] def prominence: Int =
      val tier = Catalogues.indexWhere(cat => designations.exists(_.startsWith(cat.pretty)))
      if tier >= 0 then tier
      else if commonName.isDefined then Catalogues.length
      else Int.MaxValue

    /** Plain-words type. For galaxies the Hubble morphology wins ("Spiral
      * galaxy") over SIMBAD's activity class ("Galaxy (active nucleus)"), which
      * is what a picture of NGC 2403 is about.
      */
    def typeDescription: String =
      if isGalaxyType(otype) then
        morphType.flatMap(morphologyDescription) match
          case Some(m) => return m
          case None    => ()
      otypeDescription(otype)

    /** "RA 00h 42m 44s  Dec +41° 16′ 08″", or empty if unknown. */
    def coordinates: String =
      (raDeg, decDeg) match
        case (Some(ra), Some(dec)) => s"RA ${fmtRa(ra)}  Dec ${fmtDec(dec)}"
        case _                     => ""

  object ObjectInfo:
    /** Build from a SIMBAD TAP row: main_id, '|'-separated ids, otype,
      * morphological type, ra, dec.
      */
    private[astroscalapng] def fromTap(
        mainId0: String,
        ids: String,
        otype: String,
        morph: Option[String],
        ra: Option[Double],
        dec: Option[Double]
    ): ObjectInfo =
      val collapsed = collapseWs(mainId0)
      val mainId    = if collapsed.startsWith("NAME ") then collapsed.substring(5) else collapsed
      val aliases   = ids.split("\\|", -1).map(collapseWs).filter(_.nonEmpty).toVector :+ mainId
      fromAliases(mainId, aliases, otype.trim, cleanMorph(morph), ra, dec)

    private[astroscalapng] def fromAliases(
        mainId: String,
        aliases: Vector[String],
        otype: String,
        morphType: Option[String],
        raDeg: Option[Double],
        decDeg: Option[Double]
    ): ObjectInfo =
      var designations = Vector.empty[String]
      for cat <- Catalogues; alias <- aliases do
        catalogDesignation(cat, alias).foreach { d =>
          if !designations.contains(d) then designations = designations :+ d
        }
      val aliasesNorm = aliases.map(normalize).toSet

      // Caldwell number from the built-in table, slotted in right after any
      // Messier id so it shows up early in the second line. Not added to
      // `aliasesNorm` - see the comment on `matches`/`sameObject`.
      Catalog.caldwellNumber(designations).foreach { n =>
        val c = s"C $n"
        if !designations.contains(c) then
          val pos = designations.takeWhile(_.startsWith("M ")).length
          designations = designations.patch(pos, Vector(c), 0)
      }

      // Curated / user names beat SIMBAD's "NAME" aliases, which are often
      // missing or the less common variant.
      val commonName = Catalog.popularName(designations).orElse(pickCommonName(aliases))

      ObjectInfo(mainId, commonName, designations, aliasesNorm, otype, morphType, raDeg, decDeg)

  // ---------------------------------------------------------------------
  // Resolver
  // ---------------------------------------------------------------------

  private enum ConeKind:
    /** Any deep-sky object with a recognised catalogue id or a common name. */
    case AnyDso
    /** Nebulae that have a common ("NAME ...") alias. */
    case NamedNebula

  /** Resolves names online, caching every answer for the duration of a run so a
    * folder of 300 subs of the same target costs one request.
    */
  final class Resolver(private val enabledFlag: Boolean):
    private val client: Option[HttpClient] =
      if enabledFlag then
        Some(
          HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        )
      else None

    private val cache       = mutable.Map.empty[String, Option[ObjectInfo]]
    private val nearbyCache = mutable.Map.empty[String, Option[ObjectInfo]]

    /** Set (and lookups disabled) after the first network failure. */
    var failure: Option[String] = None

    def enabled: Boolean = enabledFlag && failure.isEmpty

    /** The most prominent deep-sky object within `radiusDeg` of a position, or
      * `None` if there is nothing with a recognised catalogue id or a common
      * name there.
      */
    def nearby(raDeg: Double, decDeg: Double, radiusDeg: Double): Option[ObjectInfo] =
      cone(raDeg, decDeg, radiusDeg, ConeKind.AnyDso)

    /** The most prominent *named* nebula within `radiusDeg` of a position. */
    def nearbyNamedNebula(raDeg: Double, decDeg: Double, radiusDeg: Double): Option[ObjectInfo] =
      cone(raDeg, decDeg, radiusDeg, ConeKind.NamedNebula)

    private def cone(
        raDeg: Double,
        decDeg: Double,
        radiusDeg: Double,
        kind: ConeKind
    ): Option[ObjectInfo] =
      if !enabled then return None
      // 0.01 deg ~ 36" buckets: frames of one target share a lookup.
      val key = fmt("%s|%.2f|%.2f|%.2f", kind.toString, raDeg, decDeg, radiusDeg)
      if !nearbyCache.contains(key) then
        client match
          case None => return None
          case Some(c) =>
            coneSearch(c, raDeg, decDeg, radiusDeg, kind) match
              case Right(info) => nearbyCache.put(key, info)
              case Left(e) =>
                failure = Some(e)
                return None
      nearbyCache.get(key).flatten

    /** If `info` is a cluster/nebula without a common name, borrow the name
      * (and ids, and type) of the named nebula it sits in, if SIMBAD has one at
      * the same position. "NGC 7380" becomes "Wizard Nebula (NGC 7380)".
      */
    private[astroscalapng] def adoptCompanionNebula(info: ObjectInfo): ObjectInfo =
      if info.commonName.isDefined ||
        !CompanionHostTypes.contains(trimEndMatches(info.otype, '?'))
      then return info
      (info.raDeg, info.decDeg) match
        case (Some(ra), Some(dec)) =>
          nearbyNamedNebula(ra, dec, CompanionRadiusDeg) match
            case Some(neb) if !neb.sameObject(info) =>
              var designations = info.designations
              var i            = 0
              while i < neb.designations.length do
                val d = neb.designations(i)
                if !designations.contains(d) then designations = designations :+ d
                i += 1
              info.copy(
                commonName = neb.commonName,
                designations = designations,
                aliasesNorm = info.aliasesNorm ++ neb.aliasesNorm,
                otype = neb.otype
              )
            case _ => info
        case _ => info

    /** Look a name up. `None` when disabled, not found, or after a network
      * failure (which is recorded in `failure`).
      */
    def resolve(query0: String): Option[ObjectInfo] =
      if !enabled then return None
      val key = normalize(query0)
      if key.isEmpty then return None
      // "C 7" means Caldwell 7 to an astrophotographer; SIMBAD would not know.
      // Look up the underlying NGC/IC object instead.
      val query = Catalog.caldwellTarget(query0).getOrElse(query0)
      if !cache.contains(key) then
        client match
          case None => return None
          case Some(c) =>
            var result = fetch(c, query)
            // Sesame does not take every abbreviation we use ("Cr 399" is
            // "Collinder 399" to it); retry with the spelled-out catalogue.
            if result == Right(None) then
              spelledOut(query) match
                case Some(alt) => result = fetch(c, alt)
                case None      => ()
            result match
              case Right(info) => cache.put(key, info)
              case Left(e) =>
                failure = Some(e)
                return None
      cache.get(key).flatten

  /** Alternative spelling of a designation for the name resolver, if the
    * abbreviated form we print is not one it accepts.
    */
  private[astroscalapng] def spelledOut(query: String): Option[String] =
    val q   = collapseWs(query)
    val idx = q.indexOf(' ')
    if idx < 0 then return None
    val prefix = q.substring(0, idx)
    val rest   = q.substring(idx + 1)
    val long = prefix.toUpperCase match
      case "CR"  => "Collinder"
      case "MEL" => "Melotte"
      case "CED" => "Cederblad"
      case "B"   => "Barnard"
      case _     => return None
    Some(s"$long $rest")

  private def userAgent = s"astroscalapng/${Version.value} (+https://github.com/peterbuitho/xisf2png)"

  private def httpGet(client: HttpClient, url: String): Either[String, String] =
    try
      val req = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(12))
        .header("User-Agent", userAgent)
        .GET()
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() >= 400 then Left(s"HTTP status ${resp.statusCode()}")
      else Right(resp.body())
    catch case e: Exception => Left(Xisf.msg(e))

  private def fetch(client: HttpClient, query: String): Either[String, Option[ObjectInfo]] =
    val url = SesameUrl + percentEncode(query.trim)
    httpGet(client, url) match
      case Left(e)     => Left(s"Sesame request failed: $e")
      case Right(body) => parseSesame(body)

  /** SIMBAD TAP cone search, ranked by prominence. */
  private def coneSearch(
      client: HttpClient,
      ra: Double,
      dec: Double,
      radius: Double,
      kind: ConeKind
  ): Either[String, Option[ObjectInfo]] =
    val typeList = kind match
      case ConeKind.AnyDso      => DsoTypes
      case ConeKind.NamedNebula => NebulaTypes
    val types = typeList.map(t => s"'$t'").mkString(",")
    val nameFilter = kind match
      case ConeKind.AnyDso      => ""
      case ConeKind.NamedNebula => " AND i.ids LIKE '%NAME %'"
    val raS     = fmt("%.6f", ra)
    val decS    = fmt("%.6f", dec)
    val radiusS = fmt("%.4f", radius)
    val adql =
      s"SELECT TOP 400 b.main_id, b.otype, b.ra, b.dec, " +
        s"DISTANCE(POINT('ICRS', b.ra, b.dec), POINT('ICRS', $raS, $decS)) AS d, i.ids, b.morph_type " +
        s"FROM basic AS b JOIN ids AS i ON i.oidref = b.oid " +
        s"WHERE CONTAINS(POINT('ICRS', b.ra, b.dec), CIRCLE('ICRS', $raS, $decS, $radiusS)) = 1 " +
        s"AND b.otype IN ($types)$nameFilter ORDER BY d ASC"
    val url = s"$TapUrl?request=doQuery&lang=adql&format=tsv&query=${percentEncode(adql)}"
    httpGet(client, url) match
      case Left(e)     => Left(s"SIMBAD request failed: $e")
      case Right(body) => Right(pickFromTapTsv(body, kind == ConeKind.NamedNebula))

  /** Pick the best hit from a TAP TSV result (columns: main_id, otype, ra, dec,
    * d, ids). Rows are already distance-sorted; we take the most prominent tier
    * and, within it, the closest.
    *
    * With `requireName` (companion-nebula search) only objects with a clean
    * common name qualify, and a name ending in a type word ("... Nebula") beats
    * catalogue prominence: for a cluster inside the Rosette we want "Rosette
    * Nebula", not the NGC-numbered fragment that happens to be closest.
    */
  def pickFromTapTsv(tsv: String, requireName: Boolean): Option[ObjectInfo] =
    def unquote(s: String): String = trimEndMatches(trimStartMatches(s.trim, '"'), '"')
    // rank = (0 if name ends in a type word else 1 [named mode only], tier)
    var best: Option[((Int, Int), ObjectInfo)] = None
    val lines                                  = tsv.linesIterator.drop(1)
    var stop                                   = false
    while !stop && lines.hasNext do
      val line = lines.next()
      val cols = line.split("\t", -1)
      if cols.length >= 6 then
        val morph = if cols.length > 6 then Some(unquote(cols(6))) else None
        val info = ObjectInfo.fromTap(
          unquote(cols(0)),
          unquote(cols(5)),
          unquote(cols(1)),
          morph,
          parseDouble(cols(2).trim),
          parseDouble(cols(3).trim)
        )
        val tier = info.prominence
        if tier != Int.MaxValue then
          val rank: Option[(Int, Int)] =
            if requireName then
              info.commonName.filter(isCleanName) match
                case Some(name) => Some((if endsWithTypeWord(name) then 0 else 1, tier))
                case None       => None
            else Some((0, tier))
          rank.foreach { r =>
            // Rows come closest-first, so only a strictly better rank replaces.
            if best.forall((br, _) => lessThan(r, br)) then
              val done = r == (0, 0)
              best = Some((r, info))
              if done then stop = true
          }
    best.map(_._2)

  private def lessThan(a: (Int, Int), b: (Int, Int)): Boolean =
    a._1 < b._1 || (a._1 == b._1 && a._2 < b._2)

  private def parseDouble(s: String): Option[Double] =
    try Some(java.lang.Double.parseDouble(s))
    catch case _: Exception => None

  /** Parse Sesame's XML (`-ox` output). `Right(None)` = nothing found. */
  def parseSesame(xml: String): Either[String, Option[ObjectInfo]] =
    XmlUtil.parse(xml) match
      case Left(e) => Left(s"Sesame XML invalid: $e")
      case Right(doc) =>
        XmlUtil
          .descendants(doc.getDocumentElement)
          .find(n => XmlUtil.name(n) == "Resolver" && XmlUtil.childText(n, "oname").isDefined) match
          case None => Right(None)
          case Some(resolver) =>
            // SIMBAD prefixes common names with "NAME " in identifiers; drop it
            // for display ("NAME Horsehead Nebula" -> "Horsehead Nebula").
            val collapsed = collapseWs(XmlUtil.childText(resolver, "oname").getOrElse(""))
            val mainId = if collapsed.startsWith("NAME ") then collapsed.substring(5) else collapsed
            val otype  = XmlUtil.childText(resolver, "otype").getOrElse("").trim
            val morph  = cleanMorph(XmlUtil.childText(resolver, "MType"))
            val raDeg  = XmlUtil.childText(resolver, "jradeg").flatMap(s => parseDouble(s.trim))
            val decDeg = XmlUtil.childText(resolver, "jdedeg").flatMap(s => parseDouble(s.trim))

            val aliases = XmlUtil
              .children(resolver)
              .filter(n => XmlUtil.name(n) == "alias")
              .flatMap(XmlUtil.text)
              .map(collapseWs)
              .toVector :+ mainId

            Right(Some(ObjectInfo.fromAliases(mainId, aliases, otype, morph, raDeg, decDeg)))

  private def cleanMorph(m: Option[String]): Option[String] =
    m.map(_.trim).filter(s => s.nonEmpty && s != "~")

  /** SIMBAD object types that are galaxies (where morphology is meaningful),
    * including the active-nucleus classes whose host galaxy is what the picture
    * shows (Centaurus A is filed as a blazar).
    */
  private def isGalaxyType(otype: String): Boolean =
    trimEndMatches(otype, '?') match
      case "G" | "AGN" | "GiG" | "GiP" | "GiC" | "BiC" | "SBG" | "EmG" | "H2G" | "LSB" | "rG" |
          "SyG" | "Sy1" | "Sy2" | "LIN" | "IG" | "PaG" | "BLL" | "Bla" | "QSO" =>
        true
      case _ => false

  /** Hubble / de Vaucouleurs morphology code in plain words: "SAB(s)cd" ->
    * Spiral galaxy, "SB(r)b" -> Barred spiral galaxy, "E+0-1 pec" -> Elliptical
    * galaxy, "S0" -> Lenticular galaxy, "IB(s)m" -> Irregular galaxy, "dE" /
    * "dSph" -> Dwarf ... galaxy.
    */
  def morphologyDescription(code: String): Option[String] =
    val c = code.trim.filterNot(_.isWhitespace)
    if c.isEmpty then return None
    val (dwarf, body) =
      if c.startsWith("d") && c.length > 1 && c.charAt(1) >= 'A' && c.charAt(1) <= 'Z' then
        (true, c.substring(1))
      else (false, c)
    // Ring/spiral qualifiers like "(s)", "(r)", "(rs)" and the a-d stage follow
    // the class letters, so a prefix test on the upper-cased code is enough to
    // find the class.
    val up = body.toUpperCase

    val cls =
      if up.startsWith("SPH") || up.startsWith("DSPH") then "Spheroidal"
      else if up.startsWith("CD") then "Giant elliptical"
      else if up.startsWith("E") then "Elliptical"
      else if up.startsWith("S0") || up.startsWith("SA0") || up.startsWith("SB0") ||
        up.startsWith("SAB0")
      then "Lenticular"
      else if up.startsWith("SB") then "Barred spiral"
      else if up.startsWith("SA") || up.startsWith("S") then "Spiral"
      else if up.startsWith("I") then "Irregular"
      else if up.startsWith("RING") then "Ring"
      else return None

    (dwarf, cls) match
      case (true, "Elliptical")                      => Some("Dwarf elliptical galaxy")
      case (true, "Spheroidal")                      => Some("Dwarf spheroidal galaxy")
      case (true, "Irregular")                       => Some("Dwarf irregular galaxy")
      case (true, "Spiral") | (true, "Barred spiral") => Some("Dwarf spiral galaxy")
      case (true, _)                                 => Some("Dwarf galaxy")
      case (false, "Spheroidal")                     => Some("Spheroidal galaxy")
      case (false, "Giant elliptical")               => Some("Giant elliptical galaxy")
      case (false, "Elliptical")                     => Some("Elliptical galaxy")
      case (false, "Lenticular")                     => Some("Lenticular galaxy")
      case (false, "Barred spiral")                  => Some("Barred spiral galaxy")
      case (false, "Spiral")                         => Some("Spiral galaxy")
      case (false, "Irregular")                      => Some("Irregular galaxy")
      case (false, "Ring")                           => Some("Ring galaxy")
      case _                                         => None

  /** If `alias` is "<simbad prefix><number>" for this catalogue, return the
    * pretty form.
    */
  private def catalogDesignation(cat: Catalogue, alias: String): Option[String] =
    var i   = 0
    var out = Option.empty[String]
    while out.isEmpty && i < cat.simbad.length do
      val prefix = cat.simbad(i)
      if alias.length > prefix.length &&
        alias.substring(0, prefix.length).equalsIgnoreCase(prefix)
      then
        val rest = alias.substring(prefix.length).trim
        try
          val n = java.lang.Long.parseLong(rest)
          if n >= 1 && n <= cat.max then out = Some(s"${cat.pretty}$n")
        catch case _: NumberFormatException => ()
      i += 1
    out

  private val ConstellationAbbr = Set(
    "And", "Ant", "Aps", "Aqr", "Aql", "Ara", "Ari", "Aur", "Boo", "Cae", "Cam", "Cnc", "CVn",
    "CMa", "CMi", "Cap", "Car", "Cas", "Cen", "Cep", "Cet", "Cha", "Cir", "Col", "Com", "CrA",
    "CrB", "Crv", "Crt", "Cru", "Cyg", "Del", "Dor", "Dra", "Equ", "Eri", "For", "Gem", "Gru",
    "Her", "Hor", "Hya", "Hyi", "Ind", "Lac", "Leo", "LMi", "Lep", "Lib", "Lup", "Lyn", "Lyr",
    "Men", "Mic", "Mon", "Mus", "Nor", "Oct", "Oph", "Ori", "Pav", "Peg", "Per", "Phe", "Pic",
    "Psc", "PsA", "Pup", "Pyx", "Ret", "Sge", "Sgr", "Sco", "Scl", "Sct", "Ser", "Sex", "Tau",
    "Tel", "Tri", "TrA", "Tuc", "UMa", "UMi", "Vel", "Vir", "Vol", "Vul"
  )
  private val OtherAbbr = Set("Neb", "Gal", "Cl", "Nebul", "Amer")

  /** Choose the most presentable "NAME ..." alias. SIMBAD lists several
    * variants ("Andromeda", "Andromeda Galaxy", "And Nebula", "NORTH AMER NEB");
    * prefer a mixed-case one ending in a type word, skip abbreviations and
    * shouting.
    */
  private def pickCommonName(aliases: Vector[String]): Option[String] =
    val names = aliases.collect {
      case a if a.startsWith("NAME ") => a.substring(5).trim
    }.filter(_.nonEmpty)
    if names.isEmpty then return None

    def isShouting(n: String): Boolean =
      val letters = n.filter(_.isLetter)
      letters.length >= 4 && letters.forall(_.isUpper)

    def hasAbbreviation(n: String): Boolean =
      n.split("\\s+").exists { w0 =>
        val w = trimMatchesBy(w0, c => !c.isLetterOrDigit)
        ConstellationAbbr.contains(w) || OtherAbbr.contains(w)
      }

    // Catalogue-ish "names" (digits, lone capitals) are never used: better no
    // common name, so a companion nebula's can be adopted, than
    // "AFGL 333 Cloud (IC 1805)".
    val candidates = names.filter(isCleanName)
    val nice       = candidates.filter(n => !isShouting(n) && !hasAbbreviation(n))

    nice
      .find(endsWithTypeWord)
      .orElse(nice.headOption)
      .orElse(candidates.headOption)

  /** Words a real common name tends to end with. */
  private val TypeWords = Set(
    "nebula", "galaxy", "cluster", "cloud", "remnant", "loop", "complex", "star", "group",
    "association", "region", "filament", "chain", "triplet", "quintet", "sextet", "arc",
    "wall", "bubble", "shell", "ring", "pair", "stream", "dwarf"
  )

  private[astroscalapng] def endsWithTypeWord(name: String): Boolean =
    name.split("\\s+").filter(_.nonEmpty).lastOption.exists(w => TypeWords.contains(w.toLowerCase))

  /** A "NAME ..." alias that reads like a proper name rather than a catalogue
    * entry in disguise: "Wizard Nebula" yes; "AFGL 333 Cloud", "Rosette B",
    * "Lo 2", "NIPSS 1548C27 IRS 1" no (digits, or a lone capital letter).
    */
  private[astroscalapng] def isCleanName(name: String): Boolean =
    val ws    = name.split("\\s+").filter(_.nonEmpty)
    var words = 0
    var ok    = true
    var i     = 0
    while ok && i < ws.length do
      val w = ws(i)
      words += 1
      if w.exists(c => c >= '0' && c <= '9') then ok = false
      else
        val alnum = w.filter(_.isLetterOrDigit)
        if alnum.length == 1 && alnum.charAt(0).isUpper then ok = false
      i += 1
    ok && words > 0

  /** Find the first catalogue designation in a file name stem, e.g.
    * "2026-09-05_NGC7000_Ha_300s" -> "NGC 7000".
    */
  def designationInName(name: String): Option[String] =
    val upper = name.toUpperCase.toCharArray
    val n     = upper.length
    def isSep(c: Char): Boolean = c == ' ' || c == '_' || c == '-' || c == '.'
    def isAlpha(c: Char): Boolean = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
    def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
    def isAlnum(c: Char): Boolean = isAlpha(c) || isDigit(c)

    var found: Option[String] = None
    var i                     = 0
    while found.isEmpty && i < n do
      // Start of a letter run, not glued to a preceding letter/digit.
      if !isAlpha(upper(i)) || (i > 0 && isAlnum(upper(i - 1))) then i += 1
      else
        val start = i
        while i < n && isAlpha(upper(i)) do i += 1
        val word = new String(upper, start, i - start)

        var ci = 0
        while found.isEmpty && ci < Catalogues.length do
          val cat = Catalogues(ci)
          if cat.keys.contains(word) then
            var j    = i
            var skip = false
            // "SH" must be followed by "2" (optionally separated) to be Sharpless.
            if word == "SH" then
              if j < n && isSep(upper(j)) then j += 1
              if j < n && upper(j) == '2' then j += 1 else skip = true
            if !skip then
              val sepHere = j < n && isSep(upper(j))
              if sepHere then
                if cat.adjacentOnly then skip = true
                else j += 1
            if !skip then
              val dstart = j
              while j < n && isDigit(upper(j)) && j - dstart < 8 do j += 1
              if j == dstart || (j < n && isAlpha(upper(j))) then skip = true
              if !skip then
                val digits = new String(upper, dstart, j - dstart)
                try
                  val num = java.lang.Long.parseLong(digits)
                  if num >= 1 && num <= cat.max then found = Some(s"${cat.pretty}$num")
                catch case _: NumberFormatException => ()
          ci += 1
    found

  // ---------------------------------------------------------------------
  // identify()
  // ---------------------------------------------------------------------

  /** The finished identification for one file. */
  final case class Identification(label: Label, note: Option[String])

  /** A candidate found by name, before the coordinate check. */
  private final case class Named(
      info: ObjectInfo,
      /** The designation the user wrote (file name / header), for the title. */
      preferred: Option[String],
      note: Option[String]
  )

  /** Work out what to stamp on an image, given the header's OBJECT keyword (if
    * any), the header coordinates (plate solution or mount target, if any) and
    * the file name stem.
    */
  def identify(
      resolver: Resolver,
      headerObject: Option[String],
      coords: Option[SkyCoords],
      stem: String
  ): Identification =
    def fallback: Identification = Identification(Label.plain(stem), None)
    if !resolver.enabled then return fallback

    val fileDesig = designationInName(stem)
    val header = headerObject
      .map(_.trim)
      .map(s => trimMatches(s, '\'').trim)
      .filter(_.nonEmpty)

    val named0 = identifyByName(resolver, header, fileDesig)
    val named  = named0.map(n => n.copy(info = resolver.adoptCompanionNebula(n.info)))

    // --- Coordinates: validate the name, or identify an unnamed frame ------
    coords match
      case Some(c) =>
        named match
          case Some(nm) =>
            (nm.info.raDeg, nm.info.decDeg) match
              case (Some(ra), Some(dec)) =>
                val sep = c.separationDeg(ra, dec)
                if sep <= c.toleranceDeg then
                  // The name fits. If the named object is inside the frame but
                  // a *different* notable object sits at the centre, both are
                  // in the picture: stamp both ("Heart Nebula (IC 1805) & Fish
                  // Head Nebula (NGC 896)").
                  if sep <= c.searchRadiusDeg then
                    resolver.nearby(c.raDeg, c.decDeg, c.searchRadiusDeg) match
                      case Some(centre0) =>
                        val centre = resolver.adoptCompanionNebula(centre0)
                        if !centre.sameObject(nm.info) && centre.isNotable &&
                          !sameRegion(centre, nm.info)
                        then
                          val what = nm.preferred.getOrElse(nm.info.mainId)
                          var note = fmt(
                            "frame is centred on %s; %s is %.1f° off-centre, both in the field",
                            actualTitle(centre),
                            what,
                            sep
                          )
                          nm.note.foreach(n => note = s"$n; $note")
                          return Identification(
                            composePair(nm.info, nm.preferred, centre, c),
                            Some(note)
                          )
                      case None => ()
                  return Identification(compose(nm.info, nm.preferred), nm.note)

                // The name does not fit where the frame points. Ask SIMBAD what
                // is actually there; a prominent object wins.
                val whereFrom = if c.solved then "plate solution" else "header coordinates"
                val what      = nm.preferred.getOrElse(nm.info.mainId)
                resolver.nearby(c.raDeg, c.decDeg, c.searchRadiusDeg) match
                  case Some(actual0) =>
                    val actual = resolver.adoptCompanionNebula(actual0)
                    if !actual.sameObject(nm.info) && actual.isNotable then
                      return Identification(
                        compose(actual, None),
                        Some(
                          fmt(
                            "%s is %.1f° from the %s; the frame is centred on %s, used that",
                            what,
                            sep,
                            whereFrom,
                            actualTitle(actual)
                          )
                        )
                      )
                  case None => ()
                var note = fmt(
                  "%s is %.1f° from the %s (tolerance %.1f°)",
                  what,
                  sep,
                  whereFrom,
                  c.toleranceDeg
                )
                nm.note.foreach(n => note = s"$n; $note")
                return Identification(compose(nm.info, nm.preferred), Some(note))

              case _ =>
                return Identification(compose(nm.info, nm.preferred), nm.note)

          case None =>
            resolver.nearby(c.raDeg, c.decDeg, c.searchRadiusDeg) match
              case Some(actual0) =>
                val actual    = resolver.adoptCompanionNebula(actual0)
                val whereFrom = if c.solved then "plate solution" else "header coordinates"
                return Identification(
                  compose(actual, None),
                  Some(s"identified from the $whereFrom")
                )
              case None => ()

      case None =>
        named match
          case Some(nm) => return Identification(compose(nm.info, nm.preferred), nm.note)
          case None     => ()

    // Nothing resolved (or the network went away).
    val id = fallback
    if resolver.enabled && (header.isDefined || fileDesig.isDefined) then
      id.copy(note = Some("object not found in SIMBAD; used file name"))
    else id

  /** Header OBJECT first, cross-checked against the file name; then the file
    * name alone.
    */
  private def identifyByName(
      resolver: Resolver,
      header: Option[String],
      fileDesig: Option[String]
  ): Option[Named] =
    val fromHeader: Option[Named] = header.flatMap { h =>
      resolver.resolve(h).map { info =>
        fileDesig match
          case Some(fd) if !info.matches(fd) =>
            // Disagreement. Trust the file name if it resolves.
            resolver.resolve(fd) match
              case Some(info2) =>
                // "'NGC 7000' is NGC 7000" reads silly; only name the resolved
                // object when it adds information.
                val resolvedAs =
                  if normalize(h) == normalize(info.mainId) then "" else s" (${info.mainId})"
                Named(
                  info2,
                  Some(fd),
                  Some(s"header OBJECT is '$h'$resolvedAs but file name says $fd; used file name")
                )
              case None =>
                Named(
                  info,
                  designationInName(h),
                  Some(
                    s"header OBJECT '$h' (${info.mainId}) does not match file name designation $fd"
                  )
                )
          case Some(fd) => Named(info, Some(fd), None)
          case None     => Named(info, designationInName(h), None)
      }
    }

    fromHeader.orElse(
      fileDesig.flatMap(fd => resolver.resolve(fd).map(info => Named(info, Some(fd), None)))
    )

  /** Short human name for notes: "Great Orion Nebula (M 42)" or "NGC 7000". */
  private def actualTitle(info: ObjectInfo): String = compose(info, None).title

  /** Build the two-line label: "Common Name (Designation)" over "other ids ·
    * type · coordinates".
    */
  private[astroscalapng] def compose(info: ObjectInfo, preferred: Option[String]): Label =
    val designation = titleDesignation(info, preferred)
    val title       = titleOf(info, designation)
    var parts       = objectParts(info, designation, 3)
    val coords      = info.coordinates
    if coords.nonEmpty then parts = parts :+ coords
    Label(title, if parts.nonEmpty then Some(parts.mkString("  ·  ")) else None)

  /** Two objects sharing one frame: "Heart Nebula (IC 1805) & Fish Head Nebula
    * (NGC 896)", or, when they share a name, "Leo Triplet (M 65 & M 66)". The
    * second line carries both objects' ids and types, then the image centre.
    */
  private[astroscalapng] def composePair(
      first: ObjectInfo,
      firstPref: Option[String],
      second: ObjectInfo,
      centre: SkyCoords
  ): Label =
    val d1 = titleDesignation(first, firstPref)
    val d2 = titleDesignation(second, None)
    val title = (first.commonName, second.commonName) match
      case (Some(a), Some(b)) if normalize(a) == normalize(b) => s"$a ($d1 & $d2)"
      case _ => s"${titleOf(first, d1)} & ${titleOf(second, d2)}"
    var parts = Vector(
      objectParts(first, d1, 2).mkString("  ·  "),
      objectParts(second, d2, 2).mkString("  ·  ")
    ).filter(_.nonEmpty)
    parts = parts :+ s"RA ${fmtRa(centre.raDeg)}  Dec ${fmtDec(centre.decDeg)}"
    Label(title, Some(parts.mkString("   +   ")))

  /** Title designation: what the user wrote, else the best-known catalogue id.
    * Caldwell numbers are less recognisable than NGC/IC, so they only lead the
    * title when the user used them; otherwise they go to line two.
    */
  private def titleDesignation(info: ObjectInfo, preferred: Option[String]): String =
    preferred
      .orElse(info.designations.find(d => !d.startsWith("C ")).orElse(info.designations.headOption))
      .getOrElse(info.mainId)

  /** "Common Name (Designation)", or just the designation. */
  private def titleOf(info: ObjectInfo, designation: String): String =
    info.commonName match
      case Some(name) if normalize(name) != normalize(designation) => s"$name ($designation)"
      case _                                                       => designation

  /** Up to `maxIds` other catalogue ids plus the type, for the second line. */
  private def objectParts(info: ObjectInfo, designation: String, maxIds: Int): Vector[String] =
    val key = normalize(designation)
    var parts = info.designations.filter(d => normalize(d) != key).take(maxIds)
    val ty    = info.typeDescription
    if ty.nonEmpty then parts = parts :+ ty
    parts

  /** Do two records describe the same region under slightly different names,
    * e.g. IC 1396 "Elephant's Trunk Nebula" vs SIMBAD's separate "Elephant
    * Trunk Nebula" entry? Compares name word sets (possessives stripped): a
    * shared subset counts as the same region. Identical names do *not* count
    * (they are paired as "Name (A & B)" instead).
    */
  private[astroscalapng] def sameRegion(a: ObjectInfo, b: ObjectInfo): Boolean =
    def words(s: String): Set[String] =
      s.split("\\s+")
        .filter(_.nonEmpty)
        .map { w0 =>
          val w = if w0.endsWith("'s") then w0.dropRight(2)
          else if w0.endsWith("’s") then w0.dropRight(2)
          else w0
          w.filter(_.isLetterOrDigit).toLowerCase
        }
        .filter(_.nonEmpty)
        .toSet
    (a.commonName, b.commonName) match
      case (Some(x), Some(y)) =>
        if normalize(x) == normalize(y) then false
        else
          val wx     = words(x)
          val wy     = words(y)
          val shared = wx.intersect(wy).size
          // "Heart Nebula" vs "Fish Head Nebula" share only "nebula".
          shared >= 2 || wx.subsetOf(wy) || wy.subsetOf(wx)
      case _ => false

  // ---------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------

  /** Canonical form for comparing identifiers: upper-case, no whitespace, long
    * catalogue names shortened to the abbreviations SIMBAD also uses.
    */
  def normalize(s: String): String =
    var u = s.filterNot(_.isWhitespace).toUpperCase
    val pairs = Vector(
      ("MESSIER", "M"),
      ("CALDWELL", "C"),
      ("CLMELOTTE", "MEL"),
      ("MELOTTE", "MEL"),
      ("CLCOLLINDER", "CR"),
      ("COLLINDER", "CR"),
      ("CEDERBLAD", "CED"),
      ("LEDA", "PGC"),
      ("APG", "ARP"),
      ("SH2-", "SH2-")
    )
    for (long, short) <- pairs do
      if u.startsWith(long) then u = short + u.substring(long.length)
    u

  private[astroscalapng] def collapseWs(s: String): String =
    s.split("\\s+").filter(_.nonEmpty).mkString(" ")

  private[astroscalapng] def percentEncode(s: String): String =
    val out = new StringBuilder(s.length * 3)
    for b <- s.getBytes("UTF-8") do
      val c = (b & 0xff).toChar
      if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
        c == '-' || c == '_' || c == '.' || c == '~'
      then out.append(c)
      else out.append(fmt("%%%02X", b & 0xff))
    out.toString

  private[astroscalapng] def fmtRa(deg: Double): String =
    val hours = ((deg % 360.0) + 360.0) % 360.0 / 15.0
    val total = math.round(hours * 3600.0)
    val h     = total / 3600 % 24
    val m     = total / 60   % 60
    val s     = total        % 60
    fmt("%02dh %02dm %02ds", h, m, s)

  private[astroscalapng] def fmtDec(deg: Double): String =
    val sign  = if deg < 0.0 then '−' else '+'
    val total = math.round(math.abs(deg) * 3600.0)
    val d     = total / 3600
    val m     = total / 60 % 60
    val s     = total      % 60
    fmt("%s%02d° %02d′ %02d″", sign.toString, d, m, s)

  private def trimEndMatches(s: String, c: Char): String =
    var b = s.length
    while b > 0 && s.charAt(b - 1) == c do b -= 1
    s.substring(0, b)

  private def trimStartMatches(s: String, c: Char): String =
    var a = 0
    while a < s.length && s.charAt(a) == c do a += 1
    s.substring(a)

  private def trimMatches(s: String, c: Char): String =
    trimEndMatches(trimStartMatches(s, c), c)

  private def trimMatchesBy(s: String, p: Char => Boolean): String =
    var a = 0
    var b = s.length
    while a < b && p(s.charAt(a)) do a += 1
    while b > a && p(s.charAt(b - 1)) do b -= 1
    s.substring(a, b)

  /** Human-readable SIMBAD object types (the common ones for astrophotography;
    * unknown codes are shown as-is).
    */
  private def otypeDescription(code: String): String =
    trimEndMatches(code, '?') match
      // Galaxies
      case "G"   => "Galaxy"
      case "AGN" => "Galaxy (active nucleus)"
      case "GiG" => "Galaxy in a group"
      case "GiP" => "Galaxy in a pair"
      case "GiC" => "Galaxy in a cluster"
      case "BiC" => "Brightest cluster galaxy"
      case "IG"  => "Interacting galaxies"
      case "PaG" => "Pair of galaxies"
      case "GrG" => "Group of galaxies"
      case "CGG" => "Compact group of galaxies"
      case "ClG" => "Cluster of galaxies"
      case "SCG" => "Supercluster of galaxies"
      case "SBG" => "Starburst galaxy"
      case "EmG" => "Emission-line galaxy"
      case "H2G" => "HII galaxy"
      case "LSB" => "Low surface brightness galaxy"
      case "rG"  => "Radio galaxy"
      case "SyG" => "Seyfert galaxy"
      case "Sy1" => "Seyfert 1 galaxy"
      case "Sy2" => "Seyfert 2 galaxy"
      case "LIN" => "LINER galaxy"
      case "QSO" => "Quasar"
      case "BLL" | "Bla" => "Blazar"
      case "PoG" => "Part of a galaxy"
      // Nebulae and interstellar medium
      case "HII" => "HII region (emission nebula)"
      case "PN"  => "Planetary nebula"
      case "SNR" => "Supernova remnant"
      case "RNe" => "Reflection nebula"
      case "DNe" => "Dark nebula"
      case "GNe" | "Neb" => "Nebula"
      case "EmO" => "Emission object"
      case "MoC" => "Molecular cloud"
      case "Cld" => "Cloud"
      case "ISM" => "Interstellar medium"
      case "bub" => "Bubble"
      case "HH"  => "Herbig-Haro object"
      case "SFR" => "Star-forming region"
      case "PoC" => "Part of a cloud"
      case "glb" => "Globule"
      case "cor" => "Dense core"
      case "out" => "Outflow"
      case "sh"  => "Interstellar shell"
      case "reg" => "Region"
      // Clusters and associations
      case "OpC" => "Open cluster"
      case "GlC" => "Globular cluster"
      case "Cl*" => "Star cluster"
      case "As*" => "Stellar association"
      case "MGr" => "Moving group"
      case "St*" => "Stellar stream"
      // Stars
      case "*"   => "Star"
      case "**"  => "Double or multiple star"
      case "V*"  => "Variable star"
      case "Pe*" => "Peculiar star"
      case "Em*" => "Emission-line star"
      case "Be*" => "Be star"
      case "WR*" => "Wolf-Rayet star"
      case "Ce*" => "Cepheid variable"
      case "Mi*" => "Mira variable"
      case "LP*" => "Long-period variable"
      case "RR*" => "RR Lyrae variable"
      case "EB*" => "Eclipsing binary"
      case "SB*" => "Spectroscopic binary"
      case "Or*" => "Orion variable"
      case "TT*" => "T Tauri star"
      case "Y*O" => "Young stellar object"
      case "sg*" => "Supergiant"
      case "s*b" => "Blue supergiant"
      case "s*r" => "Red supergiant"
      case "s*y" => "Yellow supergiant"
      case "RG*" => "Red giant"
      case "AB*" => "AGB star"
      case "pA*" => "Post-AGB star"
      case "C*"  => "Carbon star"
      case "WD*" => "White dwarf"
      case "N*"  => "Neutron star"
      case "Psr" => "Pulsar"
      case "BH"  => "Black hole"
      case "XB*" => "X-ray binary"
      case "SN*" => "Supernova"
      case "No*" => "Nova"
      case "Sy*" => "Symbiotic star"
      case "PM*" => "High proper-motion star"
      case "HS*" => "Hot subdwarf"
      case "BD*" => "Brown dwarf"
      case "LM*" => "Low-mass star"
      case "Pl"  => "Exoplanet"
      // Misc
      case "gLe" => "Gravitational lens"
      case "X"   => "X-ray source"
      case "Rad" => "Radio source"
      case "IR"  => "Infrared source"
      case "UV"  => "UV source"
      case "gam" => "Gamma-ray source"
      case "err" | "?" | "" => ""
      case other => other

package astroscalapng

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Built-in knowledge SIMBAD lacks: the Caldwell catalogue (not in SIMBAD at
  * all) and the popular nicknames astrophotographers use, many of which SIMBAD
  * does not carry ("Hidden Galaxy", "Soul Nebula", "Fireworks Galaxy"). Plus an
  * optional user names file for personal additions.
  *
  * All designations are compared in [[Lookup.normalize]] form.
  *
  * Port of `src/catalog.rs`. The user-names file and environment variable are
  * renamed for this project (ASTROSCALAPNG_NAMES, astroscalapng-names.txt,
  * <config>/astroscalapng/names.txt).
  */
object Catalog:

  /** (Caldwell number, designations (first = the one to look up in SIMBAD),
    * popular name). Source: the published Caldwell list; C99 (Coalsack) has no
    * catalogue designation.
    */
  val CALDWELL: Vector[(Int, List[String], Option[String])] = Vector(
    (1, List("NGC 188"), Some("Polarissima Cluster")),
    (2, List("NGC 40"), Some("Bow-Tie Nebula")),
    (3, List("NGC 4236"), None),
    (4, List("NGC 7023"), Some("Iris Nebula")),
    (5, List("IC 342"), Some("Hidden Galaxy")),
    (6, List("NGC 6543"), Some("Cat's Eye Nebula")),
    (7, List("NGC 2403"), None),
    (8, List("NGC 559"), None),
    (9, List("Sh2-155"), Some("Cave Nebula")),
    (10, List("NGC 663"), None),
    (11, List("NGC 7635"), Some("Bubble Nebula")),
    (12, List("NGC 6946"), Some("Fireworks Galaxy")),
    (13, List("NGC 457"), Some("Owl Cluster")),
    (14, List("NGC 869", "NGC 884"), Some("Double Cluster")),
    (15, List("NGC 6826"), Some("Blinking Planetary")),
    (16, List("NGC 7243"), None),
    (17, List("NGC 147"), None),
    (18, List("NGC 185"), None),
    (19, List("IC 5146"), Some("Cocoon Nebula")),
    (20, List("NGC 7000"), Some("North America Nebula")),
    (21, List("NGC 4449"), None),
    (22, List("NGC 7662"), Some("Blue Snowball Nebula")),
    (23, List("NGC 891"), Some("Silver Sliver Galaxy")),
    (24, List("NGC 1275"), Some("Perseus A")),
    (25, List("NGC 2419"), Some("Intergalactic Wanderer")),
    (26, List("NGC 4244"), Some("Silver Needle Galaxy")),
    (27, List("NGC 6888"), Some("Crescent Nebula")),
    (28, List("NGC 752"), None),
    (29, List("NGC 5005"), None),
    (30, List("NGC 7331"), Some("Deer Lick Group")),
    (31, List("IC 405"), Some("Flaming Star Nebula")),
    (32, List("NGC 4631"), Some("Whale Galaxy")),
    (33, List("NGC 6992"), Some("Eastern Veil Nebula")),
    (34, List("NGC 6960"), Some("Western Veil Nebula")),
    (35, List("NGC 4889"), None),
    (36, List("NGC 4559"), None),
    (37, List("NGC 6885"), None),
    (38, List("NGC 4565"), Some("Needle Galaxy")),
    (39, List("NGC 2392"), Some("Eskimo Nebula")),
    (40, List("NGC 3626"), None),
    (41, List("Mel 25"), Some("Hyades")),
    (42, List("NGC 7006"), None),
    (43, List("NGC 7814"), Some("Little Sombrero Galaxy")),
    (44, List("NGC 7479"), Some("Superman Galaxy")),
    (45, List("NGC 5248"), None),
    (46, List("NGC 2261"), Some("Hubble's Variable Nebula")),
    (47, List("NGC 6934"), None),
    (48, List("NGC 2775"), None),
    (49, List("NGC 2237"), Some("Rosette Nebula")),
    // C50 is the cluster inside the Rosette; the companion-nebula logic names it.
    (50, List("NGC 2244"), None),
    (51, List("IC 1613"), None),
    (52, List("NGC 4697"), None),
    (53, List("NGC 3115"), Some("Spindle Galaxy")),
    (54, List("NGC 2506"), None),
    (55, List("NGC 7009"), Some("Saturn Nebula")),
    (56, List("NGC 246"), Some("Skull Nebula")),
    (57, List("NGC 6822"), Some("Barnard's Galaxy")),
    (58, List("NGC 2360"), Some("Caroline's Cluster")),
    (59, List("NGC 3242"), Some("Ghost of Jupiter")),
    (60, List("NGC 4038"), Some("Antennae Galaxies")),
    (61, List("NGC 4039"), Some("Antennae Galaxies")),
    (62, List("NGC 247"), None),
    (63, List("NGC 7293"), Some("Helix Nebula")),
    (64, List("NGC 2362"), Some("Tau Canis Majoris Cluster")),
    (65, List("NGC 253"), Some("Sculptor Galaxy")),
    (66, List("NGC 5694"), None),
    (67, List("NGC 1097"), None),
    (68, List("NGC 6729"), None),
    (69, List("NGC 6302"), Some("Butterfly Nebula")),
    (70, List("NGC 300"), Some("Sculptor Pinwheel Galaxy")),
    (71, List("NGC 2477"), None),
    (72, List("NGC 55"), Some("String of Pearls Galaxy")),
    (73, List("NGC 1851"), None),
    (74, List("NGC 3132"), Some("Eight-Burst Nebula")),
    (75, List("NGC 6124"), None),
    (76, List("NGC 6231"), None),
    (77, List("NGC 5128"), Some("Centaurus A")),
    (78, List("NGC 6541"), None),
    (79, List("NGC 3201"), None),
    (80, List("NGC 5139"), Some("Omega Centauri")),
    (81, List("NGC 6352"), None),
    (82, List("NGC 6193"), None),
    (83, List("NGC 4945"), None),
    (84, List("NGC 5286"), None),
    (85, List("IC 2391"), Some("Omicron Velorum Cluster")),
    (86, List("NGC 6397"), None),
    (87, List("NGC 1261"), None),
    (88, List("NGC 5823"), None),
    (89, List("NGC 6087"), Some("S Normae Cluster")),
    (90, List("NGC 2867"), None),
    (91, List("NGC 3532"), Some("Wishing Well Cluster")),
    (92, List("NGC 3372"), Some("Carina Nebula")),
    (93, List("NGC 6752"), Some("Great Peacock Globular")),
    (94, List("NGC 4755"), Some("Jewel Box Cluster")),
    (95, List("NGC 6025"), None),
    (96, List("NGC 2516"), Some("Southern Beehive Cluster")),
    (97, List("NGC 3766"), Some("Pearl Cluster")),
    (98, List("NGC 4609"), None),
    (99, List("Coalsack"), Some("Coalsack Nebula")),
    (100, List("IC 2944"), Some("Running Chicken Nebula")),
    (101, List("NGC 6744"), None),
    (102, List("IC 2602"), Some("Southern Pleiades")),
    (103, List("NGC 2070"), Some("Tarantula Nebula")),
    (104, List("NGC 362"), None),
    (105, List("NGC 4833"), None),
    (106, List("NGC 104"), Some("47 Tucanae")),
    (107, List("NGC 6101"), None),
    (108, List("NGC 4372"), None),
    (109, List("NGC 3195"), None)
  )

  /** Popular names for other frequently imaged objects that SIMBAD either lacks
    * or lists under a less common variant.
    */
  val POPULAR_NAMES: Vector[(String, String)] = Vector(
    // Messier
    ("M 1", "Crab Nebula"),
    ("M 6", "Butterfly Cluster"),
    ("M 7", "Ptolemy's Cluster"),
    ("M 8", "Lagoon Nebula"),
    ("M 11", "Wild Duck Cluster"),
    ("M 12", "Gumball Globular"),
    ("M 13", "Great Hercules Cluster"),
    ("M 15", "Great Pegasus Cluster"),
    ("M 16", "Eagle Nebula"),
    ("M 17", "Omega Nebula"),
    ("M 20", "Trifid Nebula"),
    ("M 22", "Great Sagittarius Cluster"),
    ("M 24", "Sagittarius Star Cloud"),
    ("M 27", "Dumbbell Nebula"),
    ("M 29", "Cooling Tower"),
    ("M 30", "Jellyfish Cluster"),
    ("M 31", "Andromeda Galaxy"),
    ("M 33", "Triangulum Galaxy"),
    ("M 34", "Spiral Cluster"),
    ("M 35", "Shoe-Buckle Cluster"),
    ("M 36", "Pinwheel Cluster"),
    ("M 38", "Starfish Cluster"),
    ("M 40", "Winnecke 4"),
    ("M 41", "Little Beehive Cluster"),
    ("M 42", "Orion Nebula"),
    ("M 43", "De Mairan's Nebula"),
    ("M 44", "Beehive Cluster"),
    ("M 45", "Pleiades"),
    ("M 50", "Heart-Shaped Cluster"),
    ("M 51", "Whirlpool Galaxy"),
    ("M 52", "Salt and Pepper Cluster"),
    ("M 55", "Specter Cluster"),
    ("M 57", "Ring Nebula"),
    ("M 61", "Swelling Spiral Galaxy"),
    ("M 62", "Flickering Globular Cluster"),
    ("M 63", "Sunflower Galaxy"),
    ("M 64", "Black Eye Galaxy"),
    ("M 65", "Leo Triplet"),
    ("M 66", "Leo Triplet"),
    ("M 67", "Golden Eye Cluster"),  // also "King Cobra Cluster"
    ("M 71", "Angelfish Cluster"),
    ("M 74", "Phantom Galaxy"),
    ("M 76", "Little Dumbbell Nebula"),
    ("M 77", "Cetus A"),
    ("M 78", "Casper the Friendly Ghost Nebula"),
    ("M 81", "Bode's Galaxy"),
    ("M 82", "Cigar Galaxy"),
    ("M 83", "Southern Pinwheel Galaxy"),
    ("M 87", "Virgo A"),
    ("M 93", "Critter Cluster"),
    ("M 94", "Croc's Eye Galaxy"),
    ("M 97", "Owl Nebula"),
    ("M 99", "Coma Pinwheel Galaxy"),
    ("M 101", "Pinwheel Galaxy"),
    ("M 102", "Spindle Galaxy"),
    ("M 104", "Sombrero Galaxy"),
    ("M 107", "Crucifix Cluster"),
    ("M 108", "Surfboard Galaxy"),
    // Nebulae (NGC / IC / Sharpless / Barnard / others)
    ("NGC 281", "Pacman Nebula"),
    ("NGC 896", "Fish Head Nebula"),  // brightest part of IC 1795
    ("NGC 1333", "Embryo Nebula"),
    ("NGC 1360", "Robin's Egg Nebula"),
    ("NGC 1435", "Merope Nebula"),
    ("NGC 1491", "Fossil Footprint Nebula"),
    ("NGC 1499", "California Nebula"),
    ("NGC 1514", "Crystal Ball Nebula"),
    ("NGC 1535", "Cleopatra's Eye"),
    ("NGC 1555", "Hind's Variable Nebula"),
    ("NGC 1579", "Northern Trifid Nebula"),
    ("NGC 1931", "Fly Nebula"),
    ("NGC 1977", "Running Man Nebula"),
    ("NGC 2024", "Flame Nebula"),
    ("NGC 2170", "Angel Nebula"),
    ("NGC 2174", "Monkey Head Nebula"),
    ("NGC 2264", "Christmas Tree Cluster"),
    ("NGC 2359", "Thor's Helmet"),
    ("NGC 2371", "Gemini Nebula"),
    ("NGC 2467", "Skull and Crossbones Nebula"),
    ("NGC 2736", "Pencil Nebula"),
    ("NGC 3324", "Gabriela Mistral Nebula"),
    ("NGC 3576", "Statue of Liberty Nebula"),
    ("NGC 3918", "Blue Planetary Nebula"),
    ("NGC 5189", "Spiral Planetary Nebula"),
    ("NGC 6164", "Dragon's Egg Nebula"),
    ("NGC 6188", "Rim Nebula"),
    ("NGC 6210", "Turtle Nebula"),
    ("NGC 6334", "Cat's Paw Nebula"),
    ("NGC 6357", "War and Peace Nebula"),
    ("NGC 6369", "Little Ghost Nebula"),
    ("NGC 6537", "Red Spider Nebula"),
    ("NGC 6572", "Blue Racquetball Nebula"),
    ("NGC 6751", "Glowing Eye Nebula"),
    ("NGC 6818", "Little Gem Nebula"),
    ("NGC 6905", "Blue Flash Nebula"),
    ("NGC 6979", "Pickering's Triangle"),
    ("NGC 6995", "Bat Nebula"),
    ("NGC 7008", "Fetus Nebula"),
    ("NGC 7027", "Jewel Bug Nebula"),
    ("NGC 7380", "Wizard Nebula"),
    ("NGC 7822", "Teddy Bear Nebula"),
    ("NGC 7538", "Northern Lagoon Nebula"),
    ("IC 63", "Ghost of Cassiopeia"),
    ("IC 410", "Tadpoles Nebula"),
    ("IC 417", "Spider Nebula"),
    ("IC 443", "Jellyfish Nebula"),
    ("IC 1318", "Sadr Region"),
    ("IC 1396", "Elephant's Trunk Nebula"),
    ("IC 1795", "Fish Head Nebula"),
    ("IC 1805", "Heart Nebula"),
    ("IC 1848", "Soul Nebula"),
    ("IC 2118", "Witch Head Nebula"),
    ("IC 2177", "Seagull Nebula"),
    ("IC 4406", "Retina Nebula"),
    ("IC 4592", "Blue Horsehead Nebula"),
    ("IC 4604", "Rho Ophiuchi Nebula"),
    ("IC 4628", "Prawn Nebula"),
    ("IC 5070", "Pelican Nebula"),
    ("Sh2-82", "Little Cocoon Nebula"),
    ("Sh2-101", "Tulip Nebula"),
    ("Sh2-106", "Celestial Snow Angel"),
    ("Sh2-114", "Flying Dragon Nebula"),
    ("Sh2-129", "Flying Bat Nebula"),
    ("Sh2-132", "Lion Nebula"),
    ("Sh2-142", "Wizard Nebula"),
    ("Sh2-157", "Lobster Claw Nebula"),
    ("Sh2-158", "Northern Lagoon Nebula"),
    ("Sh2-162", "Bubble Nebula"),
    ("Sh2-190", "Heart Nebula"),
    ("Sh2-199", "Soul Nebula"),
    ("Sh2-206", "Fossil Footprint Nebula"),
    ("Sh2-220", "California Nebula"),
    ("Sh2-229", "Flaming Star Nebula"),
    ("Sh2-236", "Tadpoles Nebula"),
    ("Sh2-240", "Spaghetti Nebula"),
    ("Sh2-248", "Jellyfish Nebula"),
    ("Sh2-252", "Monkey Head Nebula"),
    ("Sh2-261", "Lower's Nebula"),
    ("Sh2-264", "Angelfish Nebula"),
    ("Sh2-273", "Cone Nebula"),
    ("Sh2-274", "Medusa Nebula"),
    ("Sh2-275", "Rosette Nebula"),
    ("Sh2-276", "Barnard's Loop"),
    ("Sh2-279", "Running Man Nebula"),
    ("Sh2-296", "Seagull Nebula"),
    ("Sh2-308", "Dolphin Head Nebula"),
    ("Barnard 33", "Horsehead Nebula"),
    ("Barnard 72", "Snake Nebula"),
    ("Barnard 150", "Seahorse Nebula"),
    ("LDN 1235", "Dark Shark Nebula"),
    ("LDN 1622", "Boogeyman Nebula"),
    ("vdB 141", "Ghost Nebula"),
    // Galaxies
    ("NGC 1316", "Fornax A"),
    ("NGC 1317", "Fornax B"),  // companion to NGC 1316 above
    ("NGC 1365", "Great Barred Spiral Galaxy"),
    ("NGC 1566", "Spanish Dancer Galaxy"),
    ("NGC 2442", "Meathook Galaxy"),
    ("NGC 2537", "Bear's Paw Galaxy"),  // SIMBAD's own alias list also has
    // "Bear Claw Nebula", which our automatic pick prefers (it ends in a
    // type word) but is a mismatched, less-used name for a galaxy.
    ("NGC 2683", "UFO Galaxy"),
    ("NGC 2841", "Tiger's Eye Galaxy"),
    ("NGC 3184", "Little Pinwheel Galaxy"),
    ("NGC 3344", "Sliced Onion Galaxy"),
    ("NGC 3521", "Bubble Galaxy"),
    ("NGC 3628", "Hamburger Galaxy"),
    ("NGC 4435", "Eyes Galaxies"),  // paired with NGC 4438 below (Markarian's Eyes)
    ("NGC 4438", "Eyes Galaxies"),
    ("NGC 4490", "Cocoon Galaxy"),
    ("NGC 4535", "Lost Galaxy"),
    ("NGC 4567", "Butterfly Galaxies"),
    ("NGC 4568", "Siamese Twins"),
    ("NGC 4656", "Hockey Stick Galaxy"),
    ("NGC 4676", "Mice Galaxies"),
    ("NGC 5907", "Splinter Galaxy"),
    ("NGC 6503", "Lost-in-Space Galaxy"),
    ("IC 2574", "Coddington's Nebula"),
    ("UGC 10214", "Tadpole Galaxy"),
    // Star clusters
    ("NGC 2169", "37 Cluster"),
    ("NGC 3293", "Gem Cluster"),
    ("NGC 6811", "Hole in a Cluster"),
    ("NGC 6819", "Foxhead Cluster"),
    ("NGC 6939", "Ghost Bush Cluster"),
    ("NGC 7789", "Caroline's Rose"),
    ("Mel 20", "Alpha Persei Cluster"),
    ("Mel 111", "Coma Star Cluster"),
    ("Cr 399", "Coathanger")
  )


  /** Caldwell number for any of the given designations (normalised or not). */
  def caldwellNumber(designations: Seq[String]): Option[Int] =
    val norm = designations.map(Lookup.normalize)
    CALDWELL.find((_, ds, _) => ds.exists(d => norm.contains(Lookup.normalize(d)))).map(_._1)

  /** For a "C 7" / "Caldwell 7" style query, the designation to look up. */
  def caldwellTarget(query: String): Option[String] =
    val n = Lookup.normalize(query)
    if !n.startsWith("C") then None
    else
      val digits = n.substring(1)
      try
        val num = Integer.parseInt(digits)
        CALDWELL.find((c, _, _) => c == num).map((_, ds, _) => ds.head)
      catch case _: NumberFormatException => None

  /** Curated popular name for an object with these designations: the user's
    * names file first, then the built-in tables.
    */
  def popularName(designations: Seq[String]): Option[String] =
    val norm = designations.map(Lookup.normalize)
    val fromUser = norm.iterator.map(d => userNames.get(d)).collectFirst { case Some(n) => n }
    if fromUser.isDefined then return fromUser
    val fromCaldwell = CALDWELL.iterator
      .map((_, ds, name) => if ds.exists(d => norm.contains(Lookup.normalize(d))) then name else None)
      .collectFirst { case Some(n) => n }
    if fromCaldwell.isDefined then return fromCaldwell
    POPULAR_NAMES.find((d, _) => norm.contains(Lookup.normalize(d))).map(_._2)

  /** Locations of the optional user names file, first match wins:
    * `$ASTROSCALAPNG_NAMES`, `astroscalapng-names.txt` next to the launcher,
    * then `names.txt` in the per-user config folder
    * (`%APPDATA%\\astroscalapng` on Windows,
    * `~/Library/Application Support/astroscalapng` on macOS,
    * `$XDG_CONFIG_HOME/astroscalapng` or `~/.config/astroscalapng` elsewhere).
    */
  def userNamesPaths(): List[Path] =
    val paths = List.newBuilder[Path]
    Option(System.getenv("ASTROSCALAPNG_NAMES")).filter(_.nonEmpty).foreach(p => paths += Paths.get(p))
    appDir().foreach(dir => paths += dir.resolve("astroscalapng-names.txt"))
    val os = System.getProperty("os.name", "").toLowerCase
    val configDir: Option[Path] =
      if os.contains("win") then Option(System.getenv("APPDATA")).map(Paths.get(_))
      else if os.contains("mac") then
        Option(System.getProperty("user.home")).map(h => Paths.get(h, "Library", "Application Support"))
      else
        Option(System.getenv("XDG_CONFIG_HOME"))
          .filter(_.nonEmpty)
          .map(Paths.get(_))
          .orElse(Option(System.getProperty("user.home")).map(h => Paths.get(h, ".config")))
    configDir.foreach(dir => paths += dir.resolve("astroscalapng").resolve("names.txt"))
    paths.result()

  /** The folder the application was started from: the JVM equivalent of Rust's
    * `current_exe().parent()`. With sbt-native-packager this is the `lib`
    * folder of the staged app, so the app's own folder is checked too.
    */
  private def appDir(): Option[Path] =
    val fromProp = Option(System.getProperty("astroscalapng.home")).map(Paths.get(_))
    val fromJar =
      try
        val src = classOf[XisfError].getProtectionDomain.getCodeSource
        Option(src).map(s => Paths.get(s.getLocation.toURI)).flatMap(p => Option(p.getParent))
      catch case _: Exception => None
    fromProp.orElse(fromJar)

  /** The user's names file, parsed once. Format: one `designation = Name` per
    * line, `#` starts a comment, e.g. `NGC 2403 = My Favourite Galaxy`.
    */
  lazy val userNames: Map[String, String] =
    var map = Map.empty[String, String]
    val it  = userNamesPaths().iterator
    var done = false
    while !done && it.hasNext do
      val path = it.next()
      val lines =
        try Some(Files.readAllLines(path).asScala.toList)
        catch case _: Exception => None
      lines.foreach { ls =>
        for line0 <- ls do
          val line = line0.split("#", -1).headOption.getOrElse("").trim
          val idx  = line.indexOf('=')
          if idx >= 0 then
            val key   = Lookup.normalize(line.substring(0, idx).trim)
            val value = line.substring(idx + 1).trim
            if key.nonEmpty && value.nonEmpty then map = map.updated(key, value)
        done = true
      }
    map

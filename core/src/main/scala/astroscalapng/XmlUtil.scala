package astroscalapng

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Document, Element, Node, NodeList}

/** Thin helpers over the JDK DOM parser that mirror the roxmltree API used by
  * the Rust original (`descendants()`, `tag_name().name()`, `attribute()`,
  * `text()`), so the traversal code can stay a literal port.
  */
private[astroscalapng] object XmlUtil:

  private def newFactory(): DocumentBuilderFactory =
    val f = DocumentBuilderFactory.newInstance()
    // No DTDs / external entities: these files come from other people.
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    try f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    catch case _: IllegalArgumentException => ()
    try f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    catch case _: IllegalArgumentException => ()
    f.setNamespaceAware(false)
    f.setExpandEntityReferences(false)
    f

  /** Parse XML text; the error message is the exception's message. */
  def parse(xml: String): Either[String, Document] =
    try
      val builder = newFactory().newDocumentBuilder()
      builder.setErrorHandler(null)
      Right(builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8"))))
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  /** Local element name, namespace prefix stripped. */
  def name(n: Node): String =
    val ln = n.getLocalName
    if ln != null then ln
    else
      val qn = n.getNodeName
      val i  = qn.indexOf(':')
      if i >= 0 then qn.substring(i + 1) else qn

  /** All element descendants of `root`, document order (root included). */
  def descendants(root: Node): LazyList[Element] =
    def walk(n: Node): LazyList[Element] =
      val self = n match
        case e: Element => LazyList(e)
        case _          => LazyList.empty
      self #::: childNodes(n).to(LazyList).flatMap(walk)
    walk(root)

  /** Direct element children of `n`. */
  def children(n: Node): List[Element] =
    childNodes(n).collect { case e: Element => e }

  private def childNodes(n: Node): List[Node] =
    val list: NodeList = n.getChildNodes
    if list == null then Nil
    else (0 until list.getLength).map(list.item).toList

  def attribute(e: Element, name: String): Option[String] =
    if e.hasAttribute(name) then Some(e.getAttribute(name)) else None

  /** Text of the first text (or CDATA) child node, like roxmltree's `text()`. */
  def text(e: Element): Option[String] =
    childNodes(e)
      .find(n => n.getNodeType == Node.TEXT_NODE || n.getNodeType == Node.CDATA_SECTION_NODE)
      .map(_.getNodeValue)

  /** Text of the first child element called `name`. */
  def childText(e: Element, name0: String): Option[String] =
    children(e).find(c => name(c) == name0).flatMap(text)

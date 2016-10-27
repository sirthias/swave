import com.lightbend.paradox.markdown.Writer
import com.lightbend.paradox.markdown.Writer.Context
import org.pegdown.{DefaultVerbatimSerializer, VerbatimSerializer}
import org.pegdown.ast.{Node => AstNode}

import scala.xml._
import scala.xml.transform._

class CustomWriter extends Writer(verbatimSerializers =
  Map(VerbatimSerializer.DEFAULT -> new DefaultVerbatimSerializer)) {
  import CustomWriter._

  /**
    * Transform the HTML snippet for the sidebar navigation returned by paradox
    * to the structure our template needs.
    */
  override def writeNavigation(node: AstNode, context: Context): String = {
    val html = super.writeNavigation(node, context)
    val xml = XML.loadString(html)
    val applyNavMarker = rewriteRule {
      case elem: Elem if elem.label == "ul" => elem setClassAttr "nav nav-children"
      case elem: Elem if elem.label == "li" && (elem \ "ul").nonEmpty =>
        val removeAnchorHref: Node => Node = {
          case elem: Elem if elem.label == "a" => elem.copy(attributes = elem.attributes remove "href")
          case x => x
        }
        elem.copy(child = elem.child.map(removeAnchorHref)) setClassAttr "nav-parent"
      case x => x
    }
    val markActiveParents = rewriteRule {
      case elem: Elem if elem.label == "li" && (elem \\ "a").exists(_ hasClassAttr "active") =>
        elem addClass "nav-expanded nav-active"
      case x => x
    }
    val removeActiveClassFromAnchors = rewriteRule {
      case elem: Elem if elem.label == "a" && (elem hasClassAttr "active") =>
        elem.copy(attributes = elem.attributes.remove("class"))
      case x => x
    }
    val icons = Map(
      "Introduction" -> "blind",
      "User Documentation" -> "book",
      "Developer Documentation" -> "cogs",
      "Project Info" -> "info-circle",
      "Support" -> "users")
    val addIcons = rewriteRule {
      case elem: Elem if elem.label == "a" && (icons contains elem.text) =>
        val text = elem.text
        elem.copy(child = <i class={"fa fa-" + icons(text)} aria-hidden="true"></i><span>{text}</span>)
      case x => x
    }
    val transformed =
      List(applyNavMarker, markActiveParents, removeActiveClassFromAnchors, addIcons).foldLeft(xml: Node) { (x, r) =>
        new RuleTransformer(r).apply(x)
      }
    (transformed setClassAttr "nav nav-main").toString
  }

  override def writeBreadcrumbs(node: AstNode, context: Context): String = {
    val html = super.writeBreadcrumbs(node, context)
    val xml = XML.loadString(html)
    val replaceRootWithHome = rewriteRule {
      case elem: Elem if elem.label == "a" && elem.text == "swave" => elem.copy(child = <i class="fa fa-home"></i>)
      case x => x
    }
    val transformed = new RuleTransformer(replaceRootWithHome).apply(xml)
    (transformed setClassAttr "breadcrumbs").toString
  }
}

object CustomWriter {
  private def rewriteRule(f: Node => Seq[Node]) =
    new RewriteRule {
      override def transform(n: Node): Seq[Node] = f(n)
    }

  private implicit class RichNode(val underlying: Node) extends AnyVal {
    def setClassAttr(s: String) = underlying.asInstanceOf[Elem] % new UnprefixedAttribute("class", s, Null)
    def classAttr: Option[String] = underlying.attribute("class").map(_.text)
    def addClass(s: String) = setClassAttr(classAttr.map(_ + ' ' + s) getOrElse s)
    def hasClassAttr(value: String) = classAttr == Some(value)
  }
}
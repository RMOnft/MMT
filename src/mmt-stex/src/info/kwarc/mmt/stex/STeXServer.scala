package info.kwarc.mmt.stex

import info.kwarc.mmt.api._
import info.kwarc.mmt.api.notations.{Delim, Marker, SimpArg, Var}
import info.kwarc.mmt.api.objects._
import info.kwarc.mmt.api.refactoring.AcrossLibraryTranslator
import info.kwarc.mmt.api.utils.{MMTSystem, XMLEscaping}
import info.kwarc.mmt.api.web.{ServerExtension, ServerRequest, ServerResponse}
import info.kwarc.mmt.odk.Sage.{Sage, SageSystem}
import info.kwarc.mmt.stex.Extensions.{BasicExtension, DemoExtension, STeXExtension, Translator}
import info.kwarc.mmt.stex.translations.DemoContent
import info.kwarc.mmt.stex.xhtml._


class STeXServer extends ServerExtension("fomid") {
  private var initialized = false
  def initialize = if (!initialized) {
    initialized = true
    if (!extensions.contains(BasicExtension))
      controller.extman.addExtension(BasicExtension)
    if (!extensions.contains(DemoExtension))
      controller.extman.addExtension(DemoExtension)
  }

  def extensions = controller.extman.get(classOf[STeXExtension])

  override def start(args: List[String]): Unit = {
    super.start(args)
    initialize
  }

  override def apply(request: ServerRequest): ServerResponse = try {
    initialize
    val ret = request.path.lastOption match {
      case Some("fragment") =>
        request.query match {
          case "" =>
            ???
          case s => doFragment(s)
        }
      case Some("document") =>
        request.query match {
          case "" =>
            ???
          case s =>
            doDocument(s)
        }
      case Some("declaration") =>
        request.query match {
          case "" =>
            ???
          case s =>
            doDeclaration(s)
        }
      case Some("expression") =>
        val xml = request.body.params.get("openmath") match {
          case Some(s) =>
            XMLEscaping.unapply(s)
          case None =>
            ???
        }
        val compO = request.body.params.get("component") match {
          case Some("None") => None
          case Some(s) => Some(Path.parseC(XMLEscaping.unapply(s),NamespaceMap.empty))
          case _ => None
        }
        doExpression(Obj.parseTerm(XHTML.applyString(xml)(XHTML.Rules.defaultrules).head.node,NamespaceMap.empty),compO)
      case Some("translate") =>
        val xml = request.body.params.get("openmath") match {
          case Some(s) =>
            XMLEscaping.unapply(s)
          case None =>
            ???
        }
        val trl = request.body.params.get("target") match {
          case Some(s) =>
            val translators = extensions.flatMap(_.translators)
            translators.find(_.language == s) match {
              case None =>
                ???
              case Some(trl) => trl
            }
          case _ =>
            ???
        }
        doTranslation(Obj.parseTerm(XHTML.applyString(xml)(XHTML.Rules.defaultrules).head.node,NamespaceMap.empty),trl)
      case _ =>
        extensions.foreach(_.serverReturn(request) match {
          case Some(rsp) => return rsp
          case _ =>
        })
    }
    ServerResponse(ret.toString, "html")
  } catch {
    case t : Throwable =>
      throw t
  }

  def doDocument(uri : String) = {
    val exts = extensions
    implicit val xhtmlrules = XHTML.Rules.defaultrules ::: exts.flatMap(_.xhtmlRules)
    val filecontent = XHTML.applyString(getDocument(uri)).head
    doMainHeader(filecontent)
    val docrules = extensions.flatMap(_.documentRules)
    def doE(e : XHTMLNode) : Unit = docrules.foreach(r => r.unapply((e,xhtmlrules,doE)))
    filecontent.iterate(doE)
    filecontent
  }

  // TODO
  def getDocument(uri : String) : String = uri match {
    case "http://mathhub.info/fomid/demo.xhtml" =>
      MMTSystem.getResourceAsString("mmt-web/stex/demo/test.xhtml")
    case _ =>
      ???
  }

  def doFragment(uri : String) = {
    import info.kwarc.mmt.stex.xhtml.XHTML.Rules._
    val frag = getFragment(uri) match {
      case "missing" => None
      case o => Some(o)
    }
    val decl = doDeclaration(uri).get("div")(("", "class", "ltx_page_main")).head.children
    val (filecontent,default) = frag.map { f =>
      (XHTML.applyString(f).head,false)
    }.getOrElse((emptydoc._1,true))
    doHeader(filecontent)
    stripMargins(filecontent)
    val doc = filecontent.get("div")(("", "class", "ltx_page_main")).head
    val border = XHTML(<div style="font-size:small">{decl.map(_.node)}{ if (!default) <hr/>}</div>)(Nil).head
    doc.children.foreach {c =>
      c.delete
      border.add(c)
    }
    doc.add(border)
    filecontent
  }

  def stripMargins(ltx : XHTMLNode) = {
    val body = ltx.get("body")().head
    body.attributes(("", "style")) = "margin:0;padding:0;"
    val doc = body.get("div")(("", "class", "ltx_page_main")).head
    doc.attributes(("", "style")) = "margin:0;padding:0.1em 0.5em 0.5em 0.5em;"
    doc.get("div")().foreach { e =>
      if (e.attributes.get(("", "class")).exists(_.contains("ltx_theorem"))) {
        e.attributes(("", "style")) = "margin:0;"
      }
    }
  }

  // TODO
  def getFragment(s:String) : String = MMTSystem.getResourceAsString("mmt-web" + (s match {
    case _ if s == translations.DemoContent.c_nat.path.toString =>
      "/stex/demo/naturalnumbers.en.xhtml"
    case _ if s == translations.DemoContent.c_impl.path.toString =>
      "/stex/demo/implication.en.xhtml"
    case _ if s == translations.DemoContent.c_even.path.toString =>
      "/stex/demo/even.en.xhtml"
    case _ if s == translations.DemoContent.c_natexp.path.toString =>
      "/stex/demo/exponentiation.en.xhtml"
    case _ => return "missing"
  }))


  def doDeclaration(s : String) = {
    val path = Path.parseS(s)
    val c = controller.getConstant(path)
    val (doc,body) = emptydoc
    def space = scala.xml.Text(" ")
    val suffix = c.rl match {
      case Some("variable") =>
        body.add(<b>{scala.xml.Text("Variable")}</b>)
        c.metadata.getValues(STeX.meta_quantification) match {
          case List(OMS(STeX.Forall.path)) => scala.xml.Text("(universally quantified)")
          case List(OMS(STeX.Exists.path)) => scala.xml.Text("(existentially quantified)")
          case _ => space
        }
      case _ =>
        body.add(XHTML(<b>{scala.xml.Text("Symbol")}</b>)(Nil).head)
        space
    }
    body.add(space)
    body.add(<a href={"/?"+s} target="_blank">{XMLEscaping(c.path.toString)}</a>)
    body.add(<br/>)
    body.add(
      <table>
        <tr><th>{scala.xml.Text("Macro")}</th><th>{scala.xml.Text("Presentation")}</th><th>{scala.xml.Text("Type")}</th><th></th></tr>
        <tr>
          <td>{c.notC.parsing match {
              case Some(tn) => scala.xml.Text(tn.markers.mkString(""))
              case _ => scala.xml.Text("(None)")
            }}</td>
          <td>{c.notC.presentation match {
              case Some(tn) => scala.xml.Text(tn.markers.mkString(""))
              case _ => scala.xml.Text("(None)")
            }}</td>
          <td>{c.tp match {
              case Some(tpi) => presenter.asXML(tpi,Some(c.path $ TypeComponent))
              case _ => scala.xml.Text("(None)")
            }}</td>
          <td>{suffix}</td>
        </tr>
      </table>)
    doc
  }

  def doExpression(o : Obj,src:Option[CPath]) = {
    val (doc,body) = emptydoc
    doMainHeader(doc)
    body.add("Expression: ")
    body.add(presenter.asXML(o,src))
    body.iterate {
      case e if e.label == "mo" =>
        e.attributes.get(("","data-mmt-symref")) match {
          case Some(str) =>
            e.addOverlay("/:" + this.pathPrefix + "/fragment?" + str)
          case _ =>
        }
      case _ =>
    }
    body.add(<hr/>)
    body.add("LaTeX: ")
    body.add(<code><pre>{scala.xml.Text(toLaTeX(o))}</pre></code>)
    body.add(<hr/>)
    body.add("Translations: ")
    val translators = extensions.flatMap(_.translators)
    val translations = <table><tr>
      {translators.map {t =>
        <td><form method="post" action={"/:" + this.pathPrefix + "/translate"} class="inline" target="_self">
          <input type="hidden" name="openmath" value={o.toNode.toString().replace("\n","").replace("\n","")}/>
          <button type="submit" name="target" value={t.language}>
            {scala.xml.Text(t.language)}
          </button>
        </form></td>
      }}</tr></table>
    body.add(translations)
    doc
  }

  def doTranslation(tmI : Term,trl : Translator) = {
    val (doc,body) = emptydoc
    doMainHeader(doc)
    body.add("Expression: ")
    body.add(presenter.asXML(tmI,None))
    body.add(<hr/>)

    trl.translate(tmI) match {
      case (tm,Nil) =>
        body.add("Translated to " + trl.language + ": ")
        body.add(presenter.asXML(tm,None))
      case (_,ls) if ls.nonEmpty =>
        body.add("Translation to " + trl.language + " failed. Translators missing for:")
        body.add(<ul>{ls.map(p => <li><code>{scala.xml.Text(p.toString)}</code></li>)}</ul>)
    }
    body.iterate {
      case e if e.label == "mo" =>
        e.attributes.get(("","data-mmt-symref")) match {
          case Some(str) =>
            e.addOverlay("/:" + this.pathPrefix + "/fragment?" + str)
          case _ =>
        }
      case _ =>
    }
    doc
  }

  // TODO JOBAD and stuff
  def doMainHeader(doc : XHTMLNode): Unit = {
    val head = doHeader(doc)
    /*
        (  <script type="text/javascript" src="script/jquery/jquery.js">{p}</script>
            <link rel="stylesheet" type="text/css" href="css/bootstrap-jobad/css/bootstrap.less.css"/>
            <link rel="stylesheet" type="text/css" href="css/mmt.css" />
            <link rel="stylesheet" type="text/css" href="css/browser.css" />
            <link rel="stylesheet" type="text/css" href="css/JOBAD.css" />
            <link rel="stylesheet" type="text/css" href="css/jquery/jquery-ui.css"/>
          <script type="text/javascript" src="script/jquery/jquery-ui.js">{p}</script>
          <script type="text/javascript" src="script/tree/jquery.hotkeys.js">{p}</script>
          <script type="text/javascript" src="script/tree/jquery.jstree.js">{p}</script>
          <script type="text/javascript" src="script/incsearch/treeview.js">{p}</script>
            <link rel='stylesheet' href='css/incsearch/jstree.css'/>
            <link rel='stylesheet' href='css/incsearch/index.css'/>
            <link rel='stylesheet' href='css/incsearch/incsearch.css'/>
            <link rel='stylesheet' href='css/incsearch/treeview.css'/>
          <script type="text/javascript" src="script/mmt/mmt-js-api.js">{p}</script>
          <script type="text/javascript" src="script/jobad/deps/underscore-min.js">{p}</script>
          <script type="text/javascript" src="script/bootstrap2/bootstrap.js">{p}</script>
          <script type="text/javascript" src="script/jobad/JOBAD.js">{p}</script>
          <script type="text/javascript" src="script/jobad/modules/hovering.js">{p}</script>
          <script type="text/javascript" src="script/jobad/modules/interactive-viewing.js">{p}</script>
          <script type="text/javascript" src="script/mmt/browser.js">{p}</script>).toList.foreach(head.add(_))
     */
  }

  def doHeader(doc : XHTMLNode) = {
    val head = doc.get("head")().head
    head.get("link")(("","rel","stylesheet")).foreach(e => e.attributes.get(("","href")) match {
      case Some("https://latex.now.sh/style.css") => e.delete
      case Some("LaTeXML.css") => e.attributes(("","href")) = "/stex/latexml/LaTeXML.css"
      case Some(s) if s.startsWith("ltx-") => e.attributes(("","href")) = "/stex/latexml/" + s
      case _ =>
    })
    head.add(XHTML(<link rel="stylesheet" href="/stex/latex-css/style.css"/>)(Nil).head)
    head
  }

  def emptydoc = {
    val doc = new XHTMLDocument
    doc.add(XHTML(<head><meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8"/></head>)(Nil).head)
    doc.add(XHTML(<body><div class="ltx_page_main"><div class="ltx_page_content"><div class="ltx_document"></div></div></div></body>)(Nil).head)
    (doc,doc.get("div")(("","class","ltx_document")).head)
  }

  private def toLatex(notation : List[Marker],args : List[Obj]) : String = notation.map{
    case Delim(s) => s
    case Var(i,_,_,_) =>
      val v = args(i-1) match {
        case Context(vd) => vd
        case vd : VarDecl => vd
        case _ =>
          ???
      }
      toLaTeX(v)
    case SimpArg(i,_) =>
      toLaTeX(args(i-1))
    case _ =>
      ???
  }.mkString("")

  def toLaTeX(o : Obj) : String = o match {
    case OMBIND(OMS(f),ctx,bd) =>
      controller.getConstant(f).notC.parsing match {
        case Some(tn) =>
          toLatex(tn.markers,ctx :: bd :: Nil)
        case _ =>
          ???
      }
    case OMA(OMS(f),args) =>
      controller.getConstant(f).notC.parsing match {
        case Some(tn) =>
          toLatex(tn.markers,args)
        case _ =>
          ???
      }
    case OMV(n) => "\\" + n.toString
    case OMS(s) =>
      controller.getConstant(s).notC.parsing match {
        case Some(tn) =>
          toLatex(tn.markers,Nil)
        case _ =>
          ???
      }
    case vd:VarDecl => "\\" + vd.name.toString
    case o : OMLITTrait => o.valueString
    case _ =>
      print("")
      ???
  }

  lazy val presenter = controller.extman.get(classOf[MMTInformalPresenter]) match {
    case p :: _ => p
    case Nil =>
      val p = new MMTInformalPresenter
      controller.extman.addExtension(p)
      p
  }

}

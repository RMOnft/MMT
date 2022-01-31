package info.kwarc.mmt.stex.lsp

import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.utils.File
import info.kwarc.mmt.api.web.{ServerExtension, ServerRequest, ServerResponse}
import info.kwarc.mmt.lsp.{LSP, LSPClient, LSPServer, LSPWebsocket, LocalStyle, RunStyle, TextDocumentServer, WithAnnotations, WithAutocomplete}
import info.kwarc.mmt.stex.{RusTeX, STeXServer}
import info.kwarc.mmt.stex.xhtml.SemanticState
import org.eclipse.lsp4j.jsonrpc.services.{JsonRequest, JsonSegment}

import java.util.concurrent.CompletableFuture

class MainFileMessage {
  var mainFile: String = null
}

class HTMLUpdateMessage {
  var html: String = null
}

@JsonSegment("stex")
trait STeXClient extends LSPClient {
  @JsonRequest def getMainFile: CompletableFuture[MainFileMessage]
  @JsonRequest def updateHTML(msg: HTMLUpdateMessage): CompletableFuture[Unit]
}
class STeXLSPWebSocket extends LSPWebsocket(classOf[STeXClient],classOf[STeXLSPServer])
class STeXLSP extends LSP(classOf[STeXClient],classOf[STeXLSPServer],classOf[STeXLSPWebSocket])("stex",5007,5008){
  override def newServer(style: RunStyle): STeXLSPServer = new STeXLSPServer(style)
}

class STeXLSPServer(style:RunStyle) extends LSPServer(classOf[STeXClient])
  with TextDocumentServer[STeXClient,sTeXDocument]
  with WithAutocomplete[STeXClient]
  with WithAnnotations[STeXClient,sTeXDocument]
 {
   override def newDocument(uri: String): sTeXDocument = {
     val mf = client.client.getMainFile
     val file = mf.join().mainFile
     println("Here: " + file)
     new sTeXDocument(uri,this.client,this)
   }
   var mathhub_top : Option[File] = None

   override def completion(doc: String, line: Int, char: Int): List[Completion] = Nil

   override val scopes: List[String] = Nil
   override val modifiers: List[String] = Nil

   override def shutdown: Any = style match {
     case LocalStyle => scala.sys.exit()
     case _ =>
   }


   lazy val stexserver = controller.extman.get(classOf[STeXServer]) match {
     case Nil =>
       val ss = new STeXServer
       controller.extman.addExtension(ss)
       ss
     case a :: _ =>
       a
   }

   override def connect: Unit = {
     controller.extman.addExtension(lspdocumentserver)
     client.log("Connected to sTeX!")
   }

   override def didChangeConfiguration(params: List[(String, List[(String, String)])]): Unit = {
     params.collect {case (a,ls) if a == "stexide" =>
       ls.collect {case ("mathhub",v) if v.nonEmpty && File(v).exists() =>
         RusTeX.initializeBridge(File(v) / ".rustex")
         this.mathhub_top = Some(File(v))
       }
     }
   }

   override def didSave(docuri: String): Unit = this.documents.get(docuri) match {
     case Some(document) => document.build()
     case _ =>
   }

   val self = this

   lazy val lspdocumentserver = new ServerExtension("stexlspdocumentserver") {
     override def apply(request: ServerRequest): ServerResponse = request.path.lastOption match {
       case Some("document") =>
         request.query match {
           case "" =>
             ServerResponse("Empty Document path","txt")
           case s =>
             self.documents.get(s) match {
               case None =>
                 ServerResponse("Empty Document path","txt")
               case Some(d) =>
                 d.html match {
                   case Some(html) => ServerResponse(html.toString,"html")
                   case None =>
                     ServerResponse("Document not yet built","txt")
                 }
             }
         }
       case _ =>
         ServerResponse("Unknown key","txt")
     }
   }

}

object Main {

  @throws[InterruptedException]
  //@throws[ExecutionException]
  def main(args: Array[String]): Unit = {
    val controller = new Controller()
    val end = new STeXLSP
    controller.extman.addExtension(end)
    controller.backend.openArchive(File(args.head))
    end.runLocal
  }
}
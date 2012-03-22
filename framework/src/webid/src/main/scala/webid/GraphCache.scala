/*
* Copyright (c) 2011 Henry Story (bblfish.net)
* under the MIT licence defined
*    http://www.opensource.org/licenses/mit-license.html
*
* Permission is hereby granted, free of charge, to any person obtaining a copy of
* this software and associated documentation files (the "Software"), to deal in the
* Software without restriction, including without limitation the rights to use, copy,
* modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
* and to permit persons to whom the Software is furnished to do so, subject to the
* following conditions:
*
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
* INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
* PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
* OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.w3.readwriteweb

//import _root_.dispatch.Handler
//import _root_.dispatch.Http
//import _root_.dispatch.thread
//import _root_.dispatch.url._
//import org.apache.http.MethodNotSupportedException
//import org.w3.readwriteweb.util._
import java.net.{ConnectException, URL}
import scalaz.{Scalaz, Validation}
//import java.util.concurrent.TimeUnit
//import com.weiglewilczek.slf4s.Logging
//import com.google.common.cache._
//import com.weiglewilczek.slf4s.Logging
//import org.w3.readwriteweb.Lang._
//import org.w3.readwriteweb.{RDFXML, Lang, CacheControl, ResourceManager}
//import org.w3.readwriteweb.util._
import play.api.libs.ws.WS
import com.hp.hpl.jena.graph.Graph
import play.api.libs.iteratee.{Input, Iteratee}
import com.hp.hpl.jena.rdf.model.{ModelFactory, Model}
import com.fasterxml.aalto.stax.InputFactoryImpl
import com.fasterxml.aalto.{AsyncInputFeeder, AsyncXMLStreamReader}
import com.hp.hpl.jena.rdf.arp.SAX2Model
import patch.AsyncJenaParser
//import akka.actor.IO.Done
import java.io._
import play.api.libs.concurrent.{PurePromise, Akka, Promise}


/**
* Fetch resources on the Web and cache them
* ( at a later point this would include saving them to an indexed quad store )
*
* @author Henry Story
* @created: 12/10/2011
*
*/
object GraphCache  {
//  import dispatch._
  import Scalaz._
  import webid.Logger.log
  import play.api.Play.current

  //use shellac's rdfa parser
//  new net.rootdev.javardfa.jena.RDFaReader  //import rdfa parser


  //this is a simple but quite stupid web cache so that graphs can stay in memory and be used a little
  // bit across sessions
//  val cache: LoadingCache[URL,Validation[Throwable,Model]] =
//    CacheBuilder.newBuilder()
//      .expireAfterAccess(5, TimeUnit.MINUTES)
//      .softValues()
//      //         .expireAfterWrite(30, TimeUnit.MINUTES)
//      .build(new CacheLoader[URL, Validation[Throwable,Model]] {
//      def load(url: URL) = getUrl(url)
//    })
//
//  val http = new Http with thread.Safety {
//    import org.apache.http.params.CoreConnectionPNames
//    client.getParams.setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 3000)
//    client.getParams.setParameter(CoreConnectionPNames.SO_TIMEOUT, 15000)
//  }
//
//  def basePath = null //should be cache dir?
//
//  def sanityCheck() = true  //cache dire exists? But is this needed for functioning?
//
//  def resource(u : URL) = new org.w3.readwriteweb.Resource {
//    import CacheControl._
//    def name() = u
//    def get(cacheControl: CacheControl.Value) = cacheControl match {
//      case CacheOnly => {
//        val res = cache.getIfPresent(u)
//        if (null==res) NoCachEntry.fail
//        else res
//      }
//      case CacheFirst => cache.get(u)
//      case NoCache => {
//        val res = getUrl(u)
//        cache.put(u,res) //todo: should this only be done if say the returned value is not an error?
//        res
//      }
//    }
//    // when fetching information from the web creating directories does not make sense
//    //perhaps the resource manager should be split into read/write sections?
//    def save(model: Model) =  throw new MethodNotSupportedException("not implemented")
//
//    def createDirectory(model: Model) =  throw new MethodNotSupportedException("not implemented")
//  }

  def getUrl(u: URL) = {

    // note we prefer rdf/xml and turtle over html, as html does not always contain rdfa, and we prefer those over n3,
    // as we don't have a full n3 parser. Better would be to have a list of available parsers for whatever rdf framework is
    // installed (some claim to do n3 when they only really do turtle)
    // we can't currently accept */* as we don't have GRDDL implemented

    //we need to tell the model about the content type
    val graph: Promise[Graph] = {
       WS.url(u.toString).withHeaders("Accept"->
        "application/rdf+xml,text/turtle,application/xhtml+xml;q=0.8,text/html;q=0.7,text/n3;q=0.2").get{ response =>
         val lang = response.headers.get("Content-Type").map(_.head) match {
           case Some(mime) => {
             Lang(mime.split(";")(0)) getOrElse Lang.default
           }
           case None => RDFXML //todo: it would be better to try to do a bit of guessing in this case by looking at content
         }

         val loc = response.headers.get("Content-Location").map(_.head) match {
           case Some(loc) => new URL(u, loc)
           case None => new URL(u.getProtocol, u.getAuthority, u.getPort, u.getPath)
         }

         val graphIteratee : Iteratee[Array[Byte],Graph] = lang match {
           case RDFXML => Iteratee.fold[Array[Byte], RdfXmlFeeder](new RdfXmlFeeder(loc.toString)) {
             (graph, bytes) =>
               if (graph.feeder.needMoreInput()) {
                 graph.feeder.feedInput(bytes, 0, bytes.length)
               } else throw new Throwable("ERROR: The feeder could not take any  more input for " + loc)

               //should one check if asyncParser needs more input?
               graph.asyncParser.parse()
               graph
           }.map(_.model.getGraph)
           //case TURTLE => use my turtle parser in some way similar as above
           case mime => {
             val in = new PipedInputStream()
             val out = new PipedOutputStream(in)
             val blockingIO = Akka.future {
               // we need to run these blocking parsers in their own threads
               modelFromInputStream(in, loc, mime).fold(throw _,_.getGraph)
             }
             Iteratee.fold[Array[Byte], PipedOutputStream](out) {
               (out, bytes) => { out.write(bytes); out }
             }.map(_=>blockingIO.value.get)
           }
         }
         graphIteratee
       }
    }
    graph
  }


    def modelFromInputStream( is: InputStream,
                              base: URL,
                              lang: Lang): Validation[Throwable, Model] =
      try {
        val m = ModelFactory.createDefaultModel()
        m.getReader(lang.jenaLang).read(m, is, base.toString)
        m.success
      } catch {
        case t =>  {
          log.info("cought exception turning stream into model ",t)
          t.fail
        }
      }

  case class RdfXmlFeeder(base: String) {
    lazy val asyncReader: AsyncXMLStreamReader = new InputFactoryImpl().createAsyncXMLStreamReader();
    lazy val feeder: AsyncInputFeeder = asyncReader.getInputFeeder();
    lazy val model: Model = ModelFactory.createDefaultModel()
    lazy val asyncParser = new AsyncJenaParser(SAX2Model.create(base, model),asyncReader)
  }


    //      request.>+>[Validation[Throwable, Model]](res =>  {
//      res >:> { headers =>
//        val encoding = headers("Content-Type").headOption match {
//          case Some(mime) => {
//            Lang(mime.split(";")(0)) getOrElse Lang.default
//          }
//          case None => RDFXML  //todo: it would be better to try to do a bit of guessing in this case by looking at content
//        }
//        val loc = headers("Content-Location").headOption match {
//          case Some(loc) =>  new URL(u,loc)
//          case None => new URL(u.getProtocol,u.getAuthority,u.getPort,u.getPath)
//        }
//        res>>{ in=> modelFromInputStream(in,loc,encoding) }
//
//      }
//    })
//    try {
//      val future = http(handler)
//      future
//    } catch {
//      case e: ConnectException => {
//        logger.info("failed to connect to "+u.getHost,e)
//        e.fail
//      }
//    }
//
//  }


//  override def finalize() { http.shutdown() }
}

//object NoCachEntry extends Exception

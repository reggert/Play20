/*
 * Copyright (c) 2011 W3C under the W3C licence defined at http://opensource.org/licenses/W3C
 */

package org.w3.readwriteweb.play

import scala.None
import org.w3.banana._


object Lang {

  val supportedLanguages = Set(RDFXML, Turtle, N3, RDFaHTML, RDFaXHTML)
//  val supportContentTypes = supportedLanguages map (_.contentType)
//  val supportedAsString = supportContentTypes mkString ", "

  val default = RDFXML

  def apply(contentType: String): Option[Language] =
    contentType.trim.toLowerCase match {
      case "text/n3" => Some(N3)
      case "text/rdf+n3"=>Some(N3)
      case "text/turtle" => Some(Turtle)
      case "application/rdf+xml" => Some(RDFXML)
      case "text/html" => Some(RDFaHTML)
      case "application/xhtml+xml" => Some(RDFaXHTML)
      case "application/sparql-query" => Some(SparqL)
      case _ => None
    }

  def apply(cts: Iterable[String]): Option[Language] =
    cts map Lang.apply collectFirst { case Some(lang) => lang }


  def contentType(lang: Language) = lang match {
    case RDFXML => "application/rdf+xml"
    case Turtle => "text/turtle"
    case N3 => "text/n3"
    case RDFaXHTML => "application/xhtml+xml"
    case RDFaHTML => "text/html"
    case SparqL => "application/sparql-query"
  }

}
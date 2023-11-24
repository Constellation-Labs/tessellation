package org.tessellation.sdk.http.p2p.headers

import cats.syntax.either._

import scala.util.Try

import org.tessellation.schema.cluster.SessionToken
import org.tessellation.schema.generation.Generation

import eu.timepit.refined.types.numeric.PosLong
import io.estatico.newtype.ops._
import org.http4s.{Header, ParseFailure, ParseResult}
import org.typelevel.ci._

object `X-Session-Token` {

  def parse(s: String): ParseResult[`X-Session-Token`] =
    Try(s.toLong).toEither.flatMap { a =>
      PosLong.from(a).leftMap(err => new Throwable(err))
    }.map(Generation(_))
      .leftMap(e => ParseFailure("Invalid X-Session-Token header", e.getMessage()))
      .map(t => `X-Session-Token`(SessionToken(t)))

  implicit val headerInstance: Header[`X-Session-Token`, Header.Single] = Header.createRendered(
    ci"X-Session-Token",
    _.token.coerce[Generation].coerce[PosLong].toString,
    parse(_)
  )

}

final case class `X-Session-Token`(token: SessionToken)

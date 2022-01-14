package org.tessellation.sdk.http.p2p.headers

import java.util.UUID

import cats.syntax.either._

import scala.util.Try

import org.tessellation.schema.cluster.SessionToken

import io.estatico.newtype.ops._
import org.http4s.{Header, ParseFailure, ParseResult}
import org.typelevel.ci._

object `X-Session-Token` {

  def parse(s: String): ParseResult[`X-Session-Token`] =
    Try(UUID.fromString(s)).toEither
      .leftMap(e => ParseFailure("Invalid X-Session-Token header", e.getMessage()))
      .map(t => `X-Session-Token`(SessionToken(t)))

  implicit val headerInstance: Header[`X-Session-Token`, Header.Single] = Header.createRendered(
    ci"X-Session-Token",
    _.token.coerce[UUID].toString,
    parse(_)
  )

}

final case class `X-Session-Token`(token: SessionToken)

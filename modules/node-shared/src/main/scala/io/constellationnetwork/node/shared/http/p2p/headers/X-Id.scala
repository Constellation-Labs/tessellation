package io.constellationnetwork.node.shared.http.p2p.headers

import cats.syntax.either._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import io.estatico.newtype.ops._
import org.http4s.{Header, ParseFailure, ParseResult}
import org.typelevel.ci._

object `X-Id` {

  def parse(s: String): ParseResult[`X-Id`] =
    `X-Id`(PeerId(Hex(s))).asRight[ParseFailure]

  implicit val headerInstance: Header[`X-Id`, Header.Single] = Header.createRendered(
    ci"X-Id",
    _.id.coerce[Hex].coerce[String],
    parse(_)
  )

}

final case class `X-Id`(id: PeerId)

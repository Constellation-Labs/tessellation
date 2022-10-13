package org.tessellation.sdk.http.p2p.headers

import cats.syntax.either._

import org.tessellation.schema.hex._
import org.tessellation.schema.peer.PeerId

import eu.timepit.refined._
import eu.timepit.refined.auto._
import io.estatico.newtype.ops._
import org.http4s.{Header, ParseFailure, ParseResult}
import org.typelevel.ci._

object `X-Id` {

  def parse(s: String): ParseResult[`X-Id`] =
    refineV[HexString128Spec](s).map(hex => `X-Id`(PeerId(hex))).leftMap(ParseFailure("Invalid id", _))

  implicit val headerInstance: Header[`X-Id`, Header.Single] = Header.createRendered(
    ci"X-Id",
    _.id.coerce.value,
    parse(_)
  )

}

final case class `X-Id`(id: PeerId)

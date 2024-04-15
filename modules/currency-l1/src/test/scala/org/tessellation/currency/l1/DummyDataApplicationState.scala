package org.tessellation.currency.l1

import org.tessellation.currency.dataApplication.DataUpdate

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._
import io.circe.syntax.EncoderOps
import org.scalacheck.Gen

object DummyDataApplicationState {

  implicit val dataUpateEncoder: Encoder[DataUpdate] = {
    case event: DummyUpdate => event.asJson
    case _                  => Json.Null
  }

  implicit val dataUpdateDecoder: Decoder[DataUpdate] =
    (c: HCursor) => c.as[DummyUpdate]

  @derive(encoder, decoder, show)
  case class DummyUpdate(key: String, value: Long) extends DataUpdate

  val dummyUpdateGen: Gen[DummyUpdate] = for {
    key <- Gen.alphaStr
    value <- Gen.posNum[Long]
  } yield DummyUpdate(key, value)

}

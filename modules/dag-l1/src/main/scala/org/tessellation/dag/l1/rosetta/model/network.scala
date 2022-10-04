package org.tessellation.dag.l1.rosetta.model

import cats.Show
import cats.implicits.toBifunctorOps

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.predicates.all.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.estatico.newtype.macros.newtype
import io.getquill.MappedEncoding

object network {
  implicit def showNonEmptyString: Show[NonEmptyString] = s => s.value

  @derive(show)
  @newtype
  case class NetworkStatus(value: NonEmptyString)

  object NetworkStatus {
    val accepted: NetworkStatus = NetworkStatus("Accepted")
    val reverted: NetworkStatus = NetworkStatus("Reverted")
    val canceled: NetworkStatus = NetworkStatus("Canceled")

    implicit val quillEncode: MappedEncoding[NetworkStatus, String] =
      MappedEncoding[NetworkStatus, String](_.value.value)

    implicit val quillDecode: MappedEncoding[String, NetworkStatus] = MappedEncoding[String, NetworkStatus](
      refineV[NonEmpty].apply[String](_).leftMap(new Throwable(_)) match {
        case Left(err)    => throw err
        case Right(value) => NetworkStatus(value)
      }
    )

    def fromString(value: String): Option[NetworkStatus] = {
      val normalizedString = value.toLowerCase()

      if (normalizedString == accepted.value.value.toLowerCase())
        return Some(accepted)

      if (normalizedString == reverted.value.value.toLowerCase())
        return Some(reverted)

      if (normalizedString == canceled.value.value.toLowerCase())
        return Some(canceled)

      None
    }
  }
}

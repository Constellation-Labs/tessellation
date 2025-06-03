package io.constellationnetwork.currency.http

import cats.data.NonEmptyList
import cats.effect.Async
import cats.implicits.toFunctorOps

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.signature.Signed

import derevo.circe.magnolia.decoder
import derevo.derive
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.decode
import io.circe.shapes._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import shapeless.HNil
import shapeless.syntax.singleton._

object Codecs {

  def dataTransactionsDecoder[F[_]: Async, D <: DataUpdate](
    implicit dataUpdateDecoder: Decoder[D],
    feeTransactionDecoder: Decoder[FeeTransaction]
  ) = {

    case class DataTransactionRequest(
      data: Signed[D],
      fee: Option[Signed[FeeTransaction]]
    )
    implicit val dataTransactionRequestDecoder: Decoder[DataTransactionRequest] =
      deriveDecoder[DataTransactionRequest]

    EntityDecoder.text[F].flatMapR { body =>
      decode[DataTransactionRequest](body) match {
        case Right(req) =>
          val transactions = req.data :: req.fee.toList.map(_.widen[DataTransaction])
          NonEmptyList.fromList(transactions) match {
            case Some(nel) => DecodeResult.successT[F, DataTransactions](nel)
            case None =>
              DecodeResult.failureT[F, DataTransactions](
                InvalidMessageBodyFailure("No transactions found in request")
              )
          }

        case Left(circeError) =>
          DecodeResult.failureT[F, DataTransactions](
            MalformedMessageBodyFailure(
              s"JSON decode failed: ${circeError.getMessage}",
              Some(circeError)
            )
          )
      }
    }
  }

  def feeTransactionResponseEncoder[F[_]: Async](
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  ): F[Response[F]] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._
    import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

    def createErrorResponse(errorMsg: String): F[Response[F]] =
      BadRequest(("error" ->> errorMsg) :: HNil)

    def createResponseData(hashedTransactions: NonEmptyList[Hashed[DataTransaction]]) =
      dataRequest match {
        case _: SingleDataUpdateRequest =>
          Ok(
            ("hash" ->> hashedTransactions.head.hash.value) ::
              ("feeHash" ->> None) ::
              HNil
          )

        case _: DataTransactionsRequest =>
          hashedTransactions.toList.partition(_.signed.value.isInstanceOf[DataUpdate]) match {
            case (Nil, Nil) =>
              BadRequest(("error" ->> "Could not find data transactions or fee transactions") :: HNil)

            case (Nil, _) =>
              BadRequest(("error" ->> "Could not find valid data transactions") :: HNil)

            case (data, Nil) =>
              Ok(
                ("hash" ->> data.head.hash.value) ::
                  ("feeHash" ->> None) ::
                  HNil
              )

            case (data, fees) =>
              Ok(
                ("hash" ->> data.head.hash.value) ::
                  ("feeHash" ->> fees.head.hash.value) ::
                  HNil
              )
          }
      }

    validationResult match {
      case Left(error: DataApplicationValidationError) =>
        createErrorResponse(error.message)
      case Right(hashedTransactions) =>
        createResponseData(hashedTransactions)
    }
  }
}

package io.constellationnetwork.currency.dataApplication.services

import cats.MonadThrow
import cats.syntax.all._

import scala.reflect.ClassTag

import io.constellationnetwork.currency.dataApplication.{DataApplicationBlock, _}
import io.constellationnetwork.security.signature.Signed

import io.circe._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.jsonEncoderOf

trait BaseDataApplicationService[F[_]] {
  def serializeState(state: DataOnChainState): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]]

  def serializeUpdate(update: DataUpdate): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]]

  def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]]
  def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]]

  def dataEncoder: Encoder[DataUpdate]
  def dataDecoder: Decoder[DataUpdate]

  def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]]
  def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]]
}

object BaseDataApplicationService {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationService[F, D, DON, DOF]
  )(
    implicit d: ClassTag[D],
    don: ClassTag[DON],
    dof: ClassTag[DOF],
    monadThrow: MonadThrow[F]
  ): BaseDataApplicationService[F] =
    new BaseDataApplicationService[F] {

      def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] =
        service.serializeBlock(block)

      def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] =
        service.deserializeBlock(bytes)

      def serializeState(state: DataOnChainState): F[Array[Byte]] =
        state match {
          case on: DON => service.serializeState(on)
          case _       => UnexpectedInput.raiseError[F, Array[Byte]]
        }

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] =
        service.deserializeState(bytes).map(_.widen[DataOnChainState])

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] =
        update match {
          case d: D => service.serializeUpdate(d)
          case _    => UnexpectedInput.raiseError[F, Array[Byte]]
        }

      def deserializeUpdate(update: Array[Byte]): F[Either[Throwable, DataUpdate]] =
        service.deserializeUpdate(update).map(_.widen[DataUpdate])

      def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]] =
        state match {
          case a: DOF => service.serializeCalculatedState(a)
          case _      => UnexpectedInput.raiseError[F, Array[Byte]]
        }

      def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]] =
        service.deserializeCalculatedState(bytes).map(_.widen[DataCalculatedState])

      def dataEncoder: Encoder[DataUpdate] = new Encoder[DataUpdate] {
        final def apply(a: DataUpdate): Json = a match {
          case data: D => data.asJson(service.dataEncoder)
          case _       => Json.Null
        }
      }

      def dataDecoder: Decoder[DataUpdate] = service.dataDecoder.widen[DataUpdate]

      def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]] =
        jsonEncoderOf[F, Signed[DataUpdate]](Signed.encoder[DataUpdate](dataEncoder))

      def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]] =
        service.signedDataEntityDecoder.widen[Signed[DataUpdate]]

      def calculatedStateEncoder: Encoder[DataCalculatedState] = {
        case data: DOF => data.asJson(service.calculatedStateEncoder)
        case _         => Json.Null
      }

      def calculatedStateDecoder: Decoder[DataCalculatedState] = service.calculatedStateDecoder.widen[DataCalculatedState]
    }
}

package org.tessellation.currency

import cats.MonadThrow
import cats.data.{NonEmptyList, Validated, ValidatedNec}
import cats.syntax.all._

import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import org.tessellation.security.Encodable
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, _}

import dataApplication.DataApplicationValidationErrorOr

trait DataUpdate
trait DataState

case object Noop extends DataApplicationValidationError {
  val message = "invalid update"
}

case object UnexpectedInput extends NoStackTrace

trait BaseDataApplicationService[F[_]] {
  def validateData(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]]): F[DataApplicationValidationErrorOr[Unit]]
  def validateUpdate(update: DataUpdate): F[DataApplicationValidationErrorOr[Unit]]
  def combine(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]]): F[DataState]

  def serializeState(state: DataState): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataState]]

  def serializeUpdate(update: DataUpdate): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]]

  def dataEncoder: Encoder[DataUpdate]
  def dataDecoder: Decoder[DataUpdate]
}

trait BaseDataApplicationL0Service[F[_]] extends BaseDataApplicationService[F] {
  def genesis: DataState

  final def serializedGenesis: F[Array[Byte]] = serializeState(genesis)
}

trait BaseDataApplicationL1Service[F[_]] extends BaseDataApplicationService[F]

trait DataApplicationService[F[_], D <: DataUpdate, S <: DataState] {
  def validateData(oldState: S, updates: NonEmptyList[Signed[D]]): F[DataApplicationValidationErrorOr[Unit]]
  def validateUpdate(update: D): F[DataApplicationValidationErrorOr[Unit]]
  def combine(oldState: S, updates: NonEmptyList[Signed[D]]): F[S]

  def serializeState(state: S): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, S]]

  def serializeUpdate(update: D): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, D]]

  def dataEncoder: Encoder[D]
  def dataDecoder: Decoder[D]
}

trait DataApplicationL0Service[F[_], D <: DataUpdate, S <: DataState] extends DataApplicationService[F, D, S] {
  def genesis: S
}

trait DataApplicationL1Service[F[_], D <: DataUpdate, S <: DataState] extends DataApplicationService[F, D, S]


object BaseDataApplicationService {
  def apply[F[_], D <: DataUpdate, S <: DataState](
    service: DataApplicationService[F, D, S]
  )(implicit d: ClassTag[D], s: ClassTag[S], monadThrow: MonadThrow[F]): BaseDataApplicationService[F] =
    new BaseDataApplicationService[F] {

      def allKnown(updates: NonEmptyList[Signed[DataUpdate]]): Boolean =
        updates.map(_.value).forall { case _: D => true; case _ => false }

      def validateData(
        oldState: DataState,
        updates: NonEmptyList[Signed[DataUpdate]]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        oldState match {
          case s: S if allKnown(updates) =>
            service.validateData(s, updates.asInstanceOf[NonEmptyList[Signed[D]]])
          case _ => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      def validateUpdate(update: DataUpdate): F[DataApplicationValidationErrorOr[Unit]] =
        update match {
          case d: D => service.validateUpdate(d)
          case _    => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      def combine(
        oldState: DataState,
        updates: NonEmptyList[Signed[DataUpdate]]
      ): F[DataState] =
        oldState match {
          case state: S if allKnown(updates) =>
            service.combine(state, updates.asInstanceOf[NonEmptyList[Signed[D]]]).widen[DataState]
          case a => a.pure[F]
        }

      def serializeState(state: DataState): F[Array[Byte]] =
        state match {
          case s: S => service.serializeState(s)
          case _    => UnexpectedInput.raiseError[F, Array[Byte]]
        }

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataState]] =
        service.deserializeState(bytes).map(_.widen[DataState])

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] =
        update match {
          case d: D => service.serializeUpdate(d)
          case _    => UnexpectedInput.raiseError[F, Array[Byte]]
        }

      def deserializeUpdate(update: Array[Byte]): F[Either[Throwable, DataUpdate]] =
        service.deserializeUpdate(update).map(_.widen[DataUpdate])

      def dataEncoder: Encoder[DataUpdate] = new Encoder[DataUpdate] {
        final def apply(a: DataUpdate): Json = a match {
          case data: D => data.asJson(service.dataEncoder)
          case _       => Json.Null
        }
      }

      def dataDecoder: Decoder[DataUpdate] = new Decoder[DataUpdate] {
        final def apply(c: HCursor): Decoder.Result[DataUpdate] =
          service.dataDecoder.tryDecode(c).widen[DataUpdate]
      }

    }
}

object BaseDataApplicationL0Service {
  def apply[F[_], D <: DataUpdate, S <: DataState](
    service: DataApplicationL0Service[F, D, S]
  )(implicit d: ClassTag[D], s: ClassTag[S], monadThrow: MonadThrow[F]): BaseDataApplicationL0Service[F] = {
    val a = BaseDataApplicationService.apply[F, D, S](service)

    new BaseDataApplicationL0Service[F] {
      def validateData(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]]): F[DataApplicationValidationErrorOr[Unit]] =
        a.validateData(oldState, updates)

      def validateUpdate(update: DataUpdate): F[DataApplicationValidationErrorOr[Unit]] =
        a.validateUpdate(update)

      def combine(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]]): F[DataState] = a.combine(oldState, updates)

      def serializeState(state: DataState): F[Array[Byte]] = a.serializeState(state)

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataState]] = a.deserializeState(bytes)

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] = a.serializeUpdate(update)

      def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] = a.deserializeUpdate(bytes)

      def dataEncoder: Encoder[DataUpdate] = a.dataEncoder

      def dataDecoder: Decoder[DataUpdate] = a.dataDecoder

      def genesis: DataState = service.genesis
    }

  }
}

object BaseDataApplicationL1Service {
  def apply[F[+_], D <: DataUpdate, S <: DataState](
    service: DataApplicationL1Service[F, D, S]
  )(implicit d: ClassTag[D], s: ClassTag[S], monadThrow: MonadThrow[F]): BaseDataApplicationL1Service[F] = {
    val base = BaseDataApplicationService.apply[F, D, S](service)

    new BaseDataApplicationL1Service[F] {
      def validateData(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]]): F[DataApplicationValidationErrorOr[Unit]] =
        base.validateData(oldState, updates)

      def validateUpdate(update: DataUpdate): F[DataApplicationValidationErrorOr[Unit]] =
        base.validateUpdate(update)

      def combine(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]]): F[DataState] = base.combine(oldState, updates)

      def serializeState(state: DataState): F[Array[Byte]] = base.serializeState(state)

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataState]] = base.deserializeState(bytes)

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] = base.serializeUpdate(update)

      def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] = base.deserializeUpdate(bytes)

      def dataEncoder: Encoder[DataUpdate] = base.dataEncoder

      def dataDecoder: Decoder[DataUpdate] = base.dataDecoder
    }

  }
}

trait DataApplicationValidationError {
  val message: String
}

object dataApplication {

  type DataApplicationValidationErrorOr[A] = ValidatedNec[DataApplicationValidationError, A]

  case class DataApplicationBlock(
    updates: NonEmptyList[Signed[DataUpdate]],
    updatesHashes: NonEmptyList[Hash]
  ) extends Encodable {
    override def toEncode: AnyRef = updatesHashes
  }

  object DataApplicationBlock {
    implicit def decoder(implicit d: Decoder[DataUpdate]): Decoder[DataApplicationBlock] = deriveDecoder
    implicit def encoder(implicit e: Encoder[DataUpdate]): Encoder[DataApplicationBlock] = deriveEncoder
  }

}

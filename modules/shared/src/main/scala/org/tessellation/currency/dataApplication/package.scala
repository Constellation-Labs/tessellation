package org.tessellation.currency.dataApplication

import cats.data.{NonEmptyList, Validated, ValidatedNec}
import cats.syntax.all._
import cats.{Monad, MonadThrow}

import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import org.tessellation.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import org.tessellation.currency.schema.currency.CurrencyIncrementalSnapshot
import org.tessellation.schema.GlobalIncrementalSnapshot
import org.tessellation.schema.round.RoundId
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Encodable, Hashed, SecurityProvider}

import io.circe._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import org.http4s.HttpRoutes
import org.http4s.server.Router

trait DataUpdate
trait DataState

case object Noop extends DataApplicationValidationError {
  val message = "invalid update"
}

case object UnexpectedInput extends NoStackTrace

trait BaseDataApplicationService[F[_]] {
  def serializeState(state: DataState): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataState]]

  def serializeUpdate(update: DataUpdate): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]]

  def dataEncoder: Encoder[DataUpdate]
  def dataDecoder: Decoder[DataUpdate]
}
trait BaseDataApplicationContextualOps[F[_], Context] {
  def validateData(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(
    implicit context: Context
  ): F[DataApplicationValidationErrorOr[Unit]]

  def validateUpdate(update: DataUpdate)(implicit context: Context): F[DataApplicationValidationErrorOr[Unit]]

  def combine(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(implicit context: Context): F[DataState]

  def routes(implicit context: Context): HttpRoutes[F]
}

trait BaseDataApplicationL0Service[F[_]] extends BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, L0NodeContext[F]] {

  def genesis: DataState

  final def serializedGenesis: F[Array[Byte]] = serializeState(genesis)
}

trait BaseDataApplicationL1Service[F[_]]
    extends BaseDataApplicationService[F]
    with BaseDataApplicationContextualOps[
      F,
      L1NodeContext[F]
    ]

trait DataApplicationService[F[_], D <: DataUpdate, S <: DataState] {

  def serializeState(state: S): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, S]]

  def serializeUpdate(update: D): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, D]]

  def dataEncoder: Encoder[D]
  def dataDecoder: Decoder[D]

}

trait DataApplicationContextualOps[F[_], D <: DataUpdate, S <: DataState, Context] {
  def validateData(oldState: S, updates: NonEmptyList[Signed[D]])(
    implicit context: Context
  ): F[DataApplicationValidationErrorOr[Unit]]

  def validateUpdate(update: D)(implicit context: Context): F[DataApplicationValidationErrorOr[Unit]]

  def combine(oldState: S, updates: NonEmptyList[Signed[D]])(implicit context: Context): F[S]

  def routes(implicit context: Context): HttpRoutes[F]
}

trait DataApplicationL0Service[F[_], D <: DataUpdate, S <: DataState]
    extends DataApplicationService[F, D, S]
    with DataApplicationContextualOps[F, D, S, L0NodeContext[F]] {
  def genesis: S
}

trait DataApplicationL1Service[F[_], D <: DataUpdate, S <: DataState]
    extends DataApplicationService[F, D, S]
    with DataApplicationContextualOps[F, D, S, L1NodeContext[F]]

object BaseDataApplicationContextualOps {
  def apply[F[_], D <: DataUpdate, S <: DataState, Context](
    service: DataApplicationContextualOps[F, D, S, Context]
  )(implicit d: ClassTag[D], s: ClassTag[S], monadThrow: MonadThrow[F]): BaseDataApplicationContextualOps[F, Context] =
    new BaseDataApplicationContextualOps[F, Context] {
      def allKnown(updates: NonEmptyList[Signed[DataUpdate]]): Boolean =
        updates.map(_.value).forall { case _: D => true; case _ => false }

      def validateData(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: Context
      ): F[DataApplicationValidationErrorOr[Unit]] =
        oldState match {
          case s: S if allKnown(updates) =>
            service.validateData(s, updates.asInstanceOf[NonEmptyList[Signed[D]]])
          case _ => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      def validateUpdate(update: DataUpdate)(implicit context: Context): F[DataApplicationValidationErrorOr[Unit]] =
        update match {
          case d: D => service.validateUpdate(d)
          case _    => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      def combine(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(implicit context: Context): F[DataState] =
        oldState match {
          case state: S if allKnown(updates) =>
            service.combine(state, updates.asInstanceOf[NonEmptyList[Signed[D]]]).widen[DataState]
          case a => a.pure[F]
        }

      def routes(implicit context: Context): HttpRoutes[F] = service.routes
    }
}

object BaseDataApplicationService {
  def apply[F[_], D <: DataUpdate, S <: DataState, Context](
    service: DataApplicationService[F, D, S],
    validation: DataApplicationContextualOps[F, D, S, Context]
  )(
    implicit d: ClassTag[D],
    s: ClassTag[S],
    monadThrow: MonadThrow[F]
  ): BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, Context] =
    new BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, Context] {

      val v = BaseDataApplicationContextualOps[F, D, S, Context](validation)

      def validateData(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: Context
      ): F[DataApplicationValidationErrorOr[Unit]] =
        v.validateData(oldState, updates)

      def validateUpdate(update: DataUpdate)(implicit context: Context): F[DataApplicationValidationErrorOr[Unit]] =
        v.validateUpdate(update)

      def combine(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(implicit context: Context): F[DataState] =
        v.combine(oldState, updates)

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

      def routes(implicit context: Context): HttpRoutes[F] = v.routes
    }
}

object BaseDataApplicationL0Service {
  def apply[F[_], D <: DataUpdate, S <: DataState](
    service: DataApplicationL0Service[F, D, S]
  )(implicit d: ClassTag[D], s: ClassTag[S], monadThrow: MonadThrow[F]): BaseDataApplicationL0Service[F] = {
    val base = BaseDataApplicationService.apply[F, D, S, L0NodeContext[F]](service, service)

    new BaseDataApplicationL0Service[F] {

      def serializeState(state: DataState): F[Array[Byte]] = base.serializeState(state)

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataState]] = base.deserializeState(bytes)

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] = base.serializeUpdate(update)

      def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] = base.deserializeUpdate(bytes)

      def dataEncoder: Encoder[DataUpdate] = base.dataEncoder

      def dataDecoder: Decoder[DataUpdate] = base.dataDecoder

      def genesis: DataState = service.genesis

      def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] = base.routes

      def validateData(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] = base.validateData(oldState, updates)

      def validateUpdate(update: DataUpdate)(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
        base.validateUpdate(update)

      def combine(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataState] =
        base.combine(oldState, updates)
    }
  }
}

object BaseDataApplicationL1Service {
  def apply[F[+_], D <: DataUpdate, S <: DataState](
    service: DataApplicationL1Service[F, D, S]
  )(implicit d: ClassTag[D], s: ClassTag[S], monadThrow: MonadThrow[F]): BaseDataApplicationL1Service[F] = {
    val base = BaseDataApplicationService.apply[F, D, S, L1NodeContext[F]](service, service)

    new BaseDataApplicationL1Service[F] {

      def serializeState(state: DataState): F[Array[Byte]] = base.serializeState(state)

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataState]] = base.deserializeState(bytes)

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] = base.serializeUpdate(update)

      def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] = base.deserializeUpdate(bytes)

      def dataEncoder: Encoder[DataUpdate] = base.dataEncoder

      def dataDecoder: Decoder[DataUpdate] = base.dataDecoder

      def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = base.routes

      def validateData(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L1NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] = base.validateData(oldState, updates)

      def validateUpdate(update: DataUpdate)(
        implicit context: L1NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        base.validateUpdate(update)

      def combine(oldState: DataState, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L1NodeContext[F]
      ): F[DataState] = base.combine(oldState, updates)
    }

  }
}

trait DataApplicationValidationError {
  val message: String
}

object dataApplication {

  type DataApplicationValidationErrorOr[A] = ValidatedNec[DataApplicationValidationError, A]

  case class DataApplicationBlock(
    roundId: RoundId,
    updates: NonEmptyList[Signed[DataUpdate]],
    updatesHashes: NonEmptyList[Hash]
  ) extends Encodable {
    override def toEncode: AnyRef = updatesHashes
  }

  object DataApplicationBlock {
    implicit def decoder(implicit d: Decoder[DataUpdate]): Decoder[DataApplicationBlock] = deriveDecoder
    implicit def encoder(implicit e: Encoder[DataUpdate]): Encoder[DataApplicationBlock] = deriveEncoder
  }

  object DataApplicationCustomRoutes {
    def publicRoutes[F[_]: Monad, Context](
      maybeDataApplication: Option[BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, Context]]
    )(implicit context: Context): HttpRoutes[F] =
      maybeDataApplication.map(_.routes).map(r => Router("/data-application" -> r)).getOrElse(HttpRoutes.empty[F])
  }

}

trait L1NodeContext[F[_]] {
  def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]]
  def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def securityProvider: SecurityProvider[F]
}

trait L0NodeContext[F[_]] {
  def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]]
  def securityProvider: SecurityProvider[F]
}

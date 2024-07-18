package org.tessellation.currency.dataApplication

import cats.data.{NonEmptyList, Validated, ValidatedNec}
import cats.kernel.Eq
import cats.syntax.all._
import cats.{Applicative, Monad, MonadThrow}

import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import org.tessellation.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.currency.schema.feeTransaction.FeeTransaction
import org.tessellation.routes.internal.ExternalUrlPrefix
import org.tessellation.schema.round.RoundId
import org.tessellation.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Encodable, Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.server.Router

trait DataUpdate

trait DataOnChainState
trait DataCalculatedState

case class DataState[A <: DataOnChainState, B <: DataCalculatedState](
  onChain: A,
  calculated: B
) {
  def asBase: DataState[DataOnChainState, DataCalculatedState] = DataState(onChain, calculated)
}

object DataState {
  type Base = DataState[DataOnChainState, DataCalculatedState]
}

case object Noop extends DataApplicationValidationError {
  val message = "invalid update"
}

case class DataApplicationFeeError(message: String) extends DataApplicationValidationError

case object UnexpectedInput extends NoStackTrace

trait BaseDataApplicationService[F[_]] {
  def serializeState(state: DataOnChainState): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]]

  def serializeUpdate(update: DataUpdate): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]]

  def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]]
  def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]]

  def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]]

  def dataEncoder: Encoder[DataUpdate]
  def dataDecoder: Decoder[DataUpdate]

  def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]]

  def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]]

  def calculatedStateEncoder: Encoder[DataCalculatedState]
  def calculatedStateDecoder: Decoder[DataCalculatedState]
}

trait BaseDataApplicationContextualOps[F[_], Context] {
  def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
    implicit context: Context
  ): F[DataApplicationValidationErrorOr[Unit]]

  def validateUpdate(update: DataUpdate)(implicit context: Context): F[DataApplicationValidationErrorOr[Unit]]

  def validateFee(gsOrdinal: SnapshotOrdinal)(update: Signed[DataUpdate])(
    implicit context: Context,
    A: Applicative[F]
  ): F[DataApplicationValidationErrorOr[Unit]] = ().validNec[DataApplicationValidationError].pure[F]

  def extractFees(ds: Seq[Signed[DataUpdate]])(implicit context: Context, A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    A.pure(Seq.empty)

  def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(implicit context: Context): F[DataState.Base]

  def getCalculatedState(implicit context: Context): F[(SnapshotOrdinal, DataCalculatedState)]
  def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: Context): F[Boolean]
  def hashCalculatedState(state: DataCalculatedState)(implicit context: Context): F[Hash]

  def routes(implicit context: Context): HttpRoutes[F]

  def routesPrefix: ExternalUrlPrefix
}

trait BaseDataApplicationL0Service[F[_]] extends BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, L0NodeContext[F]] {

  def genesis: DataState.Base

  final def serializedOnChainGenesis: F[Array[Byte]] = serializeState(genesis.onChain)

  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): F[Unit]
}

trait BaseDataApplicationL1Service[F[_]] extends BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, L1NodeContext[F]]

trait DataApplicationService[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState] {
  def serializeState(state: DON): F[Array[Byte]]
  def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DON]]

  def serializeUpdate(update: D): F[Array[Byte]]
  def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, D]]

  def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]]
  def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]]

  def serializeCalculatedState(state: DOF): F[Array[Byte]]
  def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DOF]]

  def dataEncoder: Encoder[D]
  def dataDecoder: Decoder[D]

  def signedDataEntityDecoder: EntityDecoder[F, Signed[D]]

  def calculatedStateEncoder: Encoder[DOF]
  def calculatedStateDecoder: Decoder[DOF]
}

trait DataApplicationContextualOps[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState, Context] {
  def validateData(state: DataState[DON, DOF], updates: NonEmptyList[Signed[D]])(
    implicit context: Context
  ): F[DataApplicationValidationErrorOr[Unit]]

  def validateUpdate(update: D)(implicit context: Context): F[DataApplicationValidationErrorOr[Unit]]

  def validateFee(gsOrdinal: SnapshotOrdinal)(update: Signed[D])(
    implicit context: Context,
    A: Applicative[F]
  ): F[DataApplicationValidationErrorOr[Unit]] = ().validNec[DataApplicationValidationError].pure[F]

  def extractFees(ds: Seq[Signed[D]])(implicit context: Context, A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    A.pure(Seq.empty)

  def combine(state: DataState[DON, DOF], updates: List[Signed[D]])(implicit context: Context): F[DataState[DON, DOF]]

  def getCalculatedState(implicit context: Context): F[(SnapshotOrdinal, DOF)]

  def setCalculatedState(ordinal: SnapshotOrdinal, state: DOF)(implicit context: Context): F[Boolean]

  def hashCalculatedState(state: DOF)(implicit context: Context): F[Hash]

  def routes(implicit context: Context): HttpRoutes[F]

  def routesPrefix: ExternalUrlPrefix = "/data-application"
}

trait DataApplicationL0Service[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationService[F, D, DON, DOF]
    with DataApplicationContextualOps[F, D, DON, DOF, L0NodeContext[F]] {
  def genesis: DataState[DON, DOF]

  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit A: Applicative[F]): F[Unit] = A.unit
}

trait DataApplicationL1Service[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationService[F, D, DON, DOF]
    with DataApplicationContextualOps[F, D, DON, DOF, L1NodeContext[F]]

object BaseDataApplicationContextualOps {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState, Context](
    service: DataApplicationContextualOps[F, D, DON, DOF, Context]
  )(
    implicit d: ClassTag[D],
    don: ClassTag[DON],
    dof: ClassTag[DOF],
    monadThrow: MonadThrow[F]
  ): BaseDataApplicationContextualOps[F, Context] =
    new BaseDataApplicationContextualOps[F, Context] {
      def allKnown(updates: List[Signed[DataUpdate]]): Boolean =
        updates.map(_.value).forall { case _: D => true; case _ => false }

      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: Context
      ): F[DataApplicationValidationErrorOr[Unit]] =
        (state.onChain, state.calculated) match {
          case (on: DON, off: DOF) if allKnown(updates.toList) =>
            service.validateData(DataState(on, off), updates.asInstanceOf[NonEmptyList[Signed[D]]])
          case _ => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      def validateUpdate(update: DataUpdate)(implicit context: Context): F[DataApplicationValidationErrorOr[Unit]] =
        update match {
          case d: D => service.validateUpdate(d)
          case _    => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      override def validateFee(gsOrdinal: SnapshotOrdinal)(update: Signed[DataUpdate])(
        implicit context: Context,
        A: Applicative[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        service.validateFee(gsOrdinal)(update.asInstanceOf[Signed[D]])

      override def extractFees(ds: Seq[Signed[DataUpdate]])(implicit context: Context, A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
        service.extractFees(ds.asInstanceOf[Seq[Signed[D]]])

      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: Context
      ): F[DataState.Base] =
        (state.onChain, state.calculated) match {
          case (on: DON, off: DOF) if allKnown(updates) =>
            service.combine(DataState(on, off), updates.asInstanceOf[List[Signed[D]]]).map(_.asBase)
          case (_, _) => UnexpectedInput.raiseError[F, DataState.Base]
        }

      def getCalculatedState(implicit context: Context): F[(SnapshotOrdinal, DataCalculatedState)] =
        service.getCalculatedState.widen[(SnapshotOrdinal, DataCalculatedState)]

      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: Context): F[Boolean] =
        state match {
          case s: DOF => service.setCalculatedState(ordinal, s)
          case _      => UnexpectedInput.raiseError[F, Boolean]
        }

      def hashCalculatedState(state: DataCalculatedState)(implicit context: Context): F[Hash] =
        state match {
          case s: DOF => service.hashCalculatedState(s)
          case _      => UnexpectedInput.raiseError[F, Hash]
        }

      def routes(implicit context: Context): HttpRoutes[F] = service.routes

      def routesPrefix: ExternalUrlPrefix = service.routesPrefix
    }
}

object BaseDataApplicationService {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState, Context](
    service: DataApplicationService[F, D, DON, DOF],
    validation: DataApplicationContextualOps[F, D, DON, DOF, Context]
  )(
    implicit d: ClassTag[D],
    don: ClassTag[DON],
    dof: ClassTag[DOF],
    monadThrow: MonadThrow[F]
  ): BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, Context] =
    new BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, Context] {

      val v = BaseDataApplicationContextualOps[F, D, DON, DOF, Context](validation)

      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: Context
      ): F[DataApplicationValidationErrorOr[Unit]] =
        v.validateData(state, updates)

      def validateUpdate(update: DataUpdate)(implicit context: Context): F[DataApplicationValidationErrorOr[Unit]] =
        v.validateUpdate(update)

      override def validateFee(gsOrdinal: SnapshotOrdinal)(
        update: Signed[DataUpdate]
      )(implicit context: Context, A: Applicative[F]): F[DataApplicationValidationErrorOr[Unit]] =
        v.validateFee(gsOrdinal)(update)

      override def extractFees(ds: Seq[Signed[DataUpdate]])(implicit context: Context, A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
        v.extractFees(ds)

      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: Context
      ): F[DataState.Base] =
        v.combine(state, updates)

      def getCalculatedState(implicit context: Context): F[(SnapshotOrdinal, DataCalculatedState)] = v.getCalculatedState

      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: Context): F[Boolean] =
        v.setCalculatedState(ordinal, state)

      def hashCalculatedState(state: DataCalculatedState)(implicit context: Context): F[Hash] =
        v.hashCalculatedState(state)

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

      def calculatedStateEncoder: Encoder[DataCalculatedState] = new Encoder[DataCalculatedState] {
        final def apply(a: DataCalculatedState): Json = a match {
          case data: DOF => data.asJson(service.calculatedStateEncoder)
          case _         => Json.Null
        }
      }

      def calculatedStateDecoder: Decoder[DataCalculatedState] = service.calculatedStateDecoder.widen[DataCalculatedState]

      def routes(implicit context: Context): HttpRoutes[F] = v.routes

      def routesPrefix: ExternalUrlPrefix = v.routesPrefix
    }
}

object BaseDataApplicationL0Service {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL0Service[F, D, DON, DOF]
  )(implicit d: ClassTag[D], don: ClassTag[DON], dof: ClassTag[DOF], monadThrow: MonadThrow[F]): BaseDataApplicationL0Service[F] = {
    val base = BaseDataApplicationService.apply[F, D, DON, DOF, L0NodeContext[F]](service, service)

    new BaseDataApplicationL0Service[F] {

      def serializeState(state: DataOnChainState): F[Array[Byte]] = base.serializeState(state)

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] = base.deserializeState(bytes)

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] = base.serializeUpdate(update)

      def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] = base.deserializeUpdate(bytes)

      def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] = base.serializeBlock(block)

      def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] = base.deserializeBlock(bytes)

      def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]] = base.serializeCalculatedState(state)

      def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]] = base.deserializeCalculatedState(bytes)

      def dataEncoder: Encoder[DataUpdate] = base.dataEncoder

      def dataDecoder: Decoder[DataUpdate] = base.dataDecoder

      def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]] = base.signedDataEntityEncoder

      def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]] = base.signedDataEntityDecoder

      def genesis: DataState.Base = service.genesis.asBase

      def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] = base.routes

      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] = base.validateData(state, updates)

      def validateUpdate(update: DataUpdate)(implicit context: L0NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
        base.validateUpdate(update)

      override def validateFee(gsOrdinal: SnapshotOrdinal)(
        update: Signed[DataUpdate]
      )(implicit context: L0NodeContext[F], A: Applicative[F]): F[DataApplicationValidationErrorOr[Unit]] =
        base.validateFee(gsOrdinal)(update)

      override def extractFees(
        ds: Seq[Signed[DataUpdate]]
      )(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
        base.extractFees(ds)

      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataState.Base] =
        base.combine(state, updates)

      def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)] =
        base.getCalculatedState

      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean] =
        base.setCalculatedState(ordinal, state)

      def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
        base.hashCalculatedState(state)

      def calculatedStateDecoder: Decoder[DataCalculatedState] = base.calculatedStateDecoder

      def calculatedStateEncoder: Encoder[DataCalculatedState] = base.calculatedStateEncoder

      def routesPrefix: ExternalUrlPrefix = base.routesPrefix

      def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): F[Unit] = service.onSnapshotConsensusResult(snapshot)
    }
  }
}

object BaseDataApplicationL1Service {
  def apply[F[+_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL1Service[F, D, DON, DOF]
  )(implicit d: ClassTag[D], don: ClassTag[DON], dof: ClassTag[DOF], monadThrow: MonadThrow[F]): BaseDataApplicationL1Service[F] = {
    val base = BaseDataApplicationService.apply[F, D, DON, DOF, L1NodeContext[F]](service, service)

    new BaseDataApplicationL1Service[F] {

      def serializeState(state: DataOnChainState): F[Array[Byte]] = base.serializeState(state)

      def deserializeState(bytes: Array[Byte]): F[Either[Throwable, DataOnChainState]] = base.deserializeState(bytes)

      def serializeUpdate(update: DataUpdate): F[Array[Byte]] = base.serializeUpdate(update)

      def deserializeUpdate(bytes: Array[Byte]): F[Either[Throwable, DataUpdate]] = base.deserializeUpdate(bytes)

      def serializeBlock(block: Signed[DataApplicationBlock]): F[Array[Byte]] = base.serializeBlock(block)

      def deserializeBlock(bytes: Array[Byte]): F[Either[Throwable, Signed[DataApplicationBlock]]] = base.deserializeBlock(bytes)

      def serializeCalculatedState(state: DataCalculatedState): F[Array[Byte]] = base.serializeCalculatedState(state)

      def deserializeCalculatedState(bytes: Array[Byte]): F[Either[Throwable, DataCalculatedState]] = base.deserializeCalculatedState(bytes)

      def dataEncoder: Encoder[DataUpdate] = base.dataEncoder

      def dataDecoder: Decoder[DataUpdate] = base.dataDecoder

      def signedDataEntityEncoder: EntityEncoder[F, Signed[DataUpdate]] = base.signedDataEntityEncoder

      def signedDataEntityDecoder: EntityDecoder[F, Signed[DataUpdate]] = base.signedDataEntityDecoder

      def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = base.routes

      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L1NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] = base.validateData(state, updates)

      def validateUpdate(update: DataUpdate)(
        implicit context: L1NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        base.validateUpdate(update)

      override def validateFee(gsOrdinal: SnapshotOrdinal)(
        update: Signed[DataUpdate]
      )(implicit context: L1NodeContext[F], A: Applicative[F]): F[DataApplicationValidationErrorOr[Unit]] =
        base.validateFee(gsOrdinal)(update)

      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L1NodeContext[F]
      ): F[DataState.Base] = base.combine(state, updates)

      def getCalculatedState(implicit context: L1NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)] =
        base.getCalculatedState

      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L1NodeContext[F]): F[Boolean] =
        base.setCalculatedState(ordinal, state)

      def hashCalculatedState(state: DataCalculatedState)(implicit context: L1NodeContext[F]): F[Hash] =
        base.hashCalculatedState(state)

      def calculatedStateDecoder: Decoder[DataCalculatedState] = base.calculatedStateDecoder

      def calculatedStateEncoder: Encoder[DataCalculatedState] = base.calculatedStateEncoder

      def routesPrefix: ExternalUrlPrefix = base.routesPrefix
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
  ) extends Encodable[NonEmptyList[Hash]] {
    override def toEncode = updatesHashes
    override def jsonEncoder: Encoder[NonEmptyList[Hash]] = implicitly
  }

  object DataApplicationBlock {
    implicit def decoder(implicit d: Decoder[DataUpdate]): Decoder[DataApplicationBlock] = deriveDecoder

    implicit def encoder(implicit e: Encoder[DataUpdate]): Encoder[DataApplicationBlock] = deriveEncoder

    implicit def eqv: Eq[DataApplicationBlock] =
      Eq.and[DataApplicationBlock](
        Eq[RoundId].contramap(_.roundId),
        Eq[NonEmptyList[Hash]].contramap(_.updatesHashes)
      )
  }

  object DataApplicationCustomRoutes {
    def publicRoutes[F[_]: Monad, Context](
      maybeDataApplication: Option[BaseDataApplicationService[F] with BaseDataApplicationContextualOps[F, Context]]
    )(implicit context: Context): HttpRoutes[F] =
      maybeDataApplication.map { da =>
        Router(da.routesPrefix.value -> da.routes)
      }.getOrElse(HttpRoutes.empty[F])
  }

}

trait L1NodeContext[F[_]] {
  def getLastGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]]
  def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
  def securityProvider: SecurityProvider[F]
}

trait L0NodeContext[F[_]] {
  def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getCurrencySnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
  def securityProvider: SecurityProvider[F]
}

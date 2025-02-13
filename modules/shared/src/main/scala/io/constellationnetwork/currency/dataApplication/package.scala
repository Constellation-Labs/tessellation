package io.constellationnetwork.currency.dataApplication

import cats._
import cats.data._
import cats.effect.Async
import cats.kernel.Eq
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.DataTransaction.{DataTransactions, collectTransactions}
import io.constellationnetwork.currency.dataApplication.Errors.Noop
import io.constellationnetwork.currency.dataApplication.FeeTransaction.serialize
import io.constellationnetwork.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import io.constellationnetwork.currency.http.Codecs.{feeTransactionRequestDecoder, feeTransactionResponseEncoder}
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.tokenLock.TokenLockReference
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import io.circe._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.jsonEncoderOf
import org.http4s.server.Router

sealed trait DataTransaction
object DataTransaction {
  type DataTransactions = NonEmptyList[Signed[DataTransaction]]

  implicit def encoder(implicit e: Encoder[DataUpdate]): Encoder[DataTransaction] =
    Encoder.instance {
      case v: FeeTransaction => v.asJson
      case v: DataUpdate     => e.apply(v)
    }

  implicit def decoder(implicit d: Decoder[DataUpdate]): Decoder[DataTransaction] =
    (c: HCursor) => Decoder[FeeTransaction].apply(c).orElse(d.apply(c))

  def collectTransactions[A <: DataTransaction](dataTransactions: List[DataTransactions])(
    pf: PartialFunction[DataTransaction, A]
  ): List[Signed[A]] =
    dataTransactions.flatMap { currentDataTransactions =>
      currentDataTransactions.collect { dataTransaction =>
        dataTransaction.value match {
          case transaction if pf.isDefinedAt(transaction) =>
            Signed(pf(transaction), dataTransaction.proofs)
        }
      }
    }

  def getHashes[F[_]: Async: Hasher](
    data: List[DataTransactions],
    serializeDataUpdate: DataUpdate => F[Array[Byte]]
  )(implicit jsonSerializer: JsonSerializer[F]): F[List[NonEmptyList[hash.Hash]]] =
    data.traverse { dataTransactions =>
      dataTransactions.toList.traverse { signedTransaction =>
        signedTransaction.value match {
          case dataUpdate: DataUpdate =>
            Signed(dataUpdate, signedTransaction.proofs)
              .toHashed(serializeDataUpdate)
              .map(_.hash)
          case feeTransaction: FeeTransaction =>
            Signed(feeTransaction, signedTransaction.proofs)
              .toHashed(serialize[F])
              .map(_.hash)
        }
      }.map(NonEmptyList.fromListUnsafe)
    }
}

trait DataUpdate extends DataTransaction
object DataUpdate {
  def getDataUpdates(dataTransactions: List[DataTransactions]): List[Signed[DataUpdate]] =
    collectTransactions(dataTransactions) {
      case dataUpdate: DataUpdate => dataUpdate
    }
}
@derive(decoder, encoder, order, ordering, show)
case class FeeTransaction(
  source: Address,
  destination: Address,
  amount: Amount,
  dataUpdateRef: Hash
) extends DataTransaction

object FeeTransaction {
  def serialize[F[_]: Async](feeTransaction: FeeTransaction)(implicit jsonSerializer: JsonSerializer[F]): F[Array[Byte]] =
    jsonSerializer.serialize(feeTransaction)

  def getFeeTransactions(dataTransactions: List[DataTransactions]): List[Signed[FeeTransaction]] =
    collectTransactions(dataTransactions) {
      case feeTransaction: FeeTransaction => feeTransaction
    }

  def getByDataUpdate[F[_]: Async](
    dataTransactions: DataTransactions,
    dataUpdate: DataUpdate,
    serializeDataUpdate: DataUpdate => F[Array[Byte]]
  ): F[Option[Signed[FeeTransaction]]] = {
    val feeTransactions = dataTransactions.collect {
      case Signed(feeTransaction: FeeTransaction, proofs) => Signed(feeTransaction, proofs)
    }

    serializeDataUpdate(dataUpdate).map { serializedDataUpdate =>
      feeTransactions.find { feeTransaction =>
        Hash.fromBytes(serializedDataUpdate) === feeTransaction.value.dataUpdateRef
      }
    }
  }

}

trait DataOnChainState
trait DataCalculatedState

case class DataState[A <: DataOnChainState, B <: DataCalculatedState](
  onChain: A,
  calculated: B,
  sharedArtifacts: SortedSet[SharedArtifact] = SortedSet.empty[SharedArtifact]
) {
  def asBase: DataState[DataOnChainState, DataCalculatedState] =
    DataState(onChain, calculated, sharedArtifacts)
}

object DataState {
  type Base = DataState[DataOnChainState, DataCalculatedState]
}

object Errors {
  case object Noop extends DataApplicationValidationError {
    val message = "invalid update"
  }
  case class MissingDataUpdateTransaction() extends DataApplicationValidationError {
    val message = "Could not find any data update transaction"
  }

  case class DataApplicationFeeError(message: String) extends DataApplicationValidationError

  case object NotEnoughFee extends DataApplicationValidationError {
    val message = "Not enough fees"
  }
  case object SourceWalletNotEnoughBalance extends DataApplicationValidationError {
    val message = "Source wallet not enough balance"
  }
  case object MissingDataUpdateOfFeeTransaction extends DataApplicationValidationError {
    val message = "Could not find data update for provided fee transaction"
  }
  case object MissingFeeTransaction extends DataApplicationValidationError {
    val message = "Missing fee transaction"
  }
  case object SourceWalletNotSignTheTransaction extends DataApplicationValidationError {
    val message = "Source wallet should sign the transaction"
  }
  case object InvalidSignature extends DataApplicationValidationError {
    val message = "Invalid signature in data transactions"
  }
  case object NoValidDataTransactions extends DataApplicationValidationError {
    val message = "No valid data transactions found"
  }
}

case object UnexpectedInput extends NoStackTrace

sealed trait DataRequest
case class SingleDataUpdateRequest(dataUpdate: Signed[DataUpdate]) extends DataRequest
case class DataTransactionsRequest(transactions: DataTransactions) extends DataRequest

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

trait BaseDataApplicationSharedContextualOps[F[_], Context] {

  def validateFee(gsOrdinal: SnapshotOrdinal)(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
    implicit context: Context,
    A: Applicative[F]
  ): F[DataApplicationValidationErrorOr[Unit]] = ().validNec[DataApplicationValidationError].pure[F]

  def routes(implicit context: Context): HttpRoutes[F]

  def routesPrefix: ExternalUrlPrefix
}

trait BaseDataApplicationL0ContextualOps[F[_]] extends BaseDataApplicationSharedContextualOps[F, L0NodeContext[F]] {

  def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
    implicit context: L0NodeContext[F]
  ): F[DataApplicationValidationErrorOr[Unit]]

  def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(implicit context: L0NodeContext[F]): F[DataState.Base]

  def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)]

  def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean]

  def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash]

  def extractFees(ds: Seq[Signed[DataUpdate]])(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    A.pure(Seq.empty)
}

trait BaseDataApplicationL1ContextualOps[F[_]] extends BaseDataApplicationSharedContextualOps[F, L1NodeContext[F]] {

  def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]]

  def estimateFee(gsOrdinal: SnapshotOrdinal)(update: DataUpdate)(
    implicit context: L1NodeContext[F],
    A: Applicative[F]
  ): F[EstimatedFee] = EstimatedFee.empty.pure[F]
}

trait BaseDataApplicationL0Service[F[_]] extends BaseDataApplicationService[F] with BaseDataApplicationL0ContextualOps[F] {

  def genesis: DataState.Base

  final def serializedOnChainGenesis: F[Array[Byte]] = serializeState(genesis.onChain)

  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): F[Unit]

  def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Unit]

  def extractFees(ds: Seq[Signed[DataUpdate]])(implicit A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    A.pure(Seq.empty[Signed[FeeTransaction]])

  def getTokenUnlocks(
    state: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], async: Async[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]]
}

trait BaseDataApplicationL1Service[F[_]] extends BaseDataApplicationService[F] with BaseDataApplicationL1ContextualOps[F] {
  def postDataTransactionsRequestDecoder(req: Request[F])(implicit f: Async[F]): F[DataRequest]
  def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  )(
    implicit f: Async[F]
  ): F[Response[F]]
}

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

trait DataApplicationSharedContextualOps[F[
  _
], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState, Context] {

  def validateFee(gsOrdinal: SnapshotOrdinal)(dataUpdate: Signed[D], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
    implicit context: Context,
    A: Applicative[F]
  ): F[DataApplicationValidationErrorOr[Unit]] = ().validNec[DataApplicationValidationError].pure[F]

  def routes(implicit context: Context): HttpRoutes[F]

  def routesPrefix: ExternalUrlPrefix = "/data-application"
}

trait DataApplicationL0ContextualOps[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationSharedContextualOps[F, D, DON, DOF, L0NodeContext[F]] {

  def validateData(state: DataState[DON, DOF], updates: NonEmptyList[Signed[D]])(
    implicit context: L0NodeContext[F]
  ): F[DataApplicationValidationErrorOr[Unit]]

  def combine(state: DataState[DON, DOF], updates: List[Signed[D]])(implicit context: L0NodeContext[F]): F[DataState[DON, DOF]]

  def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DOF)]

  def setCalculatedState(ordinal: SnapshotOrdinal, state: DOF)(implicit context: L0NodeContext[F]): F[Boolean]

  def hashCalculatedState(state: DOF)(implicit context: L0NodeContext[F]): F[Hash]

  def extractFees(ds: Seq[Signed[D]])(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
    A.pure(Seq.empty)
}

trait DataApplicationL1ContextualOps[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationSharedContextualOps[F, D, DON, DOF, L1NodeContext[F]] {

  def validateUpdate(update: D)(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]]

  def estimateFee(gsOrdinal: SnapshotOrdinal)(update: D)(
    implicit context: L1NodeContext[F],
    A: Applicative[F]
  ): F[EstimatedFee] = EstimatedFee.empty.pure[F]
}

trait DataApplicationL0Service[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationService[F, D, DON, DOF]
    with DataApplicationL0ContextualOps[F, D, DON, DOF] {
  def genesis: DataState[DON, DOF]

  def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot])(implicit A: Applicative[F]): F[Unit] = A.unit

  def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo)(implicit A: Applicative[F]): F[Unit] =
    A.unit

  def getTokenUnlocks(
    state: DataState[DataOnChainState, DataCalculatedState]
  )(implicit context: L0NodeContext[F], async: Async[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]] = for {
    maybeLastSynchronizedGlobalSnapshot <- context.getLastSynchronizedGlobalSnapshot
    maybeLastCurrencySnapshotCombined <- context.getLastCurrencySnapshotCombined

    lastSynchronizedGlobalSnapshot <- OptionT
      .fromOption(maybeLastSynchronizedGlobalSnapshot)
      .getOrRaise(new IllegalStateException("lastSynchronizedGlobalSnapshot unavailable"))

    (_, lastCurrencySnapshotState) <- OptionT
      .fromOption(maybeLastCurrencySnapshotCombined)
      .getOrRaise(new IllegalStateException("lastCurrencySnapshot unavailable"))

    lastGlobalEpochProgress = lastSynchronizedGlobalSnapshot.epochProgress
    expiredTokenLocks = lastCurrencySnapshotState.activeTokenLocks.collect { activeTokenLocks =>
      activeTokenLocks.values.flatten.toList
        .filter(_.unlockEpoch <= lastGlobalEpochProgress)
    }.getOrElse(List.empty)

    result <- expiredTokenLocks.traverse { tokenLock =>
      TokenLockReference.of(tokenLock).map { tokenLockReference =>
        TokenUnlock(
          tokenLockReference,
          tokenLock.amount,
          tokenLock.currencyId,
          tokenLock.source
        )
      }
    }
  } yield result.toSortedSet
}

trait DataApplicationL1Service[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState]
    extends DataApplicationService[F, D, DON, DOF]
    with DataApplicationL1ContextualOps[F, D, DON, DOF] {

  def postDataTransactionsRequestDecoder(req: Request[F])(implicit f: Async[F]): F[DataRequest] = {
    implicit val dataUpdateDecoder: Decoder[D] = dataDecoder
    implicit val feeTransactionDecoder: Decoder[FeeTransaction] = deriveDecoder[FeeTransaction]
    implicit val requestDecoder: EntityDecoder[F, DataTransactions] = feeTransactionRequestDecoder

    req
      .as[DataTransactions]
      .map[DataRequest](DataTransactionsRequest)
      .handleErrorWith { _ =>
        implicit val signedEntityDecoder: EntityDecoder[F, Signed[D]] = signedDataEntityDecoder
        req.as[Signed[D]].map[DataRequest] { signedData =>
          SingleDataUpdateRequest(signedData.widen)
        }
      }
  }

  def postDataTransactionsResponseEncoder(
    dataRequest: DataRequest,
    validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
  )(
    implicit f: Async[F]
  ): F[Response[F]] =
    feeTransactionResponseEncoder(dataRequest, validationResult)
}

object BaseDataApplicationL0ContextualOps {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL0ContextualOps[F, D, DON, DOF]
  )(
    implicit d: ClassTag[D],
    don: ClassTag[DON],
    dof: ClassTag[DOF],
    monadThrow: MonadThrow[F]
  ): BaseDataApplicationL0ContextualOps[F] =
    new BaseDataApplicationL0ContextualOps[F] {
      def allKnown(updates: List[Signed[DataUpdate]]): Boolean =
        updates.map(_.value).forall { case _: D => true; case _ => false }

      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        (state.onChain, state.calculated, state.sharedArtifacts) match {
          case (on: DON, off: DOF, sharedArtifacts: SortedSet[SharedArtifact]) if allKnown(updates.toList) =>
            service.validateData(DataState(on, off, sharedArtifacts), updates.asInstanceOf[NonEmptyList[Signed[D]]])
          case _ => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataState.Base] =
        (state.onChain, state.calculated, state.sharedArtifacts) match {
          case (on: DON, off: DOF, sharedArtifacts: SortedSet[SharedArtifact]) if allKnown(updates) =>
            service.combine(DataState(on, off, sharedArtifacts), updates.asInstanceOf[List[Signed[D]]]).map(_.asBase)
          case (_, _, _) => UnexpectedInput.raiseError[F, DataState.Base]
        }

      def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)] =
        service.getCalculatedState.widen[(SnapshotOrdinal, DataCalculatedState)]

      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean] =
        state match {
          case s: DOF => service.setCalculatedState(ordinal, s)
          case _      => UnexpectedInput.raiseError[F, Boolean]
        }

      def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
        state match {
          case s: DOF => service.hashCalculatedState(s)
          case _      => UnexpectedInput.raiseError[F, Hash]
        }

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
        implicit context: L0NodeContext[F],
        A: Applicative[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        service.validateFee(gsOrdinal)(dataUpdate.asInstanceOf[Signed[D]], maybeFeeTransaction)

      override def extractFees(
        ds: Seq[Signed[DataUpdate]]
      )(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
        service.extractFees(ds.asInstanceOf[Seq[Signed[D]]])

      def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] = service.routes

      def routesPrefix: ExternalUrlPrefix = service.routesPrefix
    }
}

object BaseDataApplicationL1ContextualOps {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL1ContextualOps[F, D, DON, DOF]
  )(
    implicit d: ClassTag[D],
    don: ClassTag[DON],
    dof: ClassTag[DOF],
    monadThrow: MonadThrow[F]
  ): BaseDataApplicationL1ContextualOps[F] =
    new BaseDataApplicationL1ContextualOps[F] {
      def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[F]): F[DataApplicationValidationErrorOr[Unit]] =
        update match {
          case d: D => service.validateUpdate(d)
          case _    => Validated.invalidNec[DataApplicationValidationError, Unit](Noop).pure[F]
        }

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
        implicit context: L1NodeContext[F],
        A: Applicative[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        service.validateFee(gsOrdinal)(dataUpdate.asInstanceOf[Signed[D]], maybeFeeTransaction)

      override def estimateFee(gsOrdinal: SnapshotOrdinal)(update: DataUpdate)(
        implicit context: L1NodeContext[F],
        A: Applicative[F]
      ): F[EstimatedFee] =
        service.estimateFee(gsOrdinal)(update.asInstanceOf[D])

      def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = service.routes

      def routesPrefix: ExternalUrlPrefix = service.routesPrefix
    }
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

object BaseDataApplicationL0Service {
  def apply[F[_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL0Service[F, D, DON, DOF]
  )(implicit d: ClassTag[D], don: ClassTag[DON], dof: ClassTag[DOF], monadThrow: MonadThrow[F]): BaseDataApplicationL0Service[F] = {

    val base = BaseDataApplicationService.apply[F, D, DON, DOF](service)

    val ctx = BaseDataApplicationL0ContextualOps[F, D, DON, DOF](service)

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

      def routes(implicit context: L0NodeContext[F]): HttpRoutes[F] = ctx.routes

      def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] = ctx.validateData(state, updates)

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
        implicit context: L0NodeContext[F],
        A: Applicative[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        ctx.validateFee(gsOrdinal)(dataUpdate, maybeFeeTransaction)

      override def extractFees(
        ds: Seq[Signed[DataUpdate]]
      )(implicit context: L0NodeContext[F], A: Applicative[F]): F[Seq[Signed[FeeTransaction]]] =
        ctx.extractFees(ds)

      def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L0NodeContext[F]
      ): F[DataState.Base] =
        ctx.combine(state, updates)

      def getCalculatedState(implicit context: L0NodeContext[F]): F[(SnapshotOrdinal, DataCalculatedState)] =
        ctx.getCalculatedState

      def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Boolean] =
        ctx.setCalculatedState(ordinal, state)

      def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
        ctx.hashCalculatedState(state)

      def calculatedStateDecoder: Decoder[DataCalculatedState] = base.calculatedStateDecoder

      def calculatedStateEncoder: Encoder[DataCalculatedState] = base.calculatedStateEncoder

      def routesPrefix: ExternalUrlPrefix = ctx.routesPrefix

      def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): F[Unit] = service.onSnapshotConsensusResult(snapshot)

      def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Unit] =
        service.onGlobalSnapshotPull(snapshot, context)

      def getTokenUnlocks(
        state: DataState[DataOnChainState, DataCalculatedState]
      )(implicit context: L0NodeContext[F], async: Async[F], hasher: Hasher[F]): F[SortedSet[TokenUnlock]] =
        service.getTokenUnlocks(state)
    }
  }
}

object BaseDataApplicationL1Service {
  def apply[F[+_], D <: DataUpdate, DON <: DataOnChainState, DOF <: DataCalculatedState](
    service: DataApplicationL1Service[F, D, DON, DOF]
  )(implicit d: ClassTag[D], don: ClassTag[DON], dof: ClassTag[DOF], monadThrow: MonadThrow[F]): BaseDataApplicationL1Service[F] = {

    val base = BaseDataApplicationService.apply[F, D, DON, DOF](service)

    val ctx = BaseDataApplicationL1ContextualOps[F, D, DON, DOF](service)

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

      def routes(implicit context: L1NodeContext[F]): HttpRoutes[F] = ctx.routes

      def validateUpdate(update: DataUpdate)(
        implicit context: L1NodeContext[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        ctx.validateUpdate(update)

      override def validateFee(
        gsOrdinal: SnapshotOrdinal
      )(dataUpdate: Signed[DataUpdate], maybeFeeTransaction: Option[Signed[FeeTransaction]])(
        implicit context: L1NodeContext[F],
        A: Applicative[F]
      ): F[DataApplicationValidationErrorOr[Unit]] =
        ctx.validateFee(gsOrdinal)(dataUpdate, maybeFeeTransaction)

      override def estimateFee(gsOrdinal: SnapshotOrdinal)(
        update: DataUpdate
      )(implicit context: L1NodeContext[F], A: Applicative[F]): F[EstimatedFee] =
        ctx.estimateFee(gsOrdinal)(update)

      override def postDataTransactionsRequestDecoder(req: Request[F])(implicit f: Async[F]): F[DataRequest] =
        service.postDataTransactionsRequestDecoder(req)

      override def postDataTransactionsResponseEncoder(
        dataRequest: DataRequest,
        validationResult: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]]
      )(
        implicit f: Async[F]
      ): F[Response[F]] =
        service.postDataTransactionsResponseEncoder(dataRequest, validationResult)

      def calculatedStateDecoder: Decoder[DataCalculatedState] = base.calculatedStateDecoder

      def calculatedStateEncoder: Encoder[DataCalculatedState] = base.calculatedStateEncoder

      def routesPrefix: ExternalUrlPrefix = ctx.routesPrefix
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
    dataTransactions: NonEmptyList[DataTransactions],
    dataTransactionsHashes: NonEmptyList[NonEmptyList[Hash]]
  ) extends Encodable[NonEmptyList[NonEmptyList[Hash]]] {
    override def toEncode: NonEmptyList[NonEmptyList[Hash]] = dataTransactionsHashes
    override def jsonEncoder: Encoder[NonEmptyList[NonEmptyList[Hash]]] = implicitly
  }

  object DataApplicationBlock {
    implicit def dataTransactionDecoder(implicit d: Decoder[DataUpdate]): Decoder[DataTransaction] = DataTransaction.decoder
    implicit def dataTransactionEncoder(implicit e: Encoder[DataUpdate]): Encoder[DataTransaction] = DataTransaction.encoder

    implicit def decoder(implicit d: Decoder[DataTransaction]): Decoder[DataApplicationBlock] = deriveDecoder
    implicit def encoder(implicit e: Encoder[DataTransaction]): Encoder[DataApplicationBlock] = deriveEncoder

    implicit def eqv: Eq[DataApplicationBlock] =
      Eq.and[DataApplicationBlock](
        Eq[RoundId].contramap(_.roundId),
        Eq[NonEmptyList[NonEmptyList[Hash]]].contramap(_.dataTransactionsHashes)
      )
  }

  object DataApplicationCustomRoutes {
    def publicRoutes[F[_]: Monad, Context](
      maybeDataApplication: Option[BaseDataApplicationService[F] with BaseDataApplicationSharedContextualOps[F, Context]]
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
  def getLastSynchronizedGlobalSnapshot: F[Option[Hashed[GlobalIncrementalSnapshot]]]
  def getLastSynchronizedGlobalSnapshotCombined: F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
  def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getCurrencySnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
  def securityProvider: SecurityProvider[F]
  def getCurrencyId: F[CurrencyId]
}

package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Applicative
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, OptionT}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.DataUpdate.getDataUpdates
import io.constellationnetwork.currency.dataApplication.FeeTransaction.getFeeTransactions
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.schema.currency.DataApplicationPart
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.currency.validations.DataTransactionsValidator.validateDataTransactionsL0
import io.constellationnetwork.ext.cats.syntax.partialPrevious.catsSyntaxPartialPrevious
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.block.processing.{BlockNotAcceptedReason, DataBlockNotAccepted}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait DataApplicationSnapshotAcceptanceManager[F[_]] {
  def accept(
    maybeLastDataApplication: Option[DataApplicationPart],
    dataBlocks: List[Signed[DataApplicationBlock]],
    lastOrdinal: SnapshotOrdinal,
    currentOrdinal: SnapshotOrdinal,
    lastGlobalSyncView: Option[GlobalSyncView]
  ): F[Option[DataApplicationAcceptanceResult]]

  def consumeSignedMajorityArtifact(
    maybeLastDataApplication: Option[DataApplicationPart],
    artifact: Signed[CurrencySnapshotArtifact],
    lastGlobalSyncView: Option[GlobalSyncView]
  ): F[Unit]
}

case class DataApplicationAcceptanceResult(
  dataApplicationPart: DataApplicationPart,
  calculatedState: DataCalculatedState,
  feeTransactions: Seq[Signed[FeeTransaction]] = Seq.empty,
  sharedArtifacts: SortedSet[SharedArtifact] = SortedSet.empty[SharedArtifact],
  notAccepted: List[(Signed[DataApplicationBlock], BlockNotAcceptedReason)] = List.empty
)

object DataApplicationSnapshotAcceptanceManager {

  case class CalculatedStateDoesNotMatchOrdinal(calculatedStateOrdinal: SnapshotOrdinal, expectedOrdinal: SnapshotOrdinal)
      extends NoStackTrace {
    override def getMessage: String =
      s"Calculated state ordinal=${calculatedStateOrdinal.show} does not match expected ordinal=${expectedOrdinal.show}"
  }

  case class CalculatedStateHashDoesNotMatchMajority(current: Hash, expected: Hash) extends NoStackTrace {
    override def getMessage: String =
      s"Calculated state hash=${current.show} does not match expected hash=${expected.show} from majority"
  }

  def make[F[_]: Async: Hasher: JsonSerializer: SecurityProvider](
    service: BaseDataApplicationL0Service[F],
    nodeContext: L0NodeContext[F],
    calculatedStateStorage: CalculatedStateLocalFileSystemStorage[F],
    fixingDataApplicationFeeValidation: SnapshotOrdinal
  ): DataApplicationSnapshotAcceptanceManager[F] = new DataApplicationSnapshotAcceptanceManager[F] {
    private val logger = Slf4jLogger.getLogger

    // Same arithmetic and same iteration order as CurrencySnapshotAcceptanceManager.applyFeeTransactions, which
    // folds over a SortedSet, so both layers reach the same verdict on the same transactions. The one difference
    // is deliberate: acceptance drops the individual transaction it cannot apply, while here the whole block is
    // rejected, because letting the block through would apply its data updates without charging for them.
    private def applyFeeTransactions(
      balances: SortedMap[Address, Balance],
      feeTransactions: List[Signed[FeeTransaction]]
    ): Either[Throwable, SortedMap[Address, Balance]] =
      SortedSet.from(feeTransactions).foldLeft(balances.asRight[Throwable]) { (acc, tx) =>
        acc.flatMap { current =>
          (for {
            debitedSource <- current.getOrElse(tx.source, Balance.empty).minus(tx.amount)
            withSource = current.updated(tx.source, debitedSource)
            creditedDestination <- withSource.getOrElse(tx.destination, Balance.empty).plus(tx.amount)
          } yield withSource.updated(tx.destination, creditedDestination)).leftMap { e =>
            val details = s"source: ${tx.source.show}, destination: ${tx.destination.show}, amount: ${tx.amount.value.value}"
            new ArithmeticException(s"Cannot apply fee transaction: $e, $details")
          }
        }
      }

    def expectCalculatedStateOrdinal(
      expectedOrdinal: SnapshotOrdinal
    )(calculatedState: (SnapshotOrdinal, DataCalculatedState)): F[DataCalculatedState] =
      calculatedState match {
        case (ordinal, state) =>
          CalculatedStateDoesNotMatchOrdinal(ordinal, expectedOrdinal)
            .raiseError[F, Unit]
            .whenA(ordinal =!= expectedOrdinal)
            .as(state)
      }

    def expectCalculatedStateHash(
      expectedHash: Hash
    )(calculatedState: DataCalculatedState)(implicit context: L0NodeContext[F]): F[DataCalculatedState] =
      service.hashCalculatedState(calculatedState).flatMap { hash =>
        CalculatedStateHashDoesNotMatchMajority(hash, expectedHash)
          .raiseError[F, Unit]
          .whenA(hash =!= expectedHash)
          .as(calculatedState)
      }

    def consumeSignedMajorityArtifact(
      maybeLastDataApplication: Option[DataApplicationPart],
      artifact: Signed[CurrencySnapshotArtifact],
      lastGlobalSyncView: Option[GlobalSyncView]
    ): F[Unit] = {
      implicit val context: L0NodeContext[F] = nodeContext

      OptionT
        .fromOption(artifact.dataApplication)
        .flatMap { da =>
          OptionT
            .liftF(da.blocks.traverse(service.deserializeBlock).map(_.flatMap(_.toOption)))
            .flatMapF { dataBlocks =>
              artifact.ordinal.partialPrevious.flatTraverse(lastOrdinal =>
                accept(maybeLastDataApplication, dataBlocks, lastOrdinal, artifact.ordinal, lastGlobalSyncView)
              )
            }
            .map(_.calculatedState)
            .semiflatMap(expectCalculatedStateHash(da.calculatedStateProof))
            .semiflatTap(service.setCalculatedState(artifact.ordinal, _))
            .semiflatTap(calculatedStateStorage.write(artifact.ordinal, _)(service.serializeCalculatedState))
        }
        .value
        .void
    }

    def accept(
      maybeLastDataApplication: Option[DataApplicationPart],
      dataBlocks: List[Signed[DataApplicationBlock]],
      lastOrdinal: SnapshotOrdinal,
      currentOrdinal: SnapshotOrdinal,
      lastGlobalSyncView: Option[GlobalSyncView]
    ): F[Option[DataApplicationAcceptanceResult]] = {
      implicit val context: L0NodeContext[F] = nodeContext

      // Snapshot acceptance is re-executed verbatim whenever a signed snapshot is replayed -- rejoin, download
      // and consensus validation all recreate the artifact and compare it to the recorded one. The gate has to
      // read a value carried by the history being replayed, not the node's live view of the global chain, or a
      // node replaying an old ordinal today evaluates it against today's head and diverges. globalSyncView is a
      // field of the previous currency snapshot, so it is identical on every node and at every replay. It is
      // None on snapshots older than tessellation-3-migration, which are exactly the ones that must replay
      // under the legacy rules.
      val validateEveryFeeTransaction =
        lastGlobalSyncView.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue) >= fixingDataApplicationFeeValidation

      val newDataState: OptionT[F, DataApplicationAcceptanceResult] = for {
        lastOnChainState <- OptionT.fromOption(maybeLastDataApplication.map(_.onChainState)).flatMapF { lastDataApplication =>
          service
            .deserializeState(lastDataApplication)
            .flatTap {
              case Left(err) => logger.warn(err)("Cannot deserialize custom state")
              case Right(_)  => Applicative[F].unit
            }
            .map(_.toOption)
            .handleErrorWith(err =>
              logger.error(err)(s"Unhandled exception during deserialization data application, fallback to empty state").as(none)
            )
        }
        balances <- OptionT.liftF {
          context.getLastCurrencySnapshotCombined.flatMap { snapshot =>
            OptionT
              .fromOption(snapshot)
              .map { case (_, snapshotInfo) => snapshotInfo.balances }
              .getOrRaise(new IllegalStateException("Last currency snapshot unavailable"))
          }
        }

        lastCalculatedState <- OptionT.liftF(
          service.getCalculatedState
            .flatMap(expectCalculatedStateOrdinal(lastOrdinal))
        )

        dataState = DataState(lastOnChainState, lastCalculatedState)
        initialResult = (
          dataState,
          balances,
          List.empty[Signed[FeeTransaction]],
          List.empty[Signed[DataApplicationBlock]],
          List.empty[(Signed[DataApplicationBlock], DataBlockNotAccepted)]
        )

        processingResult <- OptionT.liftF {
          val blocksToProcess = NonEmptyList
            .fromList(dataBlocks.sortBy(_.roundId).distinctBy(_.value.roundId))
            .map(_.toList)
            .getOrElse(Nil)

          if (blocksToProcess.isEmpty) {
            val (oldState, oldBalances, oldFeeTxns, oldAcceptedBlocks, oldRejectedBlocks) = initialResult
            // No blocks to process - call combine with empty updates
            service.combine(oldState, List.empty).map { newState =>
              (newState, oldBalances, oldFeeTxns, oldAcceptedBlocks, oldRejectedBlocks)
            }
          } else {
            logger.info(s"Starting to process blocks: ${blocksToProcess.map(_.roundId)}") >>
              blocksToProcess.foldLeftM(initialResult) {
                case ((currentState, currentBalances, accFeeTransactions, accAcceptedBlocks, accNotAcceptedBlocks), dataBlock) =>
                  val dataTransactions = dataBlock.value.dataTransactions

                  // At or above the activation ordinal the validator reads currentBalances rather than the
                  // snapshot balances: fee transactions accepted by an earlier block in this same snapshot
                  // have already been debited, so a later block cannot spend a balance that is no longer
                  // there. Below it, currentBalances is never advanced and equals the snapshot balances, so
                  // the validator sees exactly what it saw before.
                  // The traverse validates each envelope on its own, so the per-source sum inside
                  // validateAllFeeTransactions covers one envelope rather than the whole block: two envelopes
                  // from the same source can each clear here while jointly exceeding its balance. The
                  // applyFeeTransactions fold below runs over the block's fee transactions with checked
                  // arithmetic and rejects the block in that case.
                  val validationBalances = if (validateEveryFeeTransaction) currentBalances else balances

                  val dataTransactionsValidations =
                    dataTransactions
                      .traverse(
                        validateDataTransactionsL0(
                          _,
                          service,
                          validationBalances,
                          currentOrdinal,
                          dataState,
                          validateEveryFeeTransaction
                        )
                      )
                      .map(_.reduce)

                  dataTransactionsValidations.flatTap { validation =>
                    if (validation.isValid)
                      logger.info(s"Validating block with roundId=${dataBlock.value.roundId}")
                    else
                      logger.info(s"Block ${dataBlock.value.roundId} is invalid: ${validation.fold(_.toList.mkString(", "), _ => "")}")
                  }.flatMap {
                    case Valid(_) =>
                      val dataTransactionsAsList = dataTransactions.toList
                      val dataUpdates = getDataUpdates(dataTransactionsAsList)
                      val feeTransactions = getFeeTransactions(dataTransactionsAsList)

                      // Below the activation ordinal the block is never rejected for arithmetic and the
                      // running balance is left untouched, which is the pre-fix behaviour.
                      val feeApplication =
                        if (validateEveryFeeTransaction) applyFeeTransactions(currentBalances, feeTransactions)
                        else currentBalances.asRight[Throwable]

                      feeApplication match {
                        case Left(err) =>
                          logger
                            .warn(s"Block ${dataBlock.value.roundId} not accepted: ${err.getMessage}")
                            .as(
                              (
                                currentState,
                                currentBalances,
                                accFeeTransactions,
                                accAcceptedBlocks,
                                accNotAcceptedBlocks :+ (dataBlock, DataBlockNotAccepted(err.getMessage))
                              )
                            )

                        case Right(updatedBalances) =>
                          for {
                            _ <- logger.info(s"Block ${dataBlock.value.roundId} is valid")
                            result <- service.combine(currentState, dataUpdates).map { newState =>
                              (
                                newState,
                                updatedBalances,
                                accFeeTransactions ++ feeTransactions,
                                accAcceptedBlocks :+ dataBlock,
                                accNotAcceptedBlocks
                              )
                            }
                            _ <- logger.info(s"SharedArtifacts produced: ${result._1.sharedArtifacts}")
                          } yield result
                      }

                    case Invalid(err) =>
                      Async[F].pure(
                        (
                          currentState,
                          currentBalances,
                          accFeeTransactions,
                          accAcceptedBlocks,
                          accNotAcceptedBlocks :+ (dataBlock, DataBlockNotAccepted(err.toString))
                        )
                      )
                  }.handleErrorWith { err =>
                    logger.error(err)(s"Exception during block validation for roundId=${dataBlock.value.roundId}") >>
                      Async[F].pure(
                        (
                          currentState,
                          currentBalances,
                          accFeeTransactions,
                          accAcceptedBlocks,
                          accNotAcceptedBlocks :+ (dataBlock, DataBlockNotAccepted(err.getMessage))
                        )
                      )
                  }
              }
          }
        }

        (newDataState, _, validatedFeeTransactions, validatedBlocks, notAcceptedBlocks) = processingResult

        serializedOnChainState <- OptionT.liftF(
          service.serializeState(newDataState.onChain)
        )

        serializedBlocks <- OptionT.liftF(
          validatedBlocks.traverse(service.serializeBlock)
        )

        calculatedStateProof <- OptionT.liftF(
          service.hashCalculatedState(newDataState.calculated)
        )

        tokenUnlocks <- OptionT.liftF(
          service
            .getTokenUnlocks(newDataState)
            .handleErrorWith(e => logger.error(e)("An error occurred when extracting tokenUnlocks").as(SortedSet.empty[TokenUnlock]))
        )

        sharedArtifacts = newDataState.sharedArtifacts ++ tokenUnlocks
      } yield
        DataApplicationAcceptanceResult(
          DataApplicationPart(serializedOnChainState, serializedBlocks, calculatedStateProof),
          newDataState.calculated,
          validatedFeeTransactions,
          sharedArtifacts,
          notAcceptedBlocks
        )

      newDataState.value.handleErrorWith { err =>
        logger.error(err)("Unhandled exception during calculating new data application state, fallback to last data application") >>
          service.getCalculatedState.map { lastCalculatedState =>
            maybeLastDataApplication.map(
              DataApplicationAcceptanceResult(
                _,
                lastCalculatedState._2,
                notAccepted = dataBlocks.map(signedBlock => (signedBlock, DataBlockNotAccepted(err.getMessage)))
              )
            )
          }
      }
    }
  }
}

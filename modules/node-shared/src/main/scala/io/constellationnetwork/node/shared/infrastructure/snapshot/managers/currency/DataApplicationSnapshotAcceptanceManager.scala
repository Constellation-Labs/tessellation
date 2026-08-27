package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

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
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait DataApplicationSnapshotAcceptanceManager[F[_]] {
  def accept(
    maybeLastDataApplication: Option[DataApplicationPart],
    dataBlocks: List[Signed[DataApplicationBlock]],
    lastOrdinal: SnapshotOrdinal,
    currentOrdinal: SnapshotOrdinal,
    parentGlobalSnapshotOrdinal: SnapshotOrdinal
  ): F[Option[DataApplicationAcceptanceResult]]

  def consumeSignedMajorityArtifact(
    maybeLastDataApplication: Option[DataApplicationPart],
    artifact: Signed[CurrencySnapshotArtifact],
    parentGlobalSnapshotOrdinal: SnapshotOrdinal
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
    feeTransactionSecurityActivationOrdinal: SnapshotOrdinal,
    fixingDataApplicationFeeValidation: SnapshotOrdinal
  ): DataApplicationSnapshotAcceptanceManager[F] = new DataApplicationSnapshotAcceptanceManager[F] {
    private val logger = Slf4jLogger.getLogger

    // Keep the same deterministic SortedSet iteration and checked arithmetic as currency snapshot acceptance.
    // A fee transaction that cannot be applied rejects its whole data block so data updates are never accepted
    // without collecting the corresponding fee.
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
      parentGlobalSnapshotOrdinal: SnapshotOrdinal
    ): F[Unit] = {
      implicit val context: L0NodeContext[F] = nodeContext

      artifact.dataApplication.traverse_ { da =>
        service.getCalculatedState.flatMap {
          case (ordinal, state) if ordinal === artifact.ordinal =>
            // At-least-once finalization may resume after the service state was installed
            // but before snapshot persistence completed. Verify the exact certified hash and
            // repair the idempotent filesystem write without recalculating N from N.
            expectCalculatedStateHash(da.calculatedStateProof)(state) >>
              calculatedStateStorage.write(artifact.ordinal, state)(service.serializeCalculatedState)

          case (ordinal, _) if ordinal.value.value > artifact.ordinal.value.value =>
            new IllegalStateException(
              s"Calculated state is ahead of replayed artifact: calculated=$ordinal artifact=${artifact.ordinal}"
            ).raiseError[F, Unit]

          case _ =>
            OptionT
              .liftF(da.blocks.traverse(service.deserializeBlock).map(_.flatMap(_.toOption)))
              .flatMapF { dataBlocks =>
                artifact.ordinal.partialPrevious.flatTraverse(lastOrdinal =>
                  accept(maybeLastDataApplication, dataBlocks, lastOrdinal, artifact.ordinal, parentGlobalSnapshotOrdinal)
                )
              }
              .map(_.calculatedState)
              .semiflatMap(expectCalculatedStateHash(da.calculatedStateProof))
              .semiflatTap(service.setCalculatedState(artifact.ordinal, _))
              .semiflatTap(calculatedStateStorage.write(artifact.ordinal, _)(service.serializeCalculatedState))
              .value
              .void
        }
      }
    }

    def accept(
      maybeLastDataApplication: Option[DataApplicationPart],
      dataBlocks: List[Signed[DataApplicationBlock]],
      lastOrdinal: SnapshotOrdinal,
      currentOrdinal: SnapshotOrdinal,
      parentGlobalSnapshotOrdinal: SnapshotOrdinal
    ): F[Option[DataApplicationAcceptanceResult]] = {
      implicit val context: L0NodeContext[F] = nodeContext

      // The previous currency snapshot carries this global-sync ordinal, making the activation decision stable
      // during replay. Historical snapshots below the gate retain the exact legacy acceptance behavior.
      val validateEveryFeeTransaction = parentGlobalSnapshotOrdinal >= fixingDataApplicationFeeValidation

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

                  // Once active, earlier fee transactions in this snapshot must be reflected when validating
                  // later blocks. Below the gate currentBalances remains equal to the historical snapshot balance.
                  val validationBalances = if (validateEveryFeeTransaction) currentBalances else balances

                  val dataTransactionsValidations =
                    dataTransactions
                      .traverse(
                        validateDataTransactionsL0(
                          _,
                          service,
                          validationBalances,
                          currentOrdinal,
                          parentGlobalSnapshotOrdinal,
                          dataState,
                          feeTransactionSecurityActivationOrdinal,
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

        updateHashes <- OptionT.liftF(
          service.hashDataUpdate match {
            case Some(hashFn) if validatedBlocks.nonEmpty =>
              validatedBlocks.flatMap { block =>
                getDataUpdates(block.value.dataTransactions.toList)
              }.traverse { signedUpdate =>
                hashFn(signedUpdate.value)
              }.map(hashes => Some(hashes.toSortedSet))
            case _ =>
              Async[F].pure(None: Option[SortedSet[Hash]])
          }
        )
      } yield
        DataApplicationAcceptanceResult(
          DataApplicationPart(serializedOnChainState, serializedBlocks, calculatedStateProof, updateHashes),
          newDataState.calculated,
          validatedFeeTransactions,
          sharedArtifacts,
          notAcceptedBlocks
        )

      newDataState.value.handleErrorWith { err =>
        logger.error(err)("Unhandled exception during calculating new data application state, fallback to last data application") >>
          service.getCalculatedState.map { lastCalculatedState =>
            maybeLastDataApplication.map(part =>
              DataApplicationAcceptanceResult(
                part,
                lastCalculatedState._2,
                notAccepted = dataBlocks.map(signedBlock => (signedBlock, DataBlockNotAccepted(err.getMessage)))
              )
            )
          }
      }
    }
  }
}

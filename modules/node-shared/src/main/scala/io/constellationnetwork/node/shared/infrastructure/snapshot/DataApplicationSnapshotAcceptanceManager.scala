package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Applicative
import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, OptionT}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.DataUpdate.getDataUpdates
import io.constellationnetwork.currency.dataApplication.FeeTransaction.getFeeTransactions
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.schema.currency.DataApplicationPart
import io.constellationnetwork.currency.validations.DataTransactionsValidator.validateDataTransactionsL0
import io.constellationnetwork.ext.cats.syntax.partialPrevious.catsSyntaxPartialPrevious
import io.constellationnetwork.node.shared.domain.block.processing.{BlockNotAcceptedReason, DataBlockNotAccepted}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait DataApplicationSnapshotAcceptanceManager[F[_]] {
  def accept(
    maybeLastDataApplication: Option[DataApplicationPart],
    dataBlocks: List[Signed[DataApplicationBlock]],
    lastOrdinal: SnapshotOrdinal,
    currentOrdinal: SnapshotOrdinal
  ): F[Option[DataApplicationAcceptanceResult]]

  def consumeSignedMajorityArtifact(
    maybeLastDataApplication: Option[DataApplicationPart],
    artifact: Signed[CurrencySnapshotArtifact]
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

  def make[F[_]: Async: Hasher: SecurityProvider](
    service: BaseDataApplicationL0Service[F],
    nodeContext: L0NodeContext[F],
    calculatedStateStorage: CalculatedStateLocalFileSystemStorage[F]
  ): DataApplicationSnapshotAcceptanceManager[F] = new DataApplicationSnapshotAcceptanceManager[F] {
    private val logger = Slf4jLogger.getLogger

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
      artifact: Signed[CurrencySnapshotArtifact]
    ): F[Unit] = {
      implicit val context: L0NodeContext[F] = nodeContext

      OptionT
        .fromOption(artifact.dataApplication)
        .flatMap { da =>
          OptionT
            .liftF(da.blocks.traverse(service.deserializeBlock).map(_.flatMap(_.toOption)))
            .flatMapF { dataBlocks =>
              artifact.ordinal.partialPrevious.flatTraverse(lastOrdinal =>
                accept(maybeLastDataApplication, dataBlocks, lastOrdinal, artifact.ordinal)
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
      currentOrdinal: SnapshotOrdinal
    ): F[Option[DataApplicationAcceptanceResult]] = {
      implicit val context: L0NodeContext[F] = nodeContext

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
          List.empty[Signed[FeeTransaction]],
          List.empty[Signed[DataApplicationBlock]],
          List.empty[(Signed[DataApplicationBlock], DataBlockNotAccepted)]
        )

        processingResult <- OptionT.liftF {
          val blocksToProcess = NonEmptyList
            .fromList(dataBlocks.distinctBy(_.value.roundId))
            .map(_.toList)
            .getOrElse(Nil)

          if (blocksToProcess.isEmpty) {
            val (oldState, oldFeeTxns, oldAcceptedBlocks, oldRejectedBlocks) = initialResult
            // No blocks to process - call combine with empty updates
            service.combine(oldState, List.empty).map { newState =>
              (newState, oldFeeTxns, oldAcceptedBlocks, oldRejectedBlocks)
            }
          } else {
            blocksToProcess.foldLeftM(initialResult) {
              case ((currentState, accFeeTransactions, accAcceptedBlocks, accNotAcceptedBlocks), dataBlock) =>
                val dataTransactions = dataBlock.value.dataTransactions

                val dataTransactionsValidations =
                  dataTransactions.traverse(validateDataTransactionsL0(_, service, balances, currentOrdinal, dataState)).map(_.reduce)

                dataTransactionsValidations.flatTap { validation =>
                  if (validation.isValid)
                    logger.debug(s"Validating block with roundId=${dataBlock.value.roundId}")
                  else
                    logger.debug(s"Block ${dataBlock.value.roundId} is invalid: ${validation.fold(_.toList.mkString(", "), _ => "")}")
                }.flatMap {
                  case Valid(_) =>
                    val dataTransactionsAsList = dataTransactions.toList
                    val dataUpdates = getDataUpdates(dataTransactionsAsList)
                    val feeTransactions = getFeeTransactions(dataTransactionsAsList)

                    logger.debug(s"Block ${dataBlock.value.roundId} is valid") >>
                      service.combine(currentState, dataUpdates).map { newState =>
                        (
                          newState,
                          accFeeTransactions ++ feeTransactions,
                          accAcceptedBlocks :+ dataBlock,
                          accNotAcceptedBlocks
                        )
                      }
                  case Invalid(err) =>
                    Async[F].pure(
                      (
                        currentState,
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
                        accFeeTransactions,
                        accAcceptedBlocks,
                        accNotAcceptedBlocks :+ (dataBlock, DataBlockNotAccepted(err.getMessage))
                      )
                    )
                }
            }
          }
        }

        (newDataState, validatedFeeTransactions, validatedBlocks, notAcceptedBlocks) = processingResult

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

package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

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
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.block.processing.{BlockNotAcceptedReason, DataBlockNotAccepted}
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
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
    feeTransactionSecurityActivationOrdinal: SnapshotOrdinal
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
      artifact: Signed[CurrencySnapshotArtifact],
      parentGlobalSnapshotOrdinal: SnapshotOrdinal
    ): F[Unit] = {
      implicit val context: L0NodeContext[F] = nodeContext

      OptionT
        .fromOption(artifact.dataApplication)
        .flatMap { da =>
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
        }
        .value
        .void
    }

    def accept(
      maybeLastDataApplication: Option[DataApplicationPart],
      dataBlocks: List[Signed[DataApplicationBlock]],
      lastOrdinal: SnapshotOrdinal,
      currentOrdinal: SnapshotOrdinal,
      parentGlobalSnapshotOrdinal: SnapshotOrdinal
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

        processingResult <- OptionT.liftF {
          type RejectedBlock = (Signed[DataApplicationBlock], DataBlockNotAccepted)
          type ProcessingResult = (DataState.Base, List[Signed[FeeTransaction]], List[Signed[DataApplicationBlock]], List[RejectedBlock])

          val blocksToProcess = NonEmptyList
            .fromList(dataBlocks.sortBy(_.roundId).distinctBy(_.value.roundId))
            .map(_.toList)
            .getOrElse(Nil)

          def validationFailure(dataBlock: Signed[DataApplicationBlock], message: String): RejectedBlock =
            dataBlock -> DataBlockNotAccepted(message)

          def validateCandidates: F[(List[Signed[DataApplicationBlock]], List[RejectedBlock])] =
            blocksToProcess.foldLeftM((List.empty[Signed[DataApplicationBlock]], List.empty[RejectedBlock])) {
              case ((validBlocks, rejectedBlocks), dataBlock) =>
                val dataTransactions = dataBlock.value.dataTransactions
                val validation = dataTransactions
                  .traverse(
                    validateDataTransactionsL0(
                      _,
                      service,
                      balances,
                      currentOrdinal,
                      parentGlobalSnapshotOrdinal,
                      dataState,
                      feeTransactionSecurityActivationOrdinal
                    )
                  )
                  .map(_.reduce)

                validation.flatTap {
                  case Valid(_) => logger.info(s"Validating block with roundId=${dataBlock.value.roundId}")
                  case Invalid(errors) =>
                    logger.info(s"Block ${dataBlock.value.roundId} is invalid: ${errors.toList.mkString(", ")}")
                }.map {
                  case Valid(_)      => (validBlocks :+ dataBlock, rejectedBlocks)
                  case Invalid(errs) => (validBlocks, rejectedBlocks :+ validationFailure(dataBlock, errs.toString))
                }.handleErrorWith { err =>
                  val message = Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
                  logger.error(err)(s"Exception during block validation for roundId=${dataBlock.value.roundId}") >>
                    (validBlocks, rejectedBlocks :+ validationFailure(dataBlock, message)).pure[F]
                }
            }

          // `combine` can reject a validation-passing block by raising. When that happens, remove the
          // failed block and recompute from the original state with the smaller fee map. The candidate
          // set strictly shrinks, so the final successful pass exposes exactly the fee transactions from
          // the blocks that are stored. Rollback replay rebuilds its map from those same stored blocks.
          def combineUntilStable(
            candidates: List[Signed[DataApplicationBlock]],
            rejected: List[RejectedBlock]
          ): F[ProcessingResult] = {
            val candidateFeeTransactions = candidates.flatMap(block => getFeeTransactions(block.value.dataTransactions.toList))

            FeeTransaction.buildFeeMap[F](candidateFeeTransactions, logger).flatMap { feeMap =>
              val feeContext = L0NodeContextOps.withSnapshotFeeTransactions(nodeContext, feeMap)

              if (candidates.isEmpty)
                service.combine(dataState, List.empty)(feeContext).map { state =>
                  (state, List.empty, List.empty, rejected)
                }
              else
                logger.info(s"Starting to process ${candidates.size} blocks with ${feeMap.size} fee transactions") >>
                  candidates
                    .foldLeftM((dataState, List.empty[Signed[DataApplicationBlock]], List.empty[RejectedBlock])) {
                      case ((currentState, acceptedBlocks, failedBlocks), dataBlock) =>
                        val dataUpdates = getDataUpdates(dataBlock.value.dataTransactions.toList)

                        logger.info(s"Block ${dataBlock.value.roundId} is valid") >>
                          service.combine(currentState, dataUpdates)(feeContext).attempt.flatMap {
                            case Right(nextState) =>
                              logger.info(s"SharedArtifacts produced: ${nextState.sharedArtifacts}") >>
                                (nextState, acceptedBlocks :+ dataBlock, failedBlocks).pure[F]
                            case Left(err) =>
                              val message = Option(err.getMessage).getOrElse(err.getClass.getSimpleName)
                              logger.error(err)(s"Exception during block combination for roundId=${dataBlock.value.roundId}") >>
                                (currentState, acceptedBlocks, failedBlocks :+ validationFailure(dataBlock, message)).pure[F]
                          }
                    }
                    .flatMap {
                      case (state, acceptedBlocks, Nil) =>
                        (state, candidateFeeTransactions, acceptedBlocks, rejected).pure[F]
                      case (_, acceptedBlocks, failedBlocks) =>
                        logger.warn(
                          s"Recomputing data application state after ${failedBlocks.size} combine failure(s); " +
                            s"remainingBlocks=${acceptedBlocks.size}"
                        ) >> combineUntilStable(acceptedBlocks, rejected ++ failedBlocks)
                    }
            }
          }

          validateCandidates.flatMap {
            case (validBlocks, rejectedBlocks) =>
              combineUntilStable(validBlocks, rejectedBlocks)
          }
        }

        (acceptedDataState, validatedFeeTransactions, validatedBlocks, notAcceptedBlocks) = processingResult

        serializedOnChainState <- OptionT.liftF(
          service.serializeState(acceptedDataState.onChain)
        )

        serializedBlocks <- OptionT.liftF(
          validatedBlocks.traverse(service.serializeBlock)
        )

        calculatedStateProof <- OptionT.liftF(
          service.hashCalculatedState(acceptedDataState.calculated)
        )

        tokenUnlocks <- OptionT.liftF(
          service
            .getTokenUnlocks(acceptedDataState)
            .handleErrorWith(e => logger.error(e)("An error occurred when extracting tokenUnlocks").as(SortedSet.empty[TokenUnlock]))
        )

        sharedArtifacts = acceptedDataState.sharedArtifacts ++ tokenUnlocks

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
          acceptedDataState.calculated,
          validatedFeeTransactions,
          sharedArtifacts,
          notAcceptedBlocks.sortBy(_._1.roundId)
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

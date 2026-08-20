package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.Parallel
import cats.data._
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.syntax.validated.validatedSyntax
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.FieldsAddedOrdinals
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.{StateChannelValidationError, getFeeAddresses}
import io.constellationnetwork.node.shared.domain.statechannel._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencySnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.HistoricalGlobalSnapshotResolver.{
  MissingInsideRetainedWindow,
  OutsideRetainedWindow
}
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.ProcessedGlobalSnapshotHistory.ProcessedHistoryUnproven
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}

import eu.timepit.refined.auto._
import io.circe.Decoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotStateChannelEventsProcessor[F[_]] {
  type BinaryCurrencyPair = (Signed[StateChannelSnapshotBinary], Option[CurrencySnapshotWithState])
  type BalanceUpdate = SortedMap[Address, Balance]
  type MetagraphAcceptanceResult = (NonEmptyList[BinaryCurrencyPair], BalanceUpdate)
  type SelectedBranches = NonEmptyList[NonEmptyList[Signed[StateChannelSnapshotBinary]]]

  def process(
    snapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: List[StateChannelOutput],
    validationType: StateChannelValidationType,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[StateChannelAcceptanceResult]

  def processCurrencySnapshots(
    snapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(
    implicit hasher: Hasher[F]
  ): F[SortedMap[Address, MetagraphAcceptanceResult]]
}

object GlobalSnapshotStateChannelEventsProcessor {
  def make[F[_]: Async: JsonSerializer: Parallel: Metrics](
    stateChannelValidator: StateChannelValidator[F],
    stateChannelManager: GlobalSnapshotStateChannelAcceptanceManager[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    feeCalculator: FeeCalculator[F],
    mptStore: MptStore[F, GlobalStateKey],
    fieldsAddedOrdinals: FieldsAddedOrdinals,
    environment: AppEnvironment
  ) =
    new GlobalSnapshotStateChannelEventsProcessor[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotStateChannelEventsProcessor.getClass)

      private type CurrencyProcessingResult =
        (SortedMap[Address, MetagraphAcceptanceResult], Set[StateChannelOutput])

      // Ordinal-gated SC fee-balance source, resolved from config here rather than threaded as a bare
      // ordinal. Fail closed: an unset env defaults to MaxValue so the context-balance path stays OFF
      // (the gate never fires) rather than activating from genesis and diverging replay.
      private val scFeeBalanceFromContextOrdinal: SnapshotOrdinal =
        fieldsAddedOrdinals.scFeeBalanceFromContext.getOrElse(environment, SnapshotOrdinal.MaxValue)

      def deserialize[A: Decoder](binary: Signed[StateChannelSnapshotBinary]): F[Option[A]] =
        JsonSerializer[F].deserialize[A](binary.value.content).map(_.toOption)

      // Staking balance behavioral equivalence: for metagraphs with only a full snapshot
      // (Left case), the old fetchStakingBalance returned Balance.empty. With MptStore,
      // getCurrencySnapshotInfo returns a CurrencySnapshotInfo created via toCurrencySnapshotInfo
      // which sets lastMessages = None, so fetchStakingAddress returns None and we still get
      // Balance.empty — preserving the same behavior.
      def buildSnapshotFeesInfo(
        event: StateChannelOutput,
        allFeesAddresses: Map[Address, Set[Address]]
      ): F[SnapshotFeesInfo] =
        event.snapshotBinary.value.lastSnapshotHash match {
          case hash if hash == Hash.empty => SnapshotFeesInfo.empty.pure // genesis
          case _ =>
            deserialize[Signed[CurrencyIncrementalSnapshot]](event.snapshotBinary).flatMap {
              case None =>
                logger.warn(s"Could not get snapshot fee info after deserializing event $event, using empty snapshot fees") >>
                  SnapshotFeesInfo.empty.pure
              case Some(snapshot) =>
                for {
                  maybeCurrencyInfo <- mptStore.getCurrencySnapshotInfo(event.address)
                  stakingAddr = maybeCurrencyInfo.flatMap(fetchStakingAddress)
                  stakingBalance <- stakingAddr.fold(Balance.empty.pure[F]) { addr =>
                    mptStore.getBalance(addr).map(_.getOrElse(Balance.empty))
                  }
                  sortedMessagesDesc = snapshot.value.messages.map(_.toList.sortBy(-_.ordinal.value.value))
                  maybeOwnerAddress = sortedMessagesDesc.flatMap(_.find(_.messageType === MessageType.Owner)).map(_.address)
                  maybeStakingAddress = sortedMessagesDesc.flatMap(_.find(_.messageType === MessageType.Staking)).map(_.address)
                } yield SnapshotFeesInfo(allFeesAddresses, stakingBalance, maybeOwnerAddress, maybeStakingAddress)
            }
        }

      def process(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput],
        validationType: StateChannelValidationType,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[StateChannelAcceptanceResult] = {
        // Note: getFeeAddresses still reads from lastGlobalSnapshotInfo directly because it
        // iterates all lastCurrencySnapshots to collect fee addresses — a bulk operation not suited
        // for per-key MptStore lookups. The staking balance lookups in buildSnapshotFeesInfo use MptStore.
        val allFeesAddresses: Map[Address, Set[Address]] = getFeeAddresses(lastGlobalSnapshotInfo)
        type Acc = (Map[Address, Set[Address]], List[ValidatedNec[(Address, StateChannelValidationError), StateChannelOutput]])

        events
          .sortBy(_.address)
          .foldLeftM[F, Acc]((allFeesAddresses, List.empty)) {
            case ((prevAllFeeAddresses, alreadyProcessed), event) =>
              buildSnapshotFeesInfo(event, prevAllFeeAddresses).flatMap { snapshotFeesInfo =>
                val validationV = validationType match {
                  case StateChannelValidationType.Full =>
                    stateChannelValidator.validate(event, snapshotOrdinal, snapshotFeesInfo)
                  case StateChannelValidationType.Historical =>
                    stateChannelValidator.validateHistorical(event, snapshotOrdinal, snapshotFeesInfo)
                }

                validationV.map {
                  case valid @ Validated.Valid(event) =>
                    val updatedAllFeesAddresses = prevAllFeeAddresses.updatedWith(event.address) { existing =>
                      val added = Set(snapshotFeesInfo.ownerAddress, snapshotFeesInfo.stakingAddress).flatten
                      existing.map(_ ++ added).orElse(added.some)
                    }
                    (updatedAllFeesAddresses, alreadyProcessed :+ valid)
                  case invalid @ Validated.Invalid(_) =>
                    (prevAllFeeAddresses, alreadyProcessed :+ invalid.errorMap(error => (event.address, error)))
                }
              }
          }
          .map { case (_, processedEvents) => processedEvents.partitionMap(_.toEither) }
          .flatTap { case (invalid, _) => logger.warn(s"Invalid state channels events: $invalid").whenA(invalid.nonEmpty) }
          .flatMap { case (_, validatedEvents) => processStateChannelEvents(snapshotOrdinal, lastGlobalSnapshotInfo, validatedEvents) }
          .flatMap {
            case (scSnapshots, returnedSCEvents) =>
              processCurrencySnapshotBranchesWithReturned(
                snapshotOrdinal,
                lastGlobalSnapshotInfo,
                scSnapshots,
                validationType,
                getGlobalSnapshotByOrdinal
              ).map {
                case (accepted, typedReturned) =>
                  val (lastCurrencyStates, incomingCurrencyState) = calculateLastCurrencySnapshots(accepted, lastGlobalSnapshotInfo)
                  val finalScSnapshots = accepted.map { case (k, (v, _)) => k -> v.map(_._1) }
                  // TODO: ASSUMING that owner addresses are restricted from being shared at this point
                  val balanceUpdates = accepted.values.map(_._2).foldLeft(SortedMap.empty[Address, Balance])(_ ++ _)

                  StateChannelAcceptanceResult(
                    finalScSnapshots,
                    lastCurrencyStates,
                    returnedSCEvents ++ typedReturned,
                    balanceUpdates,
                    incomingCurrencyState
                  )
              }
          }
      }
      private def calculateLastCurrencySnapshots(
        processedCurrencySnapshots: SortedMap[Address, MetagraphAcceptanceResult],
        lastGlobalSnapshotInfo: GlobalSnapshotInfo
      ): (SortedMap[Address, CurrencySnapshotWithState], SortedMap[Address, List[CurrencySnapshotWithState]]) = {
        val lastCurrencySnapshotPerAddress =
          processedCurrencySnapshots.map { case (k, (v, _)) => k -> v.toList.flatMap(_._2).lastOption }.collect {
            case (key, Some(state)) => key -> state
          }

        val lastCurrencySnapshots =
          processedCurrencySnapshots.map { case (k, (v, _)) => k -> v.toList.flatMap(_._2) }.filterNot { case (_, list) => list.isEmpty }

        (
          lastGlobalSnapshotInfo.lastCurrencySnapshots.concat(lastCurrencySnapshotPerAddress),
          lastCurrencySnapshots
        )
      }

      private def applyCurrencySnapshot(
        currencyAddress: Address,
        lastState: CurrencySnapshotInfo,
        lastSnapshot: Signed[CurrencyIncrementalSnapshot],
        snapshot: Signed[CurrencyIncrementalSnapshot],
        validationType: StateChannelValidationType,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[CurrencySnapshotInfo] = {
        val createContext = validationType match {
          case StateChannelValidationType.Full       => currencySnapshotContextFns.createContext _
          case StateChannelValidationType.Historical => currencySnapshotContextFns.createHistoricalContext _
        }

        createContext(
          CurrencySnapshotContext(currencyAddress, lastState),
          lastSnapshot,
          snapshot,
          getGlobalSnapshotByOrdinal
        )
          .map(_.snapshotInfo)
      }

      /** Processes currency snapshots for each metagraph address, applying fee deduction logic.
        *
        * Fee deduction follows three cases per binary:
        *   1. Fee not required (pre-fee-ordinal or fee waived): accept the binary unconditionally. 2. Fee required but no fee address
        *      (owner address missing from currency messages): reject the binary — we cannot deduct fees without a destination address. 3.
        *      Fee required with fee address: look up the metagraph owner's balance first in the local accumulator (tracks balance changes
        *      within this batch), then fall back to MptStore. If the balance covers the fee, deduct it and accept; otherwise reject
        *      remaining binaries.
        */
      def processCurrencySnapshots(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[SortedMap[Address, MetagraphAcceptanceResult]] =
        processCurrencySnapshotsWithReturned(
          snapshotOrdinal,
          lastGlobalSnapshotInfo,
          events,
          StateChannelValidationType.Full,
          getGlobalSnapshotByOrdinal
        ).map(_._1)

      private def processCurrencySnapshotsWithReturned(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        validationType: StateChannelValidationType,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[CurrencyProcessingResult] =
        processCurrencySnapshotBranchesWithReturned(
          snapshotOrdinal,
          lastGlobalSnapshotInfo,
          events.map { case (address, selected) => address -> NonEmptyList.one(selected) },
          validationType,
          getGlobalSnapshotByOrdinal
        )

      private def processCurrencySnapshotBranchesWithReturned(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: SortedMap[Address, SelectedBranches],
        validationType: StateChannelValidationType,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[CurrencyProcessingResult] = {
        val isFeeRequired = feeCalculator.isFeeRequired(snapshotOrdinal)

        events.toList.parTraverse {
          case (address, binaries) =>
            type Result = Option[MetagraphAcceptanceResult]

            sealed trait BranchResult
            final case class BranchCompleted(result: Result) extends BranchResult
            final case class BranchDependencyRejected(
              result: Result,
              rejected: List[Signed[StateChannelSnapshotBinary]],
              reason: String,
              error: Throwable
            ) extends BranchResult
            final case class BranchTerminalRejected(
              result: Result,
              rejected: List[Signed[StateChannelSnapshotBinary]],
              reason: String
            ) extends BranchResult

            def completed(result: Result): F[BranchResult] =
              Async[F].pure(BranchCompleted(result): BranchResult)

            val stubBinary: Signed[StateChannelSnapshotBinary] = Signed(
              StateChannelSnapshotBinary(Hash.empty, Array.emptyByteArray, SnapshotFee.MinValue),
              NonEmptySet.one(SignatureProof(Id(Hex("")), Signature(Hex(""))))
            )

            val emptyBalanceUpdate = SortedMap.empty[Address, Balance]

            // initialState reads from lastGlobalSnapshotInfo.lastCurrencySnapshots (not MptStore)
            // because the Left(fullSnapshot) vs Right(incremental, info) distinction matters:
            // the Left branch handles the first-incremental-over-full transition without calling
            // applyCurrencySnapshot, while MptStore normalizes Left to Right (via fromCurrencySnapshot),
            // which would route into the applyCurrencySnapshot path and fail due to hash mismatches.
            val initialState =
              lastGlobalSnapshotInfo.lastCurrencySnapshots
                .get(address)
                .map(init => (stubBinary, init.some))
                .map(s => (NonEmptyList.one(s), SortedMap.empty[Address, Balance]))

            def normalize(result: Result): Result =
              result.map { case (snaps, balances) => (snaps.reverse, balances) }.flatMap {
                case (nel, balances) if initialState.nonEmpty => NonEmptyList.fromList(nel.tail).map((_, balances))
                case value                                    => value.some
              }

            def recordDependencyRejection(reason: String, count: Int, error: Throwable): F[Unit] =
              Metrics[F].incrementCounterBy(
                "dag_l0_state_channel_dependency_rejection_total",
                count,
                Seq(Metrics.unsafeLabelName("reason") -> reason)
              ) >> logger.warn(error)(
                s"Returning unsupported state-channel lineage address=${address.show} reason=$reason count=$count"
              )

            def processSelected(selectedBinaries: List[Signed[StateChannelSnapshotBinary]]): F[BranchResult] = {
              def dependencyRejected(
                current: Result,
                rejected: List[Signed[StateChannelSnapshotBinary]],
                reason: String,
                error: Throwable
              ): F[BranchResult] =
                recordDependencyRejection(reason, rejected.size, error) >>
                  Async[F].pure(BranchDependencyRejected(normalize(current), rejected, reason, error): BranchResult)

              def terminalRejected(
                current: Result,
                rejected: List[Signed[StateChannelSnapshotBinary]],
                reason: String
              ): F[BranchResult] =
                Metrics[F].incrementCounterBy(
                  "dag_l0_state_channel_rejection_total",
                  rejected.size,
                  Seq(Metrics.unsafeLabelName("reason") -> reason)
                ) >> Async[F].pure(BranchTerminalRejected(normalize(current), rejected, reason): BranchResult)

              def loop(state: Result, remaining: List[Signed[StateChannelSnapshotBinary]]): F[BranchResult] =
                remaining match {
                  case Nil => completed(normalize(state))

                  case head :: tail if state.isEmpty =>
                    deserialize[Signed[CurrencySnapshot]](head).flatMap {
                      case Some(snapshot) =>
                        loop((NonEmptyList.one((head, snapshot.asLeft.some)), emptyBalanceUpdate).some, tail)
                      case None if isFeeRequired => terminalRejected(none, head :: tail, "fee_required_unparseable")
                      case None                  => loop((NonEmptyList.one((head, none)), emptyBalanceUpdate).some, tail)
                    }

                  case head :: tail =>
                    val current = state
                    val (nel, balanceUpdate) = state.get

                    nel.head match {
                      case (_, None) =>
                        deserialize[Signed[CurrencySnapshot]](head).flatMap {
                          case Some(snapshot)        => loop((nel.prepend((head, snapshot.asLeft.some)), balanceUpdate).some, tail)
                          case None if isFeeRequired => terminalRejected(current, head :: tail, "fee_required_unparseable")
                          case None                  => loop((nel.prepend((head, none)), balanceUpdate).some, tail)
                        }

                      case (_, lastCurrState @ Some(Left(fullSnapshot))) =>
                        deserialize[Signed[CurrencyIncrementalSnapshot]](head).flatMap {
                          case Some(snapshot) =>
                            loop(
                              (
                                nel.prepend((head, (snapshot, fullSnapshot.value.info.toCurrencySnapshotInfo).asRight.some)),
                                balanceUpdate
                              ).some,
                              tail
                            )
                          case None if isFeeRequired => terminalRejected(current, head :: tail, "fee_required_unparseable")
                          case None                  => loop((nel.prepend((head, lastCurrState)), balanceUpdate).some, tail)
                        }

                      case (_, lastCurrState @ Some(Right((lastIncremental, lastState)))) =>
                        deserialize[Signed[CurrencyIncrementalSnapshot]](head).flatMap {
                          case Some(snapshot) =>
                            applyCurrencySnapshot(
                              address,
                              lastState,
                              lastIncremental,
                              snapshot,
                              validationType,
                              getGlobalSnapshotByOrdinal
                            ).flatMap { nextState =>
                              val maybeFeeAddress = nextState.lastMessages.flatMap(_.get(MessageType.Owner)).map(_.address)

                              maybeFeeAddress
                                .filter(_ => isFeeRequired)
                                .fold(
                                  if (!isFeeRequired)
                                    loop((nel.prepend((head, (snapshot, nextState).asRight.some)), balanceUpdate).some, tail)
                                  else terminalRejected(current, head :: tail, "fee_address_missing")
                                ) { feeAddress =>
                                  val initialBalanceF =
                                    if (snapshotOrdinal >= scFeeBalanceFromContextOrdinal)
                                      lastGlobalSnapshotInfo.balances.getOrElse(feeAddress, Balance.empty).pure[F]
                                    else
                                      mptStore.getBalance(feeAddress).map(_.getOrElse(Balance.empty))

                                  balanceUpdate.get(feeAddress).fold(initialBalanceF)(_.pure[F]).flatMap { balance =>
                                    balance.minus(head.fee).toOption match {
                                      case Some(updated) =>
                                        loop(
                                          (
                                            nel.prepend((head, (snapshot, nextState).asRight.some)),
                                            balanceUpdate + (feeAddress -> updated)
                                          ).some,
                                          tail
                                        )
                                      case None => terminalRejected(current, head :: tail, "fee_balance_insufficient")
                                    }
                                  }
                                }
                            }.handleErrorWith {
                              case error: OutsideRetainedWindow =>
                                dependencyRejected(current, head :: tail, "outside_retention", error)
                              case error: ProcessedHistoryUnproven =>
                                dependencyRejected(current, head :: tail, "processed_history_unproven", error)
                              case error: MissingInsideRetainedWindow =>
                                Metrics[F].incrementCounter(
                                  "dag_l0_state_channel_dependency_rejection_total",
                                  Seq(Metrics.unsafeLabelName("reason") -> "missing_recent")
                                ) >> error.raiseError[F, BranchResult]
                              case error =>
                                logger.warn(error)(
                                  s"Currency snapshot of ordinal ${snapshot.value.ordinal.show} for address ${address.show} couldn't be applied"
                                ) >> completed(normalize(current))
                            }

                          case None if isFeeRequired => terminalRejected(current, head :: tail, "fee_required_unparseable")
                          case None                  => loop((nel.prepend((head, lastCurrState)), balanceUpdate).some, tail)
                        }
                    }
                }

              loop(initialState, selectedBinaries.reverse)
            }

            def tryBranches(
              remaining: List[NonEmptyList[Signed[StateChannelSnapshotBinary]]],
              rejected: List[Signed[StateChannelSnapshotBinary]]
            ): F[(Address, Result, List[Signed[StateChannelSnapshotBinary]])] =
              remaining match {
                case Nil => (address, none[MetagraphAcceptanceResult], rejected).pure[F]
                case branch :: alternatives =>
                  processSelected(branch.toList).flatMap {
                    case BranchCompleted(result) => (address, result, rejected).pure[F]
                    case BranchDependencyRejected(Some(prefix), suffix, _, _) =>
                      (address, prefix.some, rejected ++ suffix).pure[F]
                    case BranchDependencyRejected(None, suffix, _, _) if alternatives.nonEmpty =>
                      Metrics[F].incrementCounter("dag_l0_state_channel_dependency_branch_fallback_total") >>
                        tryBranches(alternatives, rejected ++ suffix)
                    case BranchDependencyRejected(None, suffix, _, _) =>
                      (address, none[MetagraphAcceptanceResult], rejected ++ suffix).pure[F]
                    case BranchTerminalRejected(Some(prefix), suffix, _) =>
                      (address, prefix.some, rejected ++ suffix).pure[F]
                    case BranchTerminalRejected(None, suffix, _) if alternatives.nonEmpty =>
                      Metrics[F].incrementCounter("dag_l0_state_channel_terminal_branch_fallback_total") >>
                        tryBranches(alternatives, rejected ++ suffix)
                    case BranchTerminalRejected(None, suffix, _) =>
                      (address, none[MetagraphAcceptanceResult], rejected ++ suffix).pure[F]
                  }
              }

            tryBranches(binaries.toList, List.empty)
        }.map { results =>
          val accepted = results.foldLeft(SortedMap.empty[Address, MetagraphAcceptanceResult]) {
            case (acc, (address, Some(result), _)) => acc + (address -> result)
            case (acc, (_, None, _))               => acc
          }
          val returned = results.flatMap { case (address, _, binaries) => binaries.map(StateChannelOutput(address, _)) }.toSet
          (accepted, returned)
        }.flatTap {
          case (accepted, returned) =>
            val acceptedCount = accepted.valuesIterator.map(_._1.size).sum
            Metrics[F].incrementCounterBy(
              "dag_l0_state_channel_currency_result_total",
              acceptedCount,
              Seq(Metrics.unsafeLabelName("outcome") -> "accepted")
            ) >> Metrics[F].incrementCounterBy(
              "dag_l0_state_channel_currency_result_total",
              returned.size,
              Seq(Metrics.unsafeLabelName("outcome") -> "typed_rejected")
            )
        }
      }

      private def processStateChannelEvents(
        ordinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      )(implicit hasher: Hasher[F]): F[(SortedMap[Address, SelectedBranches], Set[StateChannelOutput])] =
        stateChannelManager.acceptBranches(ordinal, lastGlobalSnapshotInfo, events)

    }

}

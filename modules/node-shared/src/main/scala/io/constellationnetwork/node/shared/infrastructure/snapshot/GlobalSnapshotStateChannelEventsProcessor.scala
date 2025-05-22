package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Parallel
import cats.data._
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.syntax.validated.validatedSyntax
import io.constellationnetwork.json.JsonBrotliBinarySerializer
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.{StateChannelValidationError, getFeeAddresses}
import io.constellationnetwork.node.shared.domain.statechannel._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage._
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}

import io.circe.Decoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotStateChannelEventsProcessor[F[_]] {
  type BinaryCurrencyPair = (Signed[StateChannelSnapshotBinary], Option[CurrencySnapshotWithState])
  type BalanceUpdate = Map[Address, Balance]
  type MetagraphAcceptanceResult = (NonEmptyList[BinaryCurrencyPair], BalanceUpdate)

  def process(
    snapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: List[StateChannelOutput],
    validationType: StateChannelValidationType,
    getLastNGlobalSnapshots: => F[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[StateChannelAcceptanceResult]

  def processCurrencySnapshots(
    snapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotInfo: GlobalSnapshotInfo,
    events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
    getLastNGlobalSnapshots: => F[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(
    implicit hasher: Hasher[F]
  ): F[SortedMap[Address, MetagraphAcceptanceResult]]
}

object GlobalSnapshotStateChannelEventsProcessor {
  def make[F[_]: Async: Parallel](
    stateChannelValidator: StateChannelValidator[F],
    stateChannelManager: GlobalSnapshotStateChannelAcceptanceManager[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
    jsonBrotliBinarySerializer: JsonBrotliBinarySerializer[F],
    feeCalculator: FeeCalculator[F]
  ) =
    new GlobalSnapshotStateChannelEventsProcessor[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](GlobalSnapshotStateChannelEventsProcessor.getClass)

      def deserialize[A: Decoder](binary: Signed[StateChannelSnapshotBinary]): F[Option[A]] =
        jsonBrotliBinarySerializer.deserialize[A](binary.value.content).map(_.toOption)

      def buildSnapshotFeesInfo(
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
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
                Async[F].delay {
                  val stakingBalance = fetchStakingBalance(event.address, lastGlobalSnapshotInfo)
                  val sortedMessagesDesc = snapshot.value.messages.map(_.toList.sortBy(-_.ordinal.value.value))
                  val maybeOwnerAddress = sortedMessagesDesc.flatMap(_.find(_.messageType === MessageType.Owner)).map(_.address)
                  val maybeStakingAddress = sortedMessagesDesc.flatMap(_.find(_.messageType === MessageType.Staking)).map(_.address)
                  SnapshotFeesInfo(allFeesAddresses, stakingBalance, maybeOwnerAddress, maybeStakingAddress)
                }
            }
        }

      def process(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput],
        validationType: StateChannelValidationType,
        getLastNGlobalSnapshots: => F[List[Hashed[GlobalIncrementalSnapshot]]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[StateChannelAcceptanceResult] = {
        val allFeesAddresses: Map[Address, Set[Address]] = getFeeAddresses(lastGlobalSnapshotInfo)
        type Acc = (Map[Address, Set[Address]], List[ValidatedNec[(Address, StateChannelValidationError), StateChannelOutput]])

        events
          .foldLeftM[F, Acc]((allFeesAddresses, List.empty)) {
            case ((prevAllFeeAddresses, alreadyProcessed), event) =>
              buildSnapshotFeesInfo(lastGlobalSnapshotInfo, event, prevAllFeeAddresses).flatMap { snapshotFeesInfo =>
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
              processCurrencySnapshots(
                snapshotOrdinal,
                lastGlobalSnapshotInfo,
                scSnapshots,
                getLastNGlobalSnapshots,
                getGlobalSnapshotByOrdinal
              ).map { accepted =>
                val (lastCurrencyStates, incomingCurrencyState) = calculateLastCurrencySnapshots(accepted, lastGlobalSnapshotInfo)
                val finalScSnapshots = accepted.map { case (k, (v, _)) => k -> v.map(_._1) }
                // TODO: ASSUMING that owner addresses are restricted from being shared at this point
                val balanceUpdates = accepted.values.map(_._2).foldLeft(Map.empty[Address, Balance])(_ ++ _)

                StateChannelAcceptanceResult(
                  finalScSnapshots,
                  lastCurrencyStates,
                  returnedSCEvents,
                  balanceUpdates,
                  incomingCurrencyState
                )
              }
          }
      }
      private def calculateLastCurrencySnapshots(
        processedCurrencySnapshots: SortedMap[Address, MetagraphAcceptanceResult],
        lastGlobalSnapshotInfo: GlobalSnapshotInfo
      ): (SortedMap[Address, CurrencySnapshotWithState], SortedMap[Address, CurrencySnapshotWithState]) = {
        val lastCurrencySnapshots =
          processedCurrencySnapshots.map { case (k, (v, _)) => k -> v.toList.flatMap(_._2).lastOption }.collect {
            case (key, Some(state)) => key -> state
          }

        (
          lastGlobalSnapshotInfo.lastCurrencySnapshots.concat(lastCurrencySnapshots),
          lastCurrencySnapshots
        )
      }

      private def applyCurrencySnapshot(
        currencyAddress: Address,
        lastState: CurrencySnapshotInfo,
        lastSnapshot: Signed[CurrencyIncrementalSnapshot],
        snapshot: Signed[CurrencyIncrementalSnapshot],
        getLastNGlobalSnapshots: => F[List[Hashed[GlobalIncrementalSnapshot]]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[CurrencySnapshotInfo] =
        currencySnapshotContextFns
          .createContext(
            CurrencySnapshotContext(currencyAddress, lastState),
            lastSnapshot,
            snapshot,
            getLastNGlobalSnapshots,
            getGlobalSnapshotByOrdinal
          )
          .map(_.snapshotInfo)

      def processCurrencySnapshots(
        snapshotOrdinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        getLastNGlobalSnapshots: => F[List[Hashed[GlobalIncrementalSnapshot]]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[SortedMap[Address, MetagraphAcceptanceResult]] = {
        val isFeeRequired = feeCalculator.isFeeRequired(snapshotOrdinal)

        events.toList.parTraverse {
          case (address, binaries) =>
            type Result = Option[MetagraphAcceptanceResult]
            type Agg = (Result, List[Signed[StateChannelSnapshotBinary]])

            val stubBinary: Signed[StateChannelSnapshotBinary] = Signed(
              StateChannelSnapshotBinary(Hash.empty, Array.emptyByteArray, SnapshotFee.MinValue),
              NonEmptySet.one(SignatureProof(Id(Hex("")), Signature(Hex(""))))
            )

            val emptyBalanceUpdate = Map.empty[Address, Balance]

            val initialState =
              lastGlobalSnapshotInfo.lastCurrencySnapshots
                .get(address)
                .map(init => (stubBinary, init.some))
                .map(s => (NonEmptyList.one(s), Map.empty[Address, Balance]))

            (initialState, binaries.toList.reverse)
              .tailRecM[F, Result] {
                case (state, Nil) => state.asRight[Agg].pure[F]

                case (None, head :: tail) =>
                  deserialize[Signed[CurrencySnapshot]](head).map {
                    case Some(snapshot) => // full snapshot - we don't subtract fee
                      (
                        (NonEmptyList.one((head, snapshot.asLeft.some)), emptyBalanceUpdate).some,
                        tail
                      ).asLeft
                    case None => // no full snapshot yet - we only accept the binary if fee is not required
                      if (isFeeRequired) none.asRight
                      else ((NonEmptyList.one((head, none)), emptyBalanceUpdate).some, tail).asLeft
                  }

                case (current @ Some((nel, balanceUpdate)), head :: tail) =>
                  nel.head match {
                    case (_, None) =>
                      deserialize[Signed[CurrencySnapshot]](head).map {
                        case Some(snapshot) => // full snapshot - we don't subtract fee
                          (
                            (nel.prepend((head, snapshot.asLeft.some)), balanceUpdate).some,
                            tail
                          ).asLeft
                        case None => // no full snapshot yet - we only accept the binary if fee is not required
                          if (isFeeRequired) current.asRight
                          else ((nel.prepend((head, none)), balanceUpdate).some, tail).asLeft
                      }

                    case (_, lastCurrState @ Some(Left(fullSnapshot))) =>
                      deserialize[Signed[CurrencyIncrementalSnapshot]](head).map {
                        case Some(snapshot) => // first incremental - we don't subtract fee
                          (
                            (
                              nel.prepend((head, (snapshot, fullSnapshot.value.info.toCurrencySnapshotInfo).asRight.some)),
                              balanceUpdate
                            ).some,
                            tail
                          ).asLeft
                        case None => // no first incremental yet - we only accept the binary if fee is not required
                          if (isFeeRequired) current.asRight
                          else ((nel.prepend((head, lastCurrState)), balanceUpdate).some, tail).asLeft
                      }

                    case (_, lastCurrState @ Some(Right((lastIncremental, lastState)))) =>
                      deserialize[Signed[CurrencyIncrementalSnapshot]](head).flatMap {
                        case Some(snapshot) => // second or subsequent incremental snapshot - we do subtract fee
                          applyCurrencySnapshot(
                            address,
                            lastState,
                            lastIncremental,
                            snapshot,
                            getLastNGlobalSnapshots,
                            getGlobalSnapshotByOrdinal
                          ).map { state =>
                            val maybeFeeAddress = state.lastMessages.flatMap(_.get(MessageType.Owner)).map(_.address)

                            val maybeBalanceUpdate = maybeFeeAddress.filter(_ => isFeeRequired).flatMap { feeAddress =>
                              val balance = balanceUpdate
                                .get(feeAddress)
                                .orElse(lastGlobalSnapshotInfo.balances.get(feeAddress))
                                .getOrElse(Balance.empty)
                              balance.minus(head.fee).toOption.map(uBalance => balanceUpdate + (feeAddress -> uBalance))
                            }

                            maybeBalanceUpdate match {
                              case Some(newBalanceUpdate) =>
                                ((nel.prepend((head, (snapshot, state).asRight.some)), newBalanceUpdate).some, tail).asLeft
                              case None if !isFeeRequired =>
                                ((nel.prepend((head, (snapshot, state).asRight.some)), balanceUpdate).some, tail).asLeft[Result]
                              case None => // balance update unsuccessful or impossible? we can't accept any more snapshots
                                current.asRight
                            }
                          }.handleErrorWith { e => // we don't accept neither binary nor incremental
                            logger.warn(e)(
                              s"Currency snapshot of ordinal ${snapshot.value.ordinal.show} for address ${address.show} couldn't be applied"
                            ) >> Async[F].pure(current.asRight)
                          }
                        case None => // again we only let it through if fee is not required
                          if (isFeeRequired)
                            Async[F].pure(current.asRight) // was: none.asRight but why clean it out rather than using current state?
                          else ((nel.prepend((head, lastCurrState)), balanceUpdate).some, tail).asLeft.pure[F]
                      }
                  }
              }
              .map(_.map { case (snaps, balances) => (snaps.reverse, balances) })
              .map { maybeProcessed =>
                initialState match {
                  case Some(_) => maybeProcessed.flatMap { case (nel, balances) => NonEmptyList.fromList(nel.tail).map((_, balances)) }
                  case None    => maybeProcessed
                }
              }
              .map(result => address -> result)
        }.map { results =>
          results.foldLeft(SortedMap.empty[Address, MetagraphAcceptanceResult]) {
            case (acc, (address, Some(result))) => acc + (address -> result)
            case (acc, (_, None))               => acc
          }
        }
      }

      private def processStateChannelEvents(
        ordinal: SnapshotOrdinal,
        lastGlobalSnapshotInfo: GlobalSnapshotInfo,
        events: List[StateChannelOutput]
      )(implicit hasher: Hasher[F]): F[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[StateChannelOutput])] =
        stateChannelManager.accept(ordinal, lastGlobalSnapshotInfo, events)

    }

}

package io.constellationnetwork.currency.l0.snapshot

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotConsensusStateCreator.{
  canStartOwnedConsensus,
  isInitialOwnerMessageEvent
}
import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusKind, CurrencyConsensusOutcome}
import io.constellationnetwork.currency.schema.currency.CurrencySnapshotContext
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusStorage
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencyMessageEvent, CurrencySnapshotArtifact, CurrencySnapshotEvent}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import io.constellationnetwork.schema.gossip.Ordinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegBigDecimal
import weaver.SimpleIOSuite

/** Regression suite for the ordinal 2 consensus gate.
  *
  * The initial Owner message can only be accepted at currency snapshot ordinal 2 and, on fee-charging networks, the global L0 charges the
  * snapshot fee to the owner address from that ordinal on. When the ordinal 2 round raced ahead of the Owner message submission, snapshot 2
  * was produced without an owner, the global L0 could not charge the fee and rejected the binary, permanently rejecting every subsequent
  * snapshot of the metagraph. The gate defers the ordinal 2 round until the Owner message is available for inclusion.
  */
object CurrencySnapshotConsensusStateCreatorSuite extends SimpleIOSuite {

  private val ordinal2 = SnapshotOrdinal.unsafeApply(2L)
  private val ordinal3 = SnapshotOrdinal.unsafeApply(3L)
  private val feeActivationOrdinal = SnapshotOrdinal.unsafeApply(2572684L)
  private val currentGlobalOrdinal = SnapshotOrdinal.unsafeApply(6700000L)

  private val ownerAddress = Address("DAG132WVZ4sL9z8Vs13okM6iCNLwTT5qf5miReFT")
  private val stakingAddress = Address("DAG7WM7isHYDYwkwqTkkwoajkdH5m3nHssx1GKCW")
  private val metagraphId = Address("DAG8ZnY1voFrENbSfwe8eT9WC6EeKTUejw7JYek4")

  private val mainnetLikeFeeConfig = FeeCalculatorConfig(
    baseFee = 100000L,
    stakingWeight = NonNegBigDecimal.unsafeFrom(BigDecimal(0)),
    computationalCost = 1L,
    proWeight = NonNegBigDecimal.unsafeFrom(BigDecimal(0))
  )

  private val feesActive: FeeCalculator[IO] =
    FeeCalculator.make[IO](SortedMap(feeActivationOrdinal -> mainnetLikeFeeConfig))

  private val feesNever: FeeCalculator[IO] =
    FeeCalculator.make[IO](SortedMap.empty)

  private def dummyProofs: NonEmptySet[SignatureProof] =
    NonEmptySet.one(SignatureProof(Id(Hex("")), Signature(Hex(""))))

  private def messageEvent(
    messageType: MessageType,
    parentOrdinal: MessageOrdinal = MessageOrdinal.MinValue,
    messageMetagraphId: Address = metagraphId
  ): CurrencySnapshotEvent = {
    val address = if (messageType === MessageType.Owner) ownerAddress else stakingAddress
    CurrencyMessageEvent(Signed(CurrencyMessage(messageType, address, messageMetagraphId, parentOrdinal), dummyProofs))
  }

  test("defers the ordinal 2 round when fees are required and no Owner message is pending") {
    canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesActive, false.pure[IO])
      .map(result => expect(!result))
  }

  test("starts the ordinal 2 round once an Owner message is pending") {
    canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesActive, true.pure[IO])
      .map(result => expect(result))
  }

  test("starts rounds other than ordinal 2 regardless of pending messages") {
    canStartOwnedConsensus[IO](ordinal3, none, currentGlobalOrdinal.some.pure[IO], feesActive, false.pure[IO])
      .map(result => expect(result))
  }

  test("starts the ordinal 2 round when the owner is already set in the last context") {
    canStartOwnedConsensus[IO](ordinal2, ownerAddress.some, currentGlobalOrdinal.some.pure[IO], feesActive, false.pure[IO])
      .map(result => expect(result))
  }

  test("starts the ordinal 2 round when fees are never required (no fee configs)") {
    canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesNever, false.pure[IO])
      .map(result => expect(result))
  }

  test("starts the ordinal 2 round when the fee config only activates at a later global ordinal") {
    val beforeActivation = SnapshotOrdinal.unsafeApply(10L)
    canStartOwnedConsensus[IO](ordinal2, none, beforeActivation.some.pure[IO], feesActive, false.pure[IO])
      .map(result => expect(result))
  }

  test("defers the ordinal 2 round when the global ordinal is unknown but fee configs exist") {
    canStartOwnedConsensus[IO](ordinal2, none, none[SnapshotOrdinal].pure[IO], feesActive, false.pure[IO])
      .map(result => expect(!result))
  }

  /* The pendingOwnerMessageExists effect reads the metagraph identifier, which is unset while
   * create-genesis wires the currency-l0 services. Forcing it outside the gate crashed the
   * metagraph L0 at startup with "Identifier not set!", so it must stay unevaluated on every
   * path that does not need it. */
  private val identifierNotSet: IO[Boolean] =
    IO.raiseError(new Throwable("Identifier not set! Encountered attempt to read it"))

  test("does not evaluate pendingOwnerMessageExists for rounds other than ordinal 2") {
    canStartOwnedConsensus[IO](ordinal3, none, currentGlobalOrdinal.some.pure[IO], feesActive, identifierNotSet).attempt.map { result =>
      expect(result == Right(true), s"ordinal 3 must start without reading the identifier, got $result")
    }
  }

  test("does not evaluate pendingOwnerMessageExists when the owner is already set") {
    canStartOwnedConsensus[IO](ordinal2, ownerAddress.some, currentGlobalOrdinal.some.pure[IO], feesActive, identifierNotSet).attempt.map {
      result =>
        expect(result == Right(true), s"an already-owned metagraph must not read the identifier, got $result")
    }
  }

  test("does not evaluate pendingOwnerMessageExists when fees are never required") {
    canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesNever, identifierNotSet).attempt.map { result =>
      expect(result == Right(true), s"a fee-free network must not read the identifier, got $result")
    }
  }

  pureTest("isInitialOwnerMessageEvent matches only the initial Owner message for this metagraph") {
    val ownerInitial = isInitialOwnerMessageEvent(metagraphId)(messageEvent(MessageType.Owner))
    val staking = isInitialOwnerMessageEvent(metagraphId)(messageEvent(MessageType.Staking))
    val wrongMetagraph = isInitialOwnerMessageEvent(metagraphId)(
      messageEvent(MessageType.Owner, messageMetagraphId = stakingAddress)
    )
    val nonInitialOwner = isInitialOwnerMessageEvent(metagraphId)(
      messageEvent(MessageType.Owner, parentOrdinal = MessageOrdinal(1L).toOption.get)
    )

    expect(ownerInitial, "the initial Owner message for this metagraph must match")
      .and(expect(!staking, "a Staking message must not match"))
      .and(expect(!wrongMetagraph, "an Owner message for a different metagraph must not match"))
      .and(expect(!nonInitialOwner, "a non-initial Owner message must not match"))
  }

  test("existsEvent finds a pending initial Owner message in the consensus event queue") {
    val consensusConfig = ConsensusConfig(
      timeTriggerInterval = 43.seconds,
      declarationTimeout = 50.seconds,
      declarationRangeLimit = 3L,
      lockDuration = 10.seconds,
      peersDeclarationTimeout = 10.seconds,
      eventCutter = EventCutterConfig(maxBinarySizeBytes = 20971520, maxUpdateNodeParametersSize = 100)
    )
    val peerId = PeerId(Hex("00"))

    for {
      storage <- ConsensusStorage.make[
        IO,
        CurrencySnapshotEvent,
        CurrencySnapshotKey,
        CurrencySnapshotArtifact,
        CurrencySnapshotContext,
        CurrencySnapshotStatus,
        CurrencyConsensusOutcome,
        CurrencyConsensusKind
      ](consensusConfig)
      emptyResult <- storage.existsEvent(isInitialOwnerMessageEvent(metagraphId))
      _ <- storage.addEvents(Map(peerId -> List((Ordinal.MinValue, messageEvent(MessageType.Staking)))))
      stakingOnlyResult <- storage.existsEvent(isInitialOwnerMessageEvent(metagraphId))
      _ <- storage.addEvents(Map(peerId -> List((Ordinal.MinValue, messageEvent(MessageType.Owner)))))
      ownerResult <- storage.existsEvent(isInitialOwnerMessageEvent(metagraphId))
    } yield
      expect(!emptyResult, "an empty event queue must not report a pending initial Owner message")
        .and(expect(!stakingOnlyResult, "a Staking-only event queue must not report a pending initial Owner message"))
        .and(expect(ownerResult, "the pending initial Owner message must be found in the event queue"))
  }
}

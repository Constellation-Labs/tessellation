package io.constellationnetwork.currency.l0.snapshot

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotConsensusStateAdvancer.retainedAfterProposal
import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotConsensusStateCreator._
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencyMessageEvent, CurrencySnapshotEvent}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegBigDecimal
import weaver.SimpleIOSuite

object CurrencySynchronousStateCreatorSuite extends SimpleIOSuite {

  private val ordinal2 = SnapshotOrdinal.unsafeApply(2L)
  private val ordinal3 = SnapshotOrdinal.unsafeApply(3L)
  private val feeActivationOrdinal = SnapshotOrdinal.unsafeApply(2572684L)
  private val currentGlobalOrdinal = SnapshotOrdinal.unsafeApply(6700000L)

  private val ownerAddress = Address("DAG132WVZ4sL9z8Vs13okM6iCNLwTT5qf5miReFT")
  private val stakingAddress = Address("DAG7WM7isHYDYwkwqTkkwoajkdH5m3nHssx1GKCW")
  private val metagraphId = Address("DAG8ZnY1voFrENbSfwe8eT9WC6EeKTUejw7JYek4")

  private val feesActive: FeeCalculator[IO] =
    FeeCalculator.make[IO](
      SortedMap(
        feeActivationOrdinal -> FeeCalculatorConfig(
          baseFee = 100000L,
          stakingWeight = NonNegBigDecimal.unsafeFrom(BigDecimal(0)),
          computationalCost = 1L,
          proWeight = NonNegBigDecimal.unsafeFrom(BigDecimal(0))
        )
      )
    )

  private val feesNever: FeeCalculator[IO] = FeeCalculator.make[IO](SortedMap.empty)

  private val dummyProofs: NonEmptySet[SignatureProof] =
    NonEmptySet.one(SignatureProof(Id(Hex("")), Signature(Hex(""))))

  private def messageEvent(
    messageType: MessageType,
    parentOrdinal: MessageOrdinal = MessageOrdinal.MinValue,
    messageMetagraphId: Address = metagraphId
  ): CurrencySnapshotEvent = {
    val address = if (messageType === MessageType.Owner) ownerAddress else stakingAddress
    CurrencyMessageEvent(Signed(CurrencyMessage(messageType, address, messageMetagraphId, parentOrdinal), dummyProofs))
  }

  test("ordinal 2 waits for the initial Owner message when fees apply") {
    for {
      absent <- canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesActive, false.pure[IO])
      present <- canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesActive, true.pure[IO])
    } yield expect.all(!absent, present)
  }

  test("the owner gate is inert outside the dangerous fee-paying ordinal-2 case") {
    val identifierNotSet = IO.raiseError[Boolean](new Throwable("Identifier not set"))

    for {
      later <- canStartOwnedConsensus[IO](ordinal3, none, currentGlobalOrdinal.some.pure[IO], feesActive, identifierNotSet).attempt
      alreadyOwned <- canStartOwnedConsensus[IO](
        ordinal2,
        ownerAddress.some,
        currentGlobalOrdinal.some.pure[IO],
        feesActive,
        identifierNotSet
      ).attempt
      feeFree <- canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesNever, identifierNotSet).attempt
      beforeFees <- canStartOwnedConsensus[IO](ordinal2, none, SnapshotOrdinal.unsafeApply(10L).some.pure[IO], feesActive, false.pure[IO])
    } yield
      expect.all(
        later.toOption.contains(true),
        alreadyOwned.toOption.contains(true),
        feeFree.toOption.contains(true),
        beforeFees
      )
  }

  test("unknown GL0 position fails conservatively when a fee schedule exists") {
    canStartOwnedConsensus[IO](ordinal2, none, none[SnapshotOrdinal].pure[IO], feesActive, false.pure[IO])
      .map(result => expect(!result))
  }

  pureTest("only this metagraph's initial Owner message opens the gate") {
    val ownerInitial = isInitialOwnerMessageEvent(metagraphId)(messageEvent(MessageType.Owner))
    val staking = isInitialOwnerMessageEvent(metagraphId)(messageEvent(MessageType.Staking))
    val wrongMetagraph = isInitialOwnerMessageEvent(metagraphId)(
      messageEvent(MessageType.Owner, messageMetagraphId = stakingAddress)
    )
    val nonInitialOwner = isInitialOwnerMessageEvent(metagraphId)(
      messageEvent(MessageType.Owner, parentOrdinal = MessageOrdinal(1L).toOption.get)
    )

    expect.all(ownerInitial, !staking, !wrongMetagraph, !nonInitialOwner)
  }

  pureTest("events rejected against one parent remain eligible for a later round") {
    val awaiting = messageEvent(MessageType.Staking)
    val rejected = messageEvent(MessageType.Owner)

    expect.same(Set(awaiting, rejected), retainedAfterProposal(Set(awaiting), Set(rejected)))
  }

  test("a Facility advertises only events confirmed on every round-start facilitator") {
    val firstHash = Hash.fromBytes(Array[Byte](1))
    val secondHash = Hash.fromBytes(Array[Byte](2))
    val firstPeer = PeerId(Hex("01" * 64))
    val secondPeer = PeerId(Hex("02" * 64))
    val confirmations = Map(
      firstPeer -> Set(firstHash, secondHash),
      secondPeer -> Set(firstHash)
    )

    retainUniversallyAvailableHashes[IO](Set(firstHash, secondHash), List(firstPeer, secondPeer)) { (peerId, requested) =>
      IO.pure(confirmations.getOrElse(peerId, Set.empty).intersect(requested))
    }.flatMap(result => IO.pure(expect(result.toSet === Set(firstHash))))
  }

  test("an unavailable round-start facilitator defers event data without blocking an empty round") {
    val hash = Hash.fromBytes(Array[Byte](1))
    val availablePeer = PeerId(Hex("01" * 64))
    val unavailablePeer = PeerId(Hex("02" * 64))

    retainUniversallyAvailableHashes[IO](Set(hash), List(availablePeer, unavailablePeer)) { (peerId, requested) =>
      IO.pure(if (peerId === availablePeer) requested else Set.empty)
    }.flatMap(result => IO.pure(expect(result.isEmpty)))
  }

  pureTest("per-facilitator event advertisement keeps the complete union within the fixed work bound") {
    val limit = io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool.DefaultSnapshotLimit

    expect.all(
      facilityEventLimit(1) === limit,
      facilityEventLimit(2) * 2 <= limit,
      facilityEventLimit(3) * 3 <= limit,
      facilityEventLimit(7) * 7 <= limit
    )
  }

  test("registration is carried only when both seedlist and signed-parent eligibility allow it") {
    val allowed = PeerId(Hex("01" * 64))
    val notInSeedlist = PeerId(Hex("02" * 64))
    val underCollateral = PeerId(Hex("03" * 64))

    retainEligibleCandidates[IO](
      Set(allowed, notInSeedlist, underCollateral),
      peerId => peerId =!= notInSeedlist
    )(peerId => IO.pure(peerId =!= underCollateral)).map { candidates =>
      expect(candidates.value === Set(allowed))
    }
  }

}

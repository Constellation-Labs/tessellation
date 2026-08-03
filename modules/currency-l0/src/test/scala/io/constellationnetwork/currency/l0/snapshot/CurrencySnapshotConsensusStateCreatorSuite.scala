package io.constellationnetwork.currency.l0.snapshot

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataTransaction}
import io.constellationnetwork.currency.l0.infrastructure.mempool.CurrencyEventMempool
import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotConsensusStateCreator.{canStartOwnedConsensus, pendingOwnerMessageExists}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencyMessageEvent, CurrencySnapshotEvent}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegBigDecimal
import io.circe.Encoder
import weaver.MutableIOSuite

/** Regression suite for the ordinal 2 consensus gate.
  *
  * The initial Owner message can only be accepted at currency snapshot ordinal 2 and, on fee-charging networks, the global L0 charges the
  * snapshot fee to the owner address from that ordinal on. When the ordinal 2 round raced ahead of the Owner message submission, snapshot 2
  * was produced without an owner, the global L0 could not charge the fee and rejected the binary, permanently rejecting every subsequent
  * snapshot of the metagraph. The gate defers the ordinal 2 round until the Owner message is available for inclusion.
  */
object CurrencySnapshotConsensusStateCreatorSuite extends MutableIOSuite {

  type Res = (Hasher[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      h = Hasher.forJson[IO]
    } yield (h, j)

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

  private def messageEvent(messageType: MessageType): Signed[CurrencySnapshotEvent] = {
    val message = CurrencyMessage(messageType, if (messageType === MessageType.Owner) ownerAddress else stakingAddress, metagraphId, MessageOrdinal.MinValue)
    Signed[CurrencySnapshotEvent](CurrencyMessageEvent(Signed(message, dummyProofs)), dummyProofs)
  }

  test("defers the ordinal 2 round when fees are required and no Owner message is pending") { _ =>
    canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesActive, false.pure[IO])
      .map(result => expect(!result))
  }

  test("starts the ordinal 2 round once an Owner message is pending") { _ =>
    canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesActive, true.pure[IO])
      .map(result => expect(result))
  }

  test("starts rounds other than ordinal 2 regardless of pending messages") { _ =>
    canStartOwnedConsensus[IO](ordinal3, none, currentGlobalOrdinal.some.pure[IO], feesActive, false.pure[IO])
      .map(result => expect(result))
  }

  test("starts the ordinal 2 round when the owner is already set in the last context") { _ =>
    canStartOwnedConsensus[IO](ordinal2, ownerAddress.some, currentGlobalOrdinal.some.pure[IO], feesActive, false.pure[IO])
      .map(result => expect(result))
  }

  test("starts the ordinal 2 round when fees are never required (no fee configs)") { _ =>
    canStartOwnedConsensus[IO](ordinal2, none, currentGlobalOrdinal.some.pure[IO], feesNever, false.pure[IO])
      .map(result => expect(result))
  }

  test("starts the ordinal 2 round when the fee config only activates at a later global ordinal") { _ =>
    val beforeActivation = SnapshotOrdinal.unsafeApply(10L)
    canStartOwnedConsensus[IO](ordinal2, none, beforeActivation.some.pure[IO], feesActive, false.pure[IO])
      .map(result => expect(result))
  }

  test("defers the ordinal 2 round when the global ordinal is unknown but fee configs exist") { _ =>
    canStartOwnedConsensus[IO](ordinal2, none, none[SnapshotOrdinal].pure[IO], feesActive, false.pure[IO])
      .map(result => expect(!result))
  }

  test("pendingOwnerMessageExists finds a pending Owner message in the mempool") {
    case (h, _) =>
      implicit val hasher: Hasher[IO] = h
      implicit val dtEncoder: Encoder[DataTransaction] = DataTransactionCodecs.encoder(none[BaseDataApplicationL0Service[IO]])

      for {
        mempool <- CurrencyEventMempool.make[IO](CurrencyEventMempool.defaultConfig)
        emptyResult <- pendingOwnerMessageExists(mempool)
        staking <- mempool.add(messageEvent(MessageType.Staking))
        stakingOnlyResult <- pendingOwnerMessageExists(mempool)
        owner <- mempool.add(messageEvent(MessageType.Owner))
        ownerResult <- pendingOwnerMessageExists(mempool)
      } yield
        expect(!emptyResult, "empty mempool must not report a pending Owner message")
          .and(expect(staking.isRight, s"staking event should be accepted by the mempool, got $staking"))
          .and(expect(!stakingOnlyResult, "a Staking-only mempool must not report a pending Owner message"))
          .and(expect(owner.isRight, s"owner event should be accepted by the mempool, got $owner"))
          .and(expect(ownerResult, "the pending Owner message must be found in the mempool"))
  }
}

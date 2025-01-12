package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.Show
import cats.effect.IO
import cats.syntax.either._
import cats.syntax.option._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.StateChannelEvent
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculatorConfig
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage.MessageType.Staking
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal, SnapshotTips}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric._
import org.scalacheck.Gen
import weaver.SimpleMutableIOSuite
import weaver.scalacheck.Checkers

object SnapshotBinaryFeeCalculatorSuite extends SimpleMutableIOSuite with Checkers {
  val eventAddress = Address(refineV[DAGAddressRefined].unsafeFrom("DAGSTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS"))
  val stakingAddress = Address(refineV[DAGAddressRefined].unsafeFrom("DAG7coCMRPJah33MMcfAEZVeB1vYn3vDRe6WqeGU"))
  val metagraphId = Address(refineV[DAGAddressRefined].unsafeFrom("DAG7coCMRPJah33MMcfAEZVeB1vYn3vDRe6WqeGU"))

  val configs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig] =
    SortedMap(
      SnapshotOrdinal.MinValue ->
        FeeCalculatorConfig(
          baseFee = NonNegLong(100_000),
          computationalCost = NonNegLong(1),
          stakingWeight = NonNegBigDecimal(BigDecimal(2e-15)),
          proWeight = NonNegBigDecimal(BigDecimal(0))
        )
    )

  val tenKb = 10 * 1024
  val twoHundredKb = 200 * 1024
  val snapshotFee = SnapshotFee(NonNegLong(30_000))
  val smallBalance = Balance(NonNegLong(5_000 * 1e8.toLong))
  val medBalance = Balance(NonNegLong(100_000 * 1e8.toLong))
  val bigBalance = Balance(NonNegLong(900_000 * 1e8.toLong))

  case class SampleInput(
    binaryContentLength: Int,
    balance: Balance,
    expectedFeeWithBalance: NonNegLong,
    expectedFeeWithoutBalance: NonNegLong
  )

  val sampleInputs = List(
    SampleInput(
      binaryContentLength = tenKb,
      balance = Balance.empty,
      expectedFeeWithBalance = 1_200_000L,
      expectedFeeWithoutBalance = 1_200_000L
    ),
    SampleInput(
      binaryContentLength = tenKb,
      balance = smallBalance,
      expectedFeeWithBalance = 1_199_000L,
      expectedFeeWithoutBalance = 1_200_000L
    ),
    SampleInput(
      binaryContentLength = tenKb,
      balance = medBalance,
      expectedFeeWithBalance = 1_180_392L,
      expectedFeeWithoutBalance = 1_200_000L
    ),
    SampleInput(
      binaryContentLength = tenKb,
      balance = bigBalance,
      expectedFeeWithBalance = 1_047_457L,
      expectedFeeWithoutBalance = 1_200_000L
    ),
    SampleInput(
      binaryContentLength = twoHundredKb,
      balance = Balance.empty,
      expectedFeeWithBalance = 25_900_000L,
      expectedFeeWithoutBalance = 25_900_000L
    ),
    SampleInput(
      binaryContentLength = twoHundredKb,
      balance = smallBalance,
      expectedFeeWithBalance = 25_880_019L,
      expectedFeeWithoutBalance = 25_900_000L
    ),
    SampleInput(
      binaryContentLength = twoHundredKb,
      balance = medBalance,
      expectedFeeWithBalance = 25_507_843L,
      expectedFeeWithoutBalance = 25_900_000L
    ),
    SampleInput(
      binaryContentLength = twoHundredKb,
      balance = bigBalance,
      expectedFeeWithBalance = 22_849_152L,
      expectedFeeWithoutBalance = 25_900_000L
    )
  )

  def binary(input: SampleInput): StateChannelSnapshotBinary =
    StateChannelSnapshotBinary(
      lastSnapshotHash = Hash.empty,
      content = Array.fill(input.binaryContentLength)(Byte.MinValue),
      fee = snapshotFee
    )

  val currencyIncrementalSnapshotGen: Gen[Signed[CurrencyIncrementalSnapshot]] =
    signedOf(
      CurrencyIncrementalSnapshot(
        ordinal = SnapshotOrdinal.MinValue,
        height = Height.MinValue,
        subHeight = SubHeight.MinValue,
        lastSnapshotHash = Hash.empty,
        blocks = SortedSet.empty,
        rewards = SortedSet.empty,
        tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
        stateProof = CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
        epochProgress = EpochProgress.MinValue,
        dataApplication = None,
        messages = None,
        globalSnapshotSyncs = None,
        feeTransactions = None,
        artifacts = None,
        allowSpendBlocks = None,
        tokenLockBlocks = None
      )
    )

  def currencyMessageGen(address: Address, metagraphId: Address): Gen[Signed[CurrencyMessage]] =
    signedOf(CurrencyMessage(MessageType.Staking, address, metagraphId, MessageOrdinal.MinValue))

  def lastCurrencySnapshotsGen(
    stakingAddress: Option[Address],
    metagraphId: Option[Address]
  ): Gen[SortedMap[Address, Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]] =
    for {
      signedCis <- currencyIncrementalSnapshotGen
      lastMessages <- stakingAddress
        .zip(metagraphId)
        .fold(
          Gen.const(Option.empty[SortedMap[MessageType, Signed[CurrencyMessage]]])
        ) {
          case (address, metagraph) =>
            currencyMessageGen(address, metagraph)
              .map(signedMsg => SortedMap[MessageType, Signed[CurrencyMessage]](Staking -> signedMsg))
              .map(_.some)
        }
      currencySnapshotInfo = CurrencySnapshotInfo(
        lastTxRefs = SortedMap.empty,
        balances = SortedMap.empty,
        lastMessages = lastMessages,
        None,
        None,
        None,
        None,
        None,
        None
      )
    } yield SortedMap(eventAddress -> (signedCis, currencySnapshotInfo).asRight[Signed[CurrencySnapshot]])

  def testDataGen(stakingAddress: Option[Address], metagraphId: Option[Address]) =
    for {
      input <- Gen.oneOf(sampleInputs)
      event <- signedOf(binary(input)).map(StateChannelOutput(eventAddress, _)).map(StateChannelEvent(_))
      lastCurrencySnapshots <- lastCurrencySnapshotsGen(stakingAddress, metagraphId)
    } yield (input, event, lastCurrencySnapshots)

  implicit val showTestData: Show[
    (
      SampleInput,
      StateChannelEvent,
      SortedMap[Address, Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]]
    )
  ] = Show.show(_.toString)

  test("when snapshot for event address is not found") { _ =>
    val calculator = SnapshotBinaryFeeCalculator.make[IO](configs)
    forall(testDataGen(None, None)) {
      case (input, stateChannelEvent, _) =>
        val info = GlobalSnapshotInfo.empty.copy(balances = SortedMap(stakingAddress -> input.balance))
        calculator
          .calculateFee(stateChannelEvent, info, SnapshotOrdinal.MinValue)
          .map(actual => expect.same(input.expectedFeeWithoutBalance, actual))
    }
  }

  test("when message for staking address is not found") { _ =>
    val calculator = SnapshotBinaryFeeCalculator.make[IO](configs)
    forall(testDataGen(None, None)) {
      case (input, stateChannelEvent, lastCurrencySnapshots) =>
        val info = GlobalSnapshotInfo.empty.copy(
          balances = SortedMap(stakingAddress -> input.balance),
          lastCurrencySnapshots = lastCurrencySnapshots
        )
        calculator
          .calculateFee(stateChannelEvent, info, SnapshotOrdinal.MinValue)
          .map(actual => expect.same(input.expectedFeeWithoutBalance, actual))
    }
  }

  test("when balance is not unavailable") { _ =>
    val calculator = SnapshotBinaryFeeCalculator.make[IO](configs)
    forall(testDataGen(stakingAddress.some, metagraphId.some)) {
      case (input, stateChannelEvent, lastCurrencySnapshots) =>
        val info = GlobalSnapshotInfo.empty.copy(
          balances = SortedMap.empty,
          lastCurrencySnapshots = lastCurrencySnapshots
        )
        calculator
          .calculateFee(stateChannelEvent, info, SnapshotOrdinal.MinValue)
          .map(actual => expect.same(input.expectedFeeWithoutBalance, actual))
    }
  }

  test("when balance is available") { _ =>
    val calculator = SnapshotBinaryFeeCalculator.make[IO](configs)
    forall(testDataGen(stakingAddress.some, metagraphId.some)) {
      case (input, stateChannelEvent, lastCurrencySnapshots) =>
        val info = GlobalSnapshotInfo.empty.copy(
          balances = SortedMap(stakingAddress -> input.balance),
          lastCurrencySnapshots = lastCurrencySnapshots
        )
        calculator
          .calculateFee(stateChannelEvent, info, SnapshotOrdinal.MinValue)
          .map(actual => expect.same(input.expectedFeeWithBalance, actual))
    }
  }
}

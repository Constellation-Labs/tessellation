package org.tessellation.dag.l0.domain.snapshot.programs

import cats.syntax.option._
import cats.syntax.semigroup._

import scala.collection.immutable.TreeMap

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.node.shared.config.types.FeeCalculatorConfig
import org.tessellation.schema.GlobalSnapshotInfo
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.balance.Balance
import org.tessellation.security.hash.Hash
import org.tessellation.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric._
import weaver.SimpleMutableIOSuite
import weaver.scalacheck.Checkers

object SnapshotBinaryFeeCalculatorSuite extends SimpleMutableIOSuite with Checkers {
  val sampleAddress = Address(refineV[DAGAddressRefined].unsafeFrom("DAG7coCMRPJah33MMcfAEZVeB1vYn3vDRe6WqeGU"))
  val getAddress = () => sampleAddress.some

  val minConfig: FeeCalculatorConfig = FeeCalculatorConfig(
    baseFee = PosInt.MinValue,
    computationalCost = PosInt.MinValue,
    stakingWeight = PosDouble(0.0000002),
    stakedDag = NonNegInt.MinValue,
    proScore = NonNegInt.MinValue,
    proWeight = NonNegInt.MinValue
  )

  val mixConfig: FeeCalculatorConfig = FeeCalculatorConfig(
    baseFee = PosInt(11),
    computationalCost = PosInt(3),
    stakingWeight = PosDouble(0.0000002),
    stakedDag = NonNegInt(13),
    proScore = NonNegInt.MinValue,
    proWeight = NonNegInt.MinValue
  )

  case class SampleInput(
    binaryContentLength: Int,
    binaryFee: SnapshotFee,
    config: FeeCalculatorConfig,
    balance: Balance,
    expectedCalculatedFee: Long
  )

  val lessThanOneKb = 999
  val moreThanOneKb = 123_999
  val sampleInputs = List(
    SampleInput(lessThanOneKb, SnapshotFee.MinValue, minConfig, Balance.empty, 0L),
    SampleInput(lessThanOneKb, SnapshotFee.MinValue, mixConfig, Balance.empty, 32L),
    SampleInput(lessThanOneKb, SnapshotFee.MinValue, minConfig, Balance(NonNegLong.MaxValue), 0L),
    SampleInput(lessThanOneKb, SnapshotFee.MinValue, mixConfig, Balance(NonNegLong.MaxValue), 0L),
    SampleInput(lessThanOneKb, SnapshotFee.MinValue, minConfig, Balance(NonNegLong(1234)), 0L),
    SampleInput(lessThanOneKb, SnapshotFee.MinValue, mixConfig, Balance(NonNegLong(1234)), 32L),
    SampleInput(lessThanOneKb, SnapshotFee(NonNegLong(123)), minConfig, Balance.empty, 125L),
    SampleInput(lessThanOneKb, SnapshotFee(NonNegLong(123)), mixConfig, Balance.empty, 147L),
    SampleInput(lessThanOneKb, SnapshotFee(NonNegLong(123)), minConfig, Balance(NonNegLong.MaxValue), 125L),
    SampleInput(lessThanOneKb, SnapshotFee(NonNegLong(123)), mixConfig, Balance(NonNegLong.MaxValue), 115L),
    SampleInput(lessThanOneKb, SnapshotFee(NonNegLong(123)), minConfig, Balance(NonNegLong(1234)), 125L),
    SampleInput(lessThanOneKb, SnapshotFee(NonNegLong(123)), mixConfig, Balance(NonNegLong(1234)), 147L),
    SampleInput(moreThanOneKb, SnapshotFee.MinValue, minConfig, Balance.empty, 121L),
    SampleInput(moreThanOneKb, SnapshotFee.MinValue, mixConfig, Balance.empty, 3996L),
    SampleInput(moreThanOneKb, SnapshotFee.MinValue, minConfig, Balance(NonNegLong.MaxValue), 0L),
    SampleInput(moreThanOneKb, SnapshotFee.MinValue, mixConfig, Balance(NonNegLong.MaxValue), 0L),
    SampleInput(moreThanOneKb, SnapshotFee.MinValue, minConfig, Balance(NonNegLong(1234)), 121L),
    SampleInput(moreThanOneKb, SnapshotFee.MinValue, mixConfig, Balance(NonNegLong(1234)), 3995L),
    SampleInput(moreThanOneKb, SnapshotFee(NonNegLong(123)), minConfig, Balance.empty, 121L),
    SampleInput(moreThanOneKb, SnapshotFee(NonNegLong(123)), mixConfig, Balance.empty, 3996L),
    SampleInput(moreThanOneKb, SnapshotFee(NonNegLong(123)), minConfig, Balance(NonNegLong.MaxValue), 0L),
    SampleInput(moreThanOneKb, SnapshotFee(NonNegLong(123)), mixConfig, Balance(NonNegLong.MaxValue), 0L),
    SampleInput(moreThanOneKb, SnapshotFee(NonNegLong(123)), minConfig, Balance(NonNegLong(1234)), 121L),
    SampleInput(moreThanOneKb, SnapshotFee(NonNegLong(123)), mixConfig, Balance(NonNegLong(1234)), 3995L)
  )

  def binary(input: SampleInput): StateChannelSnapshotBinary =
    StateChannelSnapshotBinary(
      lastSnapshotHash = Hash.empty,
      content = Array.fill(input.binaryContentLength)(Byte.MinValue),
      fee = input.binaryFee
    )

  pureTest("minimum value returned when address unavailable") {
    val input = sampleInputs.head
    val calculator = SnapshotBinaryFeeCalculator.make(input.config, () => None)
    val actual = calculator.calculateFee(
      binary(input),
      GlobalSnapshotInfo.empty.copy(balances = TreeMap(sampleAddress -> input.balance))
    )
    expect.same(Long.MinValue, actual)
  }

  pureTest("minimum value returned when balance unavailable") {
    val input = sampleInputs.head
    val calculator = SnapshotBinaryFeeCalculator.make(input.config, getAddress)
    val actual = calculator.calculateFee(
      binary(input),
      GlobalSnapshotInfo.empty
    )
    expect.same(Long.MinValue, actual)
  }

  pureTest("calculate from pre-defined inputs") {
    val expectations = for {
      input <- sampleInputs
      calculator = SnapshotBinaryFeeCalculator.make(input.config, getAddress)
      actual = calculator.calculateFee(
        binary(input),
        GlobalSnapshotInfo.empty.copy(balances = TreeMap(sampleAddress -> input.balance))
      )
    } yield expect.same(input.expectedCalculatedFee, actual)

    expectations.reduce(_ |+| _)
  }
}

package org.tessellation.sdk.infrastructure.snapshot

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.implicits.catsStdShowForList
import cats.syntax.all._
import cats.{Eq, Show}

import org.tessellation.currency.dataApplication.DataUpdate
import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.ext.kryo.{KryoRegistrationId, _}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generators.signedOf
import org.tessellation.schema.round.RoundId
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.sdk.domain.block.generators.signedBlockGen
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

@derive(show, eqv)
case class SampleDataUpdate(value: Double) extends DataUpdate {}

object CurrencyEventsCutterSuite extends MutableIOSuite with Checkers {
  type Res = KryoSerializer[IO]

  val dataApplicationBlockGen: Gen[Signed[DataApplicationBlock]] = for {
    roundId <- Gen.delay(RoundId(UUID.randomUUID()))
    value <- Gen.delay(Gen.double)
    updates <- Gen.nonEmptyListOf(signedOf(SampleDataUpdate(value))).map(NonEmptyList.fromListUnsafe)
    hashes = updates.map(_ => Hash.empty)
    signed <- signedOf[DataApplicationBlock](DataApplicationBlock(roundId, updates, hashes))
  } yield signed

  implicit val show: Show[DataApplicationBlock] = (t: DataApplicationBlock) => t.toString

  implicit def eqDataApplicationBlock(
    implicit eq: Eq[SampleDataUpdate],
    eqRoundId: Eq[RoundId],
    eqDataUpdate: Eq[NonEmptyList[Signed[SampleDataUpdate]]],
    eqHash: Eq[NonEmptyList[Hash]]
  ): Eq[Signed[DataApplicationBlock]] = Eq.instance {
    case (a, b) =>
      eqRoundId.eqv(a.roundId, b.roundId) &&
      eqDataUpdate
        .eqv(a.updates.map(_.asInstanceOf[Signed[SampleDataUpdate]]), b.updates.map(_.asInstanceOf[Signed[SampleDataUpdate]])) &&
      eqHash.eqv(a.updatesHashes, b.updatesHashes)
  }

  def sharedResource: Resource[IO, KryoSerializer[IO]] = {
    val registrar: Map[Class[_], KryoRegistrationId[Interval.Closed[999, 999]]] = Map(classOf[SampleDataUpdate] -> 999)
    KryoSerializer.forAsync[IO](sdkKryoRegistrar ++ registrar)
  }

  test("cuts data block if more data blocks than blocks") { implicit kryo =>
    val cutter = CurrencyEventsCutter.make[IO]

    val gen = for {
      dataBlockA <- dataApplicationBlockGen
      dataBlockB <- dataApplicationBlockGen
      dataBlockC <- dataApplicationBlockGen
      blockA <- signedBlockGen
      blockB <- signedBlockGen
      dataBlocks = List(dataBlockA, dataBlockB, dataBlockC)
      blocks = List(blockA, blockB)
    } yield (dataBlocks, blocks)

    forall(gen) {
      case (dataBlocks, blocks) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, dataBlocks).map { result =>
          expect.eql(
            result match {
              case Some((remaining, rejected)) =>
                dataBlocks.last.asRight[Signed[Block]] === rejected &&
                remaining === dataBlocks.map(_.asRight[Signed[Block]]).toSet.filterNot(_ === rejected)
              case None => false
            },
            true
          )
        }
    }
  }

  test("cuts block if more blocks than data blocks") { implicit kryo =>
    val cutter = CurrencyEventsCutter.make[IO]

    val gen = for {
      dataBlockA <- dataApplicationBlockGen
      dataBlockB <- dataApplicationBlockGen
      blockA <- signedBlockGen
      blockB <- signedBlockGen
      blockC <- signedBlockGen
      dataBlocks = List(dataBlockA, dataBlockB)
      blocks = List(blockA, blockB, blockC)
    } yield (dataBlocks, blocks)

    forall(gen) {
      case (dataBlocks, blocks) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, dataBlocks).map { result =>
          expect.eql(
            result match {
              case Some((remaining, rejected)) =>
                blocks.last.asLeft[Signed[DataApplicationBlock]] === rejected &&
                remaining === blocks.map(_.asLeft[Signed[DataApplicationBlock]]).toSet.filterNot(_ === rejected)
              case None => false
            },
            true
          )
        }
    }
  }

  test("cuts bigger block in case of tie-breaker event") { implicit kryo =>
    val cutter = CurrencyEventsCutter.make[IO]

    val gen = for {
      n <- Gen.chooseNum(1, 100)
      dataBlocks <- Gen.listOfN(n, dataApplicationBlockGen)
      blocks <- Gen.listOfN(n, signedBlockGen)
    } yield (dataBlocks, blocks)

    forall(gen) {
      case (dataBlocks, blocks) =>
        for {
          dataBlockSize: Int <- dataBlocks.lastOption.map(_.toBinaryF.map(_.length)).getOrElse(0.pure[IO])
          blockSize: Int <- blocks.lastOption.map(_.toBinaryF.map(_.length)).getOrElse(0.pure[IO])
          result <- cutter.cut(SnapshotOrdinal.MinValue, blocks, dataBlocks)
          expected = result match {
            case Some((remaining, rejected)) =>
              if (dataBlockSize > blockSize)
                dataBlocks.last.asRight[Signed[Block]] === rejected && remaining === dataBlocks
                  .map(_.asRight[Signed[Block]])
                  .toSet
                  .filterNot(_ === rejected)
              else if (dataBlockSize < blockSize)
                blocks.last.asLeft[Signed[DataApplicationBlock]] === rejected && remaining === blocks
                  .map(_.asLeft[Signed[DataApplicationBlock]])
                  .toSet
                  .filterNot(_ === rejected)
              else true
            case None => false
          }
        } yield expect.same(true, expected)
    }
  }

  test("returns none if both collections empty") { implicit kryo =>
    val cutter = CurrencyEventsCutter.make[IO]

    val gen = for {
      dataBlocks <- Gen.listOfN(0, dataApplicationBlockGen)
      blocks <- Gen.listOfN(0, signedBlockGen)
    } yield (dataBlocks, blocks)

    forall(gen) {
      case (dataBlocks, blocks) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, dataBlocks).map { result =>
          expect.eql(
            result.isEmpty,
            true
          )
        }
    }
  }

  test("returns none if cutting makes both collections empty") { implicit kryo =>
    val cutter = CurrencyEventsCutter.make[IO]

    val gen = for {
      dataBlocksLength <- Gen.chooseNum(0, 1)
      blocksLength = 1 - dataBlocksLength
      dataBlocks <- Gen.listOfN(dataBlocksLength, dataApplicationBlockGen)
      blocks <- Gen.listOfN(blocksLength, signedBlockGen)
    } yield (dataBlocks, blocks)

    forall(gen) {
      case (dataBlocks, blocks) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, dataBlocks).map { result =>
          expect.eql(
            result.isEmpty,
            true
          )
        }
    }
  }

}

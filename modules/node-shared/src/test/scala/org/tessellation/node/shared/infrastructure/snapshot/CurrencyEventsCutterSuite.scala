package org.tessellation.node.shared.infrastructure.snapshot

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.implicits.catsStdShowForList
import cats.syntax.all._
import cats.{Eq, Show}

import org.tessellation.currency.dataApplication._
import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency
import org.tessellation.ext.cats.effect._
import org.tessellation.json.JsonSerializer
import org.tessellation.node.shared.domain.block.generators.signedBlockGen
import org.tessellation.routes.internal
import org.tessellation.schema.generators.signedOf
import org.tessellation.schema.round.RoundId
import org.tessellation.schema.{Block, SnapshotOrdinal}
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import org.http4s.{EntityDecoder, HttpRoutes}
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

@derive(show, eqv, encoder)
case class SampleDataUpdate(value: Double) extends DataUpdate

object CurrencyEventsCutterSuite extends MutableIOSuite with Checkers {
  type Res = JsonSerializer[IO]

  val dataApplicationBlockGen: Gen[Signed[DataApplicationBlock]] = for {
    roundId <- Gen.delay(RoundId(UUID.randomUUID()))
    value <- Gen.delay(Gen.double)
    updates <- Gen.nonEmptyListOf(signedOf(SampleDataUpdate(value))).map(NonEmptyList.fromListUnsafe)
    hashes = updates.map(_ => Hash.empty)
    signed <- signedOf[DataApplicationBlock](DataApplicationBlock(roundId, updates, hashes))
  } yield signed

  implicit val show: Show[DataApplicationBlock] = (t: DataApplicationBlock) => t.toString

  implicit def eqDataApplicationBlock(
    implicit eqRoundId: Eq[RoundId],
    eqDataUpdate: Eq[NonEmptyList[Signed[SampleDataUpdate]]],
    eqHash: Eq[NonEmptyList[Hash]]
  ): Eq[Signed[DataApplicationBlock]] = Eq.instance {
    case (a, b) =>
      eqRoundId.eqv(a.roundId, b.roundId) &&
      eqDataUpdate
        .eqv(a.updates.map(_.asInstanceOf[Signed[SampleDataUpdate]]), b.updates.map(_.asInstanceOf[Signed[SampleDataUpdate]])) &&
      eqHash.eqv(a.updatesHashes, b.updatesHashes)
  }

  def sharedResource: Resource[IO, JsonSerializer[IO]] =
    JsonSerializer.forSync[F].asResource

  test("cuts data block if more data blocks than blocks") { implicit j =>
    val cutter = CurrencyEventsCutter.make[IO](testDataApplication.some)

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

  test("cuts block if more blocks than data blocks") { implicit j =>
    val cutter = CurrencyEventsCutter.make[IO](testDataApplication.some)

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

  test("cuts block if data application not provided") { implicit j =>
    val cutter = CurrencyEventsCutter.make[IO](None)

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
                blocks.last.asLeft[Signed[DataApplicationBlock]] === rejected &&
                remaining === blocks.map(_.asLeft[Signed[DataApplicationBlock]]).toSet.filterNot(_ === rejected)
              case None => false
            },
            true
          )
        }
    }
  }

  test("cuts bigger block in case of tie-breaker event") { implicit j =>
    val cutter = CurrencyEventsCutter.make[IO](testDataApplication.some)

    val gen = for {
      n <- Gen.chooseNum(1, 100)
      dataBlocks <- Gen.listOfN(n, dataApplicationBlockGen)
      blocks <- Gen.listOfN(n, signedBlockGen)
    } yield (dataBlocks, blocks)

    implicit val dataUpdateEncoder: Encoder[DataUpdate] = testDataApplication.dataEncoder
    implicit val dataBlockEncoder = DataApplicationBlock.encoder

    forall(gen) {
      case (dataBlocks, blocks) =>
        for {
          dataBlockSize: Int <- dataBlocks.lastOption.map(a => JsonSerializer[IO].serialize(a).map(_.length)).getOrElse(0.pure[IO])
          blockSize: Int <- blocks.lastOption.map(a => JsonSerializer[IO].serialize(a).map(_.length)).getOrElse(0.pure[IO])
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

  test("returns none if both collections empty") { implicit j =>
    val cutter = CurrencyEventsCutter.make[IO](testDataApplication.some)

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

  test("returns none if cutting makes both collections empty") { implicit j =>
    val cutter = CurrencyEventsCutter.make[IO](testDataApplication.some)

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

  val testDataApplication: BaseDataApplicationL0Service[IO] = new BaseDataApplicationL0Service[IO] {

    override def serializeState(state: DataOnChainState): IO[Array[Byte]] = ???

    override def deserializeState(bytes: Array[Byte]): IO[Either[Throwable, DataOnChainState]] = ???

    override def serializeUpdate(update: DataUpdate): IO[Array[Byte]] = ???

    override def deserializeUpdate(bytes: Array[Byte]): IO[Either[Throwable, DataUpdate]] = ???

    override def serializeBlock(block: Signed[DataApplicationBlock]): IO[Array[Byte]] = ???

    override def deserializeBlock(bytes: Array[Byte]): IO[Either[Throwable, Signed[DataApplicationBlock]]] = ???

    override def serializeCalculatedState(state: DataCalculatedState): IO[Array[Byte]] = ???

    override def deserializeCalculatedState(bytes: Array[Byte]): IO[Either[Throwable, DataCalculatedState]] = ???

    override def dataEncoder: Encoder[DataUpdate] = new Encoder[DataUpdate] {
      final def apply(a: DataUpdate): Json = a match {
        case data: SampleDataUpdate => data.asJson
        case _                      => Json.Null
      }

    }

    override def dataDecoder: Decoder[DataUpdate] = ???

    override def signedDataEntityDecoder: EntityDecoder[IO, Signed[DataUpdate]] = ???

    override def calculatedStateEncoder: Encoder[DataCalculatedState] = ???

    override def calculatedStateDecoder: Decoder[DataCalculatedState] = ???

    override def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
      implicit context: L0NodeContext[IO]
    ): IO[dataApplication.DataApplicationValidationErrorOr[Unit]] = ???

    override def validateUpdate(update: DataUpdate)(
      implicit context: L0NodeContext[IO]
    ): IO[dataApplication.DataApplicationValidationErrorOr[Unit]] = ???

    override def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
      implicit context: L0NodeContext[IO]
    ): IO[DataState.Base] = ???

    override def getCalculatedState(implicit context: L0NodeContext[IO]): IO[(SnapshotOrdinal, DataCalculatedState)] = ???

    override def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(
      implicit context: L0NodeContext[IO]
    ): IO[Boolean] = ???

    override def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[IO]): IO[Hash] = ???

    override def routes(implicit context: L0NodeContext[IO]): HttpRoutes[IO] = ???

    override def routesPrefix: internal.ExternalUrlPrefix = ???

    override def genesis: DataState.Base = ???

    override def onSnapshotConsensusResult(snapshot: Hashed[currency.CurrencyIncrementalSnapshot]): IO[Unit] = ???

  }

}

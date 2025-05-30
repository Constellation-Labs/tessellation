package io.constellationnetwork.node.shared.infrastructure.snapshot

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.{Async, IO, Resource}
import cats.implicits.catsStdShowForList
import cats.syntax.all._
import cats.{Eq, Show}

import scala.collection.immutable.SortedSet

import io.constellationnetwork.block.generators.{signedBlockGen, signedTokenLockBlockGen}
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.schema.currency
import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.routes.internal
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import io.constellationnetwork.schema.generators.{addressGen, signedOf}
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
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
    formattedUpdates = updates.map(t => NonEmptyList.of(t))
    hashes = updates.map(_ => NonEmptyList.of(Hash.empty))
    signed <- signedOf[DataApplicationBlock](DataApplicationBlock(roundId, formattedUpdates, hashes))
  } yield signed

  val currencyMessageGen: Gen[Signed[CurrencyMessage]] = for {
    messageType <- Gen.oneOf(MessageType.Owner, MessageType.Staking)
    address <- addressGen
    metagraphId <- addressGen
    signed <- signedOf[CurrencyMessage](CurrencyMessage(messageType, address, metagraphId, MessageOrdinal.MinValue))
  } yield signed

  implicit val show: Show[DataApplicationBlock] = (t: DataApplicationBlock) => t.toString

  implicit def eqDataApplicationBlock(
    implicit eqRoundId: Eq[RoundId],
    eqDataUpdate: Eq[NonEmptyList[Signed[SampleDataUpdate]]],
    eqHash: Eq[NonEmptyList[NonEmptyList[Hash]]]
  ): Eq[Signed[DataApplicationBlock]] = Eq.instance {
    case (a, b) =>
      eqRoundId.eqv(a.roundId, b.roundId) &&
      eqDataUpdate
        .eqv(
          a.dataTransactions.map(_.asInstanceOf[Signed[SampleDataUpdate]]),
          b.dataTransactions.map(_.asInstanceOf[Signed[SampleDataUpdate]])
        ) &&
      eqHash.eqv(a.dataTransactionsHashes, b.dataTransactionsHashes)
  }

  def sharedResource: Resource[IO, JsonSerializer[IO]] =
    JsonSerializer.forSync[F].asResource

  test("does not cut messages") { implicit j =>
    val cutter = CurrencyEventsCutter.make[IO](testDataApplication.some)

    val gen = for {
      messageA <- currencyMessageGen
      messages = List(messageA)
    } yield messages

    forall(gen) {
      case (messages) =>
        cutter.cut(SnapshotOrdinal.MinValue, List.empty, List.empty, List.empty, messages).map { result =>
          expect.eql(
            result match {
              case Some(_) => false
              case None    => true
            },
            true
          )
        }
    }
  }
  test("cuts data block if more data blocks than blocks") { implicit j =>
    val cutter = CurrencyEventsCutter.make[IO](testDataApplication.some)

    val gen = for {
      dataBlockA <- dataApplicationBlockGen
      dataBlockB <- dataApplicationBlockGen
      dataBlockC <- dataApplicationBlockGen
      blockA <- signedBlockGen
      blockB <- signedBlockGen
      messageA <- currencyMessageGen
      dataBlocks = List(dataBlockA, dataBlockB, dataBlockC)
      blocks = List(blockA, blockB)
      messages = List(messageA)
    } yield (dataBlocks, blocks, messages)

    forall(gen) {
      case (dataBlocks, blocks, messages) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, List.empty, dataBlocks, messages).map { result =>
          expect.eql(
            result match {
              case Some((remaining, rejected)) =>
                DataApplicationBlockEvent(dataBlocks.last).asInstanceOf[CurrencySnapshotEvent] === rejected &&
                remaining === (dataBlocks.map(DataApplicationBlockEvent(_)).widen[CurrencySnapshotEvent] ++ messages
                  .map(CurrencyMessageEvent(_))
                  .widen[CurrencySnapshotEvent]).toSet.filterNot(_ === rejected)
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
      messageA <- currencyMessageGen
      dataBlocks = List(dataBlockA, dataBlockB)
      blocks = List(blockA, blockB, blockC)
      messages = List(messageA)
    } yield (dataBlocks, blocks, messages)

    forall(gen) {
      case (dataBlocks, blocks, messages) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, List.empty, dataBlocks, messages).map { result =>
          expect.eql(
            result match {
              case Some((remaining, rejected)) =>
                BlockEvent(blocks.last).asInstanceOf[CurrencySnapshotEvent] === rejected &&
                remaining === (blocks.map(BlockEvent(_)).widen[CurrencySnapshotEvent].toSet ++ messages
                  .map(CurrencyMessageEvent(_))
                  .widen[CurrencySnapshotEvent]).filterNot(_ === rejected)
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
      messageA <- currencyMessageGen
      dataBlocks = List(dataBlockA, dataBlockB, dataBlockC)
      blocks = List(blockA, blockB)
      messages = List(messageA)
    } yield (dataBlocks, blocks, messages)

    forall(gen) {
      case (dataBlocks, blocks, messages) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, List.empty, dataBlocks, messages).map { result =>
          expect.eql(
            result match {
              case Some((remaining, rejected)) =>
                BlockEvent(blocks.last).asInstanceOf[CurrencySnapshotEvent] === rejected &&
                remaining === (blocks.map(BlockEvent(_)).widen[CurrencySnapshotEvent].toSet ++ messages
                  .map(CurrencyMessageEvent(_))
                  .widen[CurrencySnapshotEvent]).filterNot(_ === rejected)
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
      tokenLockBlocks <- Gen.listOfN(n, signedTokenLockBlockGen)
      messageA <- currencyMessageGen
      messages = List(messageA)
    } yield (dataBlocks, blocks, tokenLockBlocks, messages)

    implicit val dataUpdateEncoder: Encoder[DataTransaction] = DataTransaction.encoder(testDataApplication.dataEncoder)
    implicit val dataBlockEncoder = DataApplicationBlock.encoder

    forall(gen) {
      case (dataBlocks, blocks, tokenLockBlocks, messages) =>
        for {
          dataBlockSize: Int <- dataBlocks.lastOption.map(a => JsonSerializer[IO].serialize(a).map(_.length)).getOrElse(0.pure[IO])
          blockSize: Int <- blocks.lastOption.map(a => JsonSerializer[IO].serialize(a).map(_.length)).getOrElse(0.pure[IO])
          result <- cutter.cut(SnapshotOrdinal.MinValue, blocks, tokenLockBlocks, dataBlocks, messages)
          expected = result match {
            case Some((remaining, rejected)) =>
              if (dataBlockSize > blockSize)
                DataApplicationBlockEvent(dataBlocks.last).asInstanceOf[CurrencySnapshotEvent] === rejected && remaining === (dataBlocks
                  .map(DataApplicationBlockEvent(_))
                  .widen[CurrencySnapshotEvent]
                  .toSet ++ messages
                  .map(CurrencyMessageEvent(_))
                  .widen[CurrencySnapshotEvent])
                  .filterNot(_ === rejected)
              else if (dataBlockSize < blockSize)
                BlockEvent(blocks.last).asInstanceOf[CurrencySnapshotEvent] === rejected && remaining === (blocks
                  .map(BlockEvent(_))
                  .widen[CurrencySnapshotEvent]
                  .toSet ++ messages
                  .map(CurrencyMessageEvent(_))
                  .widen[CurrencySnapshotEvent])
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
      messageA <- currencyMessageGen
      messages = List(messageA)
    } yield (dataBlocks, blocks, messages)

    forall(gen) {
      case (dataBlocks, blocks, messages) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, List.empty, dataBlocks, messages).map { result =>
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
      messageA <- currencyMessageGen
      messages = List(messageA)
    } yield (dataBlocks, blocks, messages)

    forall(gen) {
      case (dataBlocks, blocks, messages) =>
        cutter.cut(SnapshotOrdinal.MinValue, blocks, List.empty, dataBlocks, messages).map { result =>
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

    override def signedDataEntityEncoder: EntityEncoder[IO, Signed[DataUpdate]] = ???

    override def signedDataEntityDecoder: EntityDecoder[IO, Signed[DataUpdate]] = ???

    override def calculatedStateEncoder: Encoder[DataCalculatedState] = ???

    override def calculatedStateDecoder: Decoder[DataCalculatedState] = ???

    override def validateData(state: DataState.Base, updates: NonEmptyList[Signed[DataUpdate]])(
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

    def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): IO[Unit] = ???

    override def getTokenUnlocks(
      state: DataState[DataOnChainState, DataCalculatedState]
    )(implicit context: L0NodeContext[IO], async: Async[IO], hasher: Hasher[IO]): IO[SortedSet[artifact.TokenUnlock]] = ???
  }

}

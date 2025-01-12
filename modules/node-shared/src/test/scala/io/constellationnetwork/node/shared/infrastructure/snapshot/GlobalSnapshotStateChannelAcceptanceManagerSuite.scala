package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.{NonEmptyChain, NonEmptyList}
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotStateChannelAcceptanceManagerSuite extends MutableIOSuite with Checkers {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotStateChannelAcceptanceManagerSuite.Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp)

  val address = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")

  test("valid state channels should be returned when ordinal doesn't exceed delay") { res =>
    implicit val (h, sp) = res

    for {
      stateChannelOutput1 <- mkStateChannelOutput(1, address, Hash("someHash").some)
      stateChannelOutput2 <- mkStateChannelOutput(2, address, Hash("someHash").some)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap(address -> Hash("someHash")))
      manager <- mkManager()
      result1 <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, List(stateChannelOutput1))
      result2 <- manager.accept(SnapshotOrdinal(10L), snapshotInfo, List(stateChannelOutput1, stateChannelOutput2))
      expected1 = (SortedMap.empty[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set(stateChannelOutput1))
      expected2 = (
        SortedMap.empty[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        Set(stateChannelOutput1, stateChannelOutput2)
      )
    } yield expect.same(expected1, result1) && expect.same(expected2, result2)

  }

  test("valid state channel should be accepted when ordinal exceeds delay") { res =>
    implicit val (h, sp) = res

    for {
      stateChannelOutput <- mkStateChannelOutput(1, address, Hash("someHash").some)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap(address -> Hash("someHash")))
      manager <- mkManager()
      _ <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, List(stateChannelOutput))
      result <- manager.accept(SnapshotOrdinal(11L), snapshotInfo, List(stateChannelOutput))
    } yield expect.same((SortedMap(address -> NonEmptyList.one(stateChannelOutput.snapshotBinary)), Set.empty), result)

  }

  test("invalid state channel should be returned if purge delay is not exceeded") { res =>
    implicit val (h, sp) = res

    for {
      stateChannelOutput <- mkStateChannelOutput(1, address, Some(Hash("unknown")))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
      manager <- mkManager()
      _ <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, List(stateChannelOutput))
      result <- manager.accept(SnapshotOrdinal(11L), snapshotInfo, List(stateChannelOutput))
      expected = (SortedMap.empty[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set(stateChannelOutput))
    } yield expect.same(expected, result)

  }

  test("invalid or yet un-processable state channel should be purged after purge delay is exceeded") { res =>
    implicit val (h, sp) = res

    for {
      stateChannelOutput <- mkStateChannelOutput(1, address, Some(Hash("unknown")))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
      manager <- mkManager(NonNegLong.MinValue, NonNegLong(4L))
      _ <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, List(stateChannelOutput))
      resultAt4 <- manager.accept(SnapshotOrdinal(4L), snapshotInfo, List(stateChannelOutput))
      expectedAt4 = (SortedMap.empty[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set(stateChannelOutput))
      resultAt5 <- manager.accept(SnapshotOrdinal(5L), snapshotInfo, List(stateChannelOutput))
      expectedAt5 = (SortedMap.empty[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set.empty[StateChannelOutput])

      getsReturnedAt4 = expect.same(expectedAt4, resultAt4)
      getsDiscardedAt5 = expect.same(expectedAt5, resultAt5)
    } yield getsReturnedAt4 && getsDiscardedAt5
  }

  test("valid state channel with more signatures should be preferred") { res =>
    implicit val (h, sp) = res

    for {
      stateChannelOutput1 <- mkStateChannelOutput(1, address, None)
      stateChannelOutput2 <- mkStateChannelOutput(2, address, None)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
      manager <- mkManager()
      _ <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, List(stateChannelOutput1))
      _ <- manager.accept(SnapshotOrdinal(10L), snapshotInfo, List(stateChannelOutput1, stateChannelOutput2))
      result <- manager.accept(SnapshotOrdinal(11L), snapshotInfo, List(stateChannelOutput1, stateChannelOutput2))
      expected = (SortedMap(address -> NonEmptyList.one(stateChannelOutput2.snapshotBinary)), Set.empty)
    } yield expect.same(expected, result)

  }

  test("valid state channel with more occurrences should be preferred") { res =>
    implicit val (h, sp) = res

    for {
      stateChannelOutput1 <- mkStateChannelOutput(1, address, None)
      stateChannelOutput2 <- mkStateChannelOutput(1, address, None, stateChannelOutput1.snapshotBinary.value.some)
      stateChannelOutput3 <- mkStateChannelOutput(1, address, None)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
      manager <- mkManager()
      _ <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, List(stateChannelOutput1, stateChannelOutput2, stateChannelOutput3))
      result <- manager.accept(SnapshotOrdinal(11L), snapshotInfo, List(stateChannelOutput1, stateChannelOutput2, stateChannelOutput3))
      expected1 = (SortedMap(address -> NonEmptyList.one(stateChannelOutput2.snapshotBinary)), Set.empty)
      expected2 = (SortedMap(address -> NonEmptyList.one(stateChannelOutput1.snapshotBinary)), Set.empty)
    } yield expect.same(expected1, result).xor(expect.same(expected2, result))

  }

  test("acceptance should return deterministic result given concurring proposals and reruns") { res =>
    implicit val (h, sp) = res

    def gen = for {
      numberOfBinaries <- Gen.chooseNum(2, 10)
      numberOfCalls <- Gen.chooseNum(2, 10)
      numberOfSignatures <- Gen.chooseNum(1, 5)
    } yield (numberOfBinaries, numberOfCalls, numberOfSignatures)

    forall(gen) {
      case (numberOfBinaries, numberOfCalls, numberOfSignatures) =>
        for {
          binary <- mkStateChannelSnapshotBinary(Hash.empty)
          stateChannelOutputs <- (1 to numberOfBinaries).toList.traverse(_ =>
            mkStateChannelOutput(numberOfSignatures, address, None, binary.some)
          )
          snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
          results <- (1 to numberOfCalls).toList.traverse { _ =>
            for {
              manager <- mkManager()
              _ <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, stateChannelOutputs)
              shuffledOutputs <- Random.scalaUtilRandom[IO].flatMap(_.shuffleList(stateChannelOutputs))
              result <- manager.accept(SnapshotOrdinal(11L), snapshotInfo, shuffledOutputs)
            } yield result
          }
          expectedUniqueResults = 1
        } yield
          expect.same(expectedUniqueResults, results.distinct.size) &&
            expect.same(NonEmptyList.one(binary), results.head._1(address).map(_.value)) &&
            expect.same(Set.empty, results.head._2)
    }
  }

  test("valid state channel with more signatures should be preferred over more occurrences") { res =>
    implicit val (h, sp) = res

    for {
      stateChannelOutput1 <- mkStateChannelOutput(1, address, None)
      stateChannelOutput2 <- mkStateChannelOutput(1, address, None, stateChannelOutput1.snapshotBinary.value.some)
      stateChannelOutput3 <- mkStateChannelOutput(2, address, None)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
      manager <- mkManager()
      _ <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, List(stateChannelOutput1, stateChannelOutput2, stateChannelOutput3))
      result <- manager.accept(SnapshotOrdinal(11L), snapshotInfo, List(stateChannelOutput1, stateChannelOutput2, stateChannelOutput3))
      expected = (SortedMap(address -> NonEmptyList.one(stateChannelOutput3.snapshotBinary)), Set.empty)
    } yield expect.same(expected, result)

  }

  test("valid state channels which form a chain should be accepted") { res =>
    implicit val (h, sp) = res

    for {
      output1 <- mkStateChannelOutput(1, address, None)
      output1Hash <- output1.snapshotBinary.toHashed.map(_.hash)
      output1Following1 <- mkStateChannelOutput(10, address, output1Hash.some)
      output2 <- mkStateChannelOutput(2, address, None)
      output2Hash <- output2.snapshotBinary.toHashed.map(_.hash)
      output2Following1 <- mkStateChannelOutput(5, address, output2Hash.some)
      output2Following2 <- mkStateChannelOutput(2, address, output2Hash.some)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
      manager <- mkManager()
      _ <- manager.accept(
        SnapshotOrdinal(1L),
        snapshotInfo,
        List(output1, output1Following1, output2, output2Following1, output2Following2)
      )
      result <- manager.accept(
        SnapshotOrdinal(11L),
        snapshotInfo,
        List(output1, output1Following1, output2, output2Following1, output2Following2)
      )
      expected = (SortedMap(address -> NonEmptyList.of(output2Following1.snapshotBinary, output2.snapshotBinary)), Set.empty)
    } yield expect.same(expected, result)
  }

  test("valid state channels should be accepted right away when pull delay is set to 0") { res =>
    implicit val (h, sp) = res

    for {
      output1 <- mkStateChannelOutput(1, address, None)
      output1Hash <- output1.snapshotBinary.toHashed.map(_.hash)
      output1Following1 <- mkStateChannelOutput(10, address, output1Hash.some)
      output2 <- mkStateChannelOutput(2, address, None)
      output2Hash <- output2.snapshotBinary.toHashed.map(_.hash)
      output2Following1 <- mkStateChannelOutput(5, address, output2Hash.some)
      output2Following2 <- mkStateChannelOutput(2, address, output2Hash.some)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
      manager <- mkManager(pullDelay = NonNegLong.MinValue)
      result <- manager.accept(
        SnapshotOrdinal(1L),
        snapshotInfo,
        List(output1, output1Following1, output2, output2Following1, output2Following2)
      )
      expected = (SortedMap(address -> NonEmptyList.of(output2Following1.snapshotBinary, output2.snapshotBinary)), Set.empty)
    } yield expect.same(expected, result)
  }

  test("valid state channel should be processed with purge delay set to 0") { res =>
    implicit val (h, sp) = res

    for {
      stateChannelOutput <- mkStateChannelOutput(1, address, Hash("someHash").some)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap(address -> Hash("someHash")))
      manager <- mkManager(NonNegLong.MinValue, NonNegLong.MinValue)
      result <- manager.accept(SnapshotOrdinal(1L), snapshotInfo, List(stateChannelOutput))
    } yield expect.same((SortedMap(address -> NonEmptyList.one(stateChannelOutput.snapshotBinary)), Set.empty), result)

  }

  test("state channel events that don't form a chain should be returned") { res =>
    implicit val (h, sp) = res

    for {
      correctChain <- mkChainOfStateChannelOutputs(1, address, 3L)
      (first, third) = (correctChain.head, correctChain.last)
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap.empty)
      manager <- mkManager(pullDelay = NonNegLong.MinValue)
      result <- manager.accept(
        SnapshotOrdinal(1L),
        snapshotInfo,
        List(first, third)
      )
      expected = (SortedMap(address -> NonEmptyList.one(first.snapshotBinary)), Set(third))
    } yield expect.eql(expected, result)
  }

  private def mkManager(pullDelay: NonNegLong = NonNegLong(10L), purgeDelay: NonNegLong = NonNegLong(4L))(
    implicit h: Hasher[IO]
  ) =
    GlobalSnapshotStateChannelAcceptanceManager.make[IO](None, pullDelay = pullDelay, purgeDelay = purgeDelay)

  private def mkChainOfStateChannelOutputs(
    howManySigners: Int,
    address: Address,
    chainLength: PosLong
  )(implicit S: SecurityProvider[IO], H: Hasher[IO]): IO[NonEmptyChain[StateChannelOutput]] =
    mkStateChannelOutput(howManySigners, address)
      .map(NonEmptyChain.one)
      .flatMap { initialOutput =>
        initialOutput.tailRecM {
          case outputs if outputs.size >= chainLength =>
            outputs.asRight[NonEmptyChain[StateChannelOutput]].pure[IO]
          case outputs =>
            outputs.last.snapshotBinary.toHashed
              .map(_.hash.some)
              .flatMap(mkStateChannelOutput(howManySigners, address, _))
              .map(outputs.append(_).asLeft[NonEmptyChain[StateChannelOutput]])
        }
      }

  private def mkStateChannelOutput(
    howManySigners: Int,
    address: Address,
    hash: Option[Hash] = None,
    givenBinary: Option[StateChannelSnapshotBinary] = None
  )(
    implicit S: SecurityProvider[IO],
    H: Hasher[IO]
  ) = for {
    binary <- givenBinary.fold(mkStateChannelSnapshotBinary(hash.getOrElse(Hash.empty)))(_.pure[IO])
    keyPairs <- (1 to howManySigners).toList.traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    signedSC <- forAsyncHasher(givenBinary.getOrElse(binary), keyPairs.head).flatMap(signedSingle =>
      keyPairs.tail.foldM(signedSingle)((signed, keyPair) => signed.signAlsoWith(keyPair))
    )
  } yield StateChannelOutput(address, signedSC)

  private def mkStateChannelSnapshotBinary(hash: Hash) = Random
    .scalaUtilRandom[IO]
    .flatMap(_.nextString(10))
    .map(content => StateChannelSnapshotBinary(hash, content.getBytes, SnapshotFee.MinValue))

  private def mkGlobalSnapshotInfo(lastStateChannelSnapshotHashes: SortedMap[Address, Hash]) =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      None,
      None,
      None
    )

}

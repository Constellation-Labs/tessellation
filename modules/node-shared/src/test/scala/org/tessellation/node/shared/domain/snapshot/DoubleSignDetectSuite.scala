package org.tessellation.node.shared.domain.snapshot

import cats.effect.IO
import cats.syntax.all._

import org.tessellation.node.shared.config.types.DoubleSignDetectConfig
import org.tessellation.node.shared.domain.fork._
import org.tessellation.schema.generators.peerIdGen
import org.tessellation.schema.{SnapshotOrdinal, peer}
import org.tessellation.security.hash.Hash

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.numeric.{PosInt, PosLong}
import org.scalacheck.{Arbitrary, Gen}
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object DoubleSignDetectSuite extends SimpleIOSuite with Checkers {

  def forkInfoGen(ordinal: Long): Gen[ForkInfo] =
    Arbitrary.arbitrary[Hash].map(h => ForkInfo(SnapshotOrdinal.unsafeApply(ordinal), h))

  def listOfForkInfoGen(startingOrdinal: Long = 0, min: Int = 0, max: Int = 5): Gen[List[ForkInfo]] =
    for {
      n <- Gen.chooseNum(min, max)
      infos <- Gen.sequence[List[ForkInfo], ForkInfo](List.range(startingOrdinal, startingOrdinal + n).map(forkInfoGen))
    } yield infos

  def forkEntriesGen(target: ForkInfo, conflict: ForkInfo, minSpacedBy: PosInt, maxSpacedBy: PosInt): Gen[ForkInfoEntries] =
    for {
      start <- listOfForkInfoGen()
      space <- listOfForkInfoGen(startingOrdinal = 100, min = minSpacedBy, max = maxSpacedBy)
      end <- listOfForkInfoGen(startingOrdinal = 200)

      infos = start ++ (target :: space) ++ (conflict :: end)
      entries = ForkInfoEntries.from(Refined.unsafeApply(infos.size + 1), infos)
    } yield entries

  def mkMockForkInfoStorage: ForkInfoStorage[IO] = new ForkInfoStorage[IO] {
    def add(peerId: peer.PeerId, entry: ForkInfo): IO[Unit] = ().pure

    override def getForkInfo: IO[ForkInfoMap] =
      ForkInfoMap(Map.empty).pure
  }

  def mkDoubleSignDetect(threshold: PosLong): DoubleSignDetect[IO] =
    new DoubleSignDetect[IO](mkMockForkInfoStorage, DoubleSignDetectConfig(threshold))

  test("not double sign if not within threshold") {
    val target = ForkInfo(SnapshotOrdinal(1000L), Hash("a"))
    val conflict = ForkInfo(SnapshotOrdinal(1000L), Hash("e"))

    val threshold: PosInt = 3

    val gen = for {
      peerId <- peerIdGen
      entries <- forkEntriesGen(target, conflict, threshold, Refined.unsafeApply(threshold + 1))
    } yield (peerId, entries)

    forall(gen) {
      case (peerId, entries) =>
        val detect = mkDoubleSignDetect(Refined.unsafeApply[Long, Positive](threshold.toLong))

        val actual = detect.validate(peerId, entries)

        expect(actual.isEmpty)
    }
  }

  test("is double sign if within threshold") {
    val target = ForkInfo(SnapshotOrdinal(1000L), Hash("a"))
    val conflict = ForkInfo(SnapshotOrdinal(1000L), Hash("e"))

    val threshold: PosInt = 3

    val gen = for {
      peerId <- peerIdGen
      entries <- forkEntriesGen(target, conflict, 1, Refined.unsafeApply(threshold - 1))
    } yield (peerId, entries)

    forall(gen) {
      case (peerId, entries) =>
        val detect = mkDoubleSignDetect(Refined.unsafeApply[Long, Positive](threshold.toLong))

        val actual = detect.validate(peerId, entries)

        expect.all(
          peerId.some === actual.map(_.peerId),
          SnapshotOrdinal(1000L) === actual.map(_.ordinal),
          actual.map(d => d.delta <= threshold && d.delta > 1).exists(_ === true),
          List(Hash("a"), Hash("e")).some === actual.map(_.hashes.toList)
        )
    }
  }

  test("is not double sign if hashes match") {
    val target = ForkInfo(SnapshotOrdinal(1000L), Hash("a"))
    val conflict = ForkInfo(SnapshotOrdinal(1000L), Hash("a"))

    val threshold: PosInt = 3

    val gen = for {
      peerId <- peerIdGen
      entries <- forkEntriesGen(target, conflict, 1, Refined.unsafeApply(threshold - 1))
    } yield (peerId, entries)

    forall(gen) {
      case (peerId, entries) =>
        val detect = mkDoubleSignDetect(Refined.unsafeApply[Long, Positive](threshold.toLong))

        val expected: Option[DetectedDoubleSign] = none
        val actual = detect.validate(peerId, entries)

        expect.eql(expected, actual)
    }
  }

}

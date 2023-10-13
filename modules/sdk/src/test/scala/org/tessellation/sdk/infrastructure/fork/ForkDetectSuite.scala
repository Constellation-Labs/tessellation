package org.tessellation.sdk.infrastructure.fork

import cats.effect.IO
import cats.syntax.applicative._

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.TrustScores
import org.tessellation.sdk.domain.fork._
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ForkDetectSuite extends SimpleIOSuite with Checkers {

  val trustScores: TrustScores = TrustScores(
    Map(
      PeerId(Hex("a")) -> 1.0,
      PeerId(Hex("b")) -> 0.75,
      PeerId(Hex("c")) -> 0.5,
      PeerId(Hex("d")) -> 0.25,
      PeerId(Hex("e")) -> 0.0,
      PeerId(Hex("f")) -> -0.25,
      PeerId(Hex("g")) -> -0.5
    )
  )

  val forkInfoMap: ForkInfoMap = ForkInfoMap(
    Map(
      PeerId(Hex("a")) -> ForkInfoEntries.from(
        5,
        List(
          ForkInfo(SnapshotOrdinal(10L), Hash("first")),
          ForkInfo(SnapshotOrdinal(11L), Hash("second")),
          ForkInfo(SnapshotOrdinal(12L), Hash("third"))
        )
      ),
      PeerId(Hex("b")) -> ForkInfoEntries.from(
        5,
        List(
          ForkInfo(SnapshotOrdinal(8L), Hash("first")),
          ForkInfo(SnapshotOrdinal(9L), Hash("second")),
          ForkInfo(SnapshotOrdinal(12L), Hash("fourth"))
        )
      ),
      PeerId(Hex("c")) -> ForkInfoEntries.from(
        5,
        List(
          ForkInfo(SnapshotOrdinal(10L), Hash("first")),
          ForkInfo(SnapshotOrdinal(11L), Hash("second")),
          ForkInfo(SnapshotOrdinal(12L), Hash("fourth"))
        )
      ),
      PeerId(Hex("e")) -> ForkInfoEntries.from(
        5,
        List(
          ForkInfo(SnapshotOrdinal(8L), Hash("first")),
          ForkInfo(SnapshotOrdinal(9L), Hash("second")),
          ForkInfo(SnapshotOrdinal(20L), Hash("third"))
        )
      ),
      PeerId(Hex("e")) -> ForkInfoEntries.from(
        5,
        List(
          ForkInfo(SnapshotOrdinal(8L), Hash("first")),
          ForkInfo(SnapshotOrdinal(9L), Hash("second")),
          ForkInfo(SnapshotOrdinal(20L), Hash("third"))
        )
      ),
      PeerId(Hex("f")) -> ForkInfoEntries.from(
        5,
        List(
          ForkInfo(SnapshotOrdinal(7L), Hash("first")),
          ForkInfo(SnapshotOrdinal(9L), Hash("second")),
          ForkInfo(SnapshotOrdinal(21L), Hash("third"))
        )
      ),
      PeerId(Hex("g")) -> ForkInfoEntries.from(
        5,
        List(
          ForkInfo(SnapshotOrdinal(7L), Hash("first")),
          ForkInfo(SnapshotOrdinal(9L), Hash("second")),
          ForkInfo(SnapshotOrdinal(21L), Hash("third"))
        )
      )
    )
  )

  def mkForkDetect(forks: ForkInfoMap = forkInfoMap): IO[ForkDetect[IO]] =
    ForkDetect.make[IO](trustScores.pure, forks.pure).pure

  test("negative trust scores are filtered out") {
    for {
      forkDetect <- mkForkDetect()

      expected = ForkInfo(SnapshotOrdinal(12L), Hash("fourth"))
      actual <- forkDetect.getMajorityFork
    } yield expect.eql(expected, actual)
  }

  test("the majority hash and ordinals are detected") {
    val forks = ForkInfoMap(
      Map(
        PeerId(Hex("a")) -> ForkInfoEntries.from(
          5,
          List(
            ForkInfo(SnapshotOrdinal(10L), Hash("first")),
            ForkInfo(SnapshotOrdinal(11L), Hash("second")),
            ForkInfo(SnapshotOrdinal(12L), Hash("third"))
          )
        ),
        PeerId(Hex("b")) -> ForkInfoEntries.from(
          5,
          List(
            ForkInfo(SnapshotOrdinal(8L), Hash("first")),
            ForkInfo(SnapshotOrdinal(9L), Hash("second")),
            ForkInfo(SnapshotOrdinal(12L), Hash("fourth"))
          )
        ),
        PeerId(Hex("c")) -> ForkInfoEntries.from(
          5,
          List(
            ForkInfo(SnapshotOrdinal(10L), Hash("first")),
            ForkInfo(SnapshotOrdinal(11L), Hash("second")),
            ForkInfo(SnapshotOrdinal(12L), Hash("fourth"))
          )
        )
      )
    )

    for {
      forkDetect <- mkForkDetect(forks)

      expected = ForkInfo(SnapshotOrdinal(12L), Hash("fourth"))
      actual <- forkDetect.getMajorityFork
    } yield expect.eql(expected, actual)
  }

}

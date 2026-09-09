package io.constellationnetwork.node.shared.infrastructure.gossip.event

import cats.effect.IO

import scala.concurrent.duration._

import weaver.SimpleIOSuite

/** Guards the B2 admission chain-tip witness coverage fix: on clusters larger than the gossip mesh, the witness sweep must still refresh
  * EVERY responsive peer's tip within the refresh window, or admission candidates outside the mesh are never witnessed (atTip=0) and Core
  * freezes below its floor.
  */
object ChainTipWitnessSweepSuite extends SimpleIOSuite {

  private val hb = 10.seconds
  private val refresh = 45.seconds // -> 4 heartbeats per refresh at hb=10s

  test("sweepSize scales to cover the cluster within the refresh window, floored at meshHigh") {
    IO {
      expect(ChainTipWitnessSweep.sweepSize(82, 12, hb, refresh) == 21, "82 peers -> ceil(82/4)=21")
        .and(expect(ChainTipWitnessSweep.sweepSize(20, 12, hb, refresh) == 12, "small deficit floored at meshHigh=12"))
        .and(expect(ChainTipWitnessSweep.sweepSize(5, 12, hb, refresh) == 5, "cluster below meshHigh witnesses everyone"))
        .and(expect(ChainTipWitnessSweep.sweepSize(0, 12, hb, refresh) == 0, "no peers -> 0"))
    }
  }

  test("round-robin witness sweep covers EVERY peer within ceil(n / sweepSize) heartbeats") {
    // (peerCount, sweepSize) pairs spanning small, mesh-sized, and large clusters.
    val cases = List((82, 21), (200, 50), (13, 13), (40, 12), (7, 7), (100, 25), (1, 1))
    IO {
      cases.map {
        case (n, size) =>
          val ordered = (0 until n).toVector
          val rounds = math.max(1, math.ceil(n.toDouble / size.toDouble).toInt)
          // Advance the cursor by `size` each round, exactly as runHeartbeat does.
          val covered = (0 until rounds)
            .foldLeft((Set.empty[Int], 0)) {
              case ((acc, cursor), _) =>
                val slice = ChainTipWitnessSweep.slice(ordered, cursor, size)
                (acc ++ slice, (cursor + size) % n)
            }
            ._1
          expect(covered.size == n, s"n=$n size=$size covered ${covered.size}/$n within $rounds heartbeats")
      }
        .reduce(_ and _)
    }
  }

  test("slice wraps in-bounds and never exceeds the peer set") {
    val ordered = (0 until 10).toVector
    IO {
      expect(ChainTipWitnessSweep.slice(ordered, 8, 4) == Vector(8, 9, 0, 1), "wraps past the end")
        .and(expect(ChainTipWitnessSweep.slice(ordered, 25, 3) == Vector(5, 6, 7), "cursor reduced mod size"))
        .and(expect(ChainTipWitnessSweep.slice(ordered, 0, 100).toSet == ordered.toSet, "size > n returns all, no OOB"))
        .and(expect(ChainTipWitnessSweep.slice(Vector.empty[Int], 3, 4).isEmpty, "empty set -> empty"))
    }
  }
}

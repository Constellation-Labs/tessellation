package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Order
import cats.effect.IO
import cats.syntax.all._

import io.chrisdavenport.mapref.MapRef
import weaver.SimpleIOSuite

/** Regression suite for the `pruneStaleResources` window contract.
  *
  * The real `ConsensusStorage.pruneStaleResources` requires many typeclass witnesses that make direct construction boilerplate-heavy; these
  * tests drive the same filter predicate against a `MapRef[IO, Long, Unit]` shaped identically to `resourcesR`, verifying the "keep active
  * key AND any future keys within the declaration window" invariant.
  *
  * Observed in E2E: pre-arrived Facility declarations for key=N+1 were being wiped when key=N completed, because the prune predicate was
  * `filterNot(_ === activeKey)` (drop everything except activeKey) instead of `filter(_ < activeKey)` (drop only strictly past rounds).
  * Combined with gossip first-write-wins semantics, pruned peers never retransmitted, leaving the new round with `progress=3/5 missing=2`
  * for minutes.
  */
object ConsensusStoragePruneSuite extends SimpleIOSuite {

  private def pruneStaleResources(resourcesR: MapRef[IO, Long, Option[Unit]], activeKey: Long): IO[Unit] =
    resourcesR.keys.flatMap { keys =>
      keys.filter(Order[Long].lt(_, activeKey)).traverse_(k => resourcesR(k).set(none))
    }

  private def seed(resourcesR: MapRef[IO, Long, Option[Unit]], keys: Long*): IO[Unit] =
    keys.toList.traverse_(k => resourcesR(k).set(().some))

  private def keySet(resourcesR: MapRef[IO, Long, Option[Unit]]): IO[Set[Long]] =
    resourcesR.keys.flatMap { ks =>
      ks.toList.traverseFilter(k => resourcesR(k).get.map(_.map(_ => k)))
    }.map(_.toSet)

  test("prune keeps the active key AND future keys, drops past keys") {
    MapRef.ofConcurrentHashMap[IO, Long, Unit]().flatMap { resourcesR =>
      for {
        _ <- seed(resourcesR, 5L, 6L, 7L, 8L, 9L)
        _ <- pruneStaleResources(resourcesR, activeKey = 7L)
        remaining <- keySet(resourcesR)
      } yield expect(remaining === Set(7L, 8L, 9L), s"expected {7,8,9} preserved, got $remaining")
    }
  }

  test("prune keeps future-only entries when no past keys exist") {
    MapRef.ofConcurrentHashMap[IO, Long, Unit]().flatMap { resourcesR =>
      for {
        _ <- seed(resourcesR, 10L, 11L, 12L)
        _ <- pruneStaleResources(resourcesR, activeKey = 10L)
        remaining <- keySet(resourcesR)
      } yield expect(remaining === Set(10L, 11L, 12L), s"expected {10,11,12} preserved, got $remaining")
    }
  }

  test("prune is a no-op when activeKey is below all existing entries") {
    MapRef.ofConcurrentHashMap[IO, Long, Unit]().flatMap { resourcesR =>
      for {
        _ <- seed(resourcesR, 100L, 101L)
        _ <- pruneStaleResources(resourcesR, activeKey = 50L)
        remaining <- keySet(resourcesR)
      } yield expect(remaining === Set(100L, 101L), s"expected both future keys preserved, got $remaining")
    }
  }

  test("prune drops all entries when activeKey is above all existing keys") {
    MapRef.ofConcurrentHashMap[IO, Long, Unit]().flatMap { resourcesR =>
      for {
        _ <- seed(resourcesR, 1L, 2L, 3L)
        _ <- pruneStaleResources(resourcesR, activeKey = 100L)
        remaining <- keySet(resourcesR)
      } yield expect(remaining === Set.empty, s"expected all keys dropped, got $remaining")
    }
  }

  test("prune preserves entries on idempotent re-invocation") {
    MapRef.ofConcurrentHashMap[IO, Long, Unit]().flatMap { resourcesR =>
      for {
        _ <- seed(resourcesR, 5L, 6L, 7L, 8L)
        _ <- pruneStaleResources(resourcesR, activeKey = 7L)
        _ <- pruneStaleResources(resourcesR, activeKey = 7L)
        remaining <- keySet(resourcesR)
      } yield expect(remaining === Set(7L, 8L), s"expected {7,8} stable across prunes, got $remaining")
    }
  }
}

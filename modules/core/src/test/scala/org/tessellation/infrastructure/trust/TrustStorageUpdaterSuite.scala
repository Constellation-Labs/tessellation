package org.tessellation.infrastructure.trust

import cats.effect.{IO, Ref}
import cats.syntax.applicative._
import cats.syntax.option._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.infrastructure.trust.generators.genPeerTrustInfo
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.trust.SnapshotOrdinalPublicTrust
import org.tessellation.sdk.config.types.TrustStorageConfig
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.trust.storage.{OrdinalTrustMap, TrustMap, TrustStorage}

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import io.circe.Encoder
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TrustStorageUpdaterSuite extends SimpleIOSuite with Checkers {

  def genData(minOrdinalValue: Long, maxOrdinalValue: Long): Gen[(TrustMap, SnapshotOrdinal)] =
    for {
      trust <- Gen.mapOfN(4, genPeerTrustInfo)
      delta <- Gen.chooseNum(minOrdinalValue, maxOrdinalValue)
      ordinal = Refined.unsafeApply[Long, NonNegative](delta)
    } yield (TrustMap.empty.copy(trust = trust), SnapshotOrdinal(ordinal))

  def mkTrustStorage(trust: TrustMap = TrustMap.empty): F[TrustStorage[F]] = {
    val config = TrustStorageConfig(
      ordinalTrustUpdateInterval = 1000L,
      ordinalTrustUpdateDelay = 500L
    )

    TrustStorage.make(trust, config)
  }

  def mkMockGossip[B](spreadRef: Ref[IO, List[B]]): Gossip[IO] = new Gossip[IO] {
    override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] =
      spreadRef.update(rumorContent.asInstanceOf[B] :: _)

    override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] =
      IO.raiseError(new Exception("spreadCommon: Unexpected call"))
  }

  def init(trust: TrustMap, ordinal: SnapshotOrdinal) =
    for {
      store <- mkTrustStorage(trust)
      gossipped <- Ref.of(List.empty[SnapshotOrdinalPublicTrust])
      mockGossip = mkMockGossip(gossipped)
      updater = TrustStorageUpdater.make(ordinal.some.pure, mockGossip, store)
    } yield (store, gossipped, updater)

  test("trust storages are updated") {
    val minOrdinalValue = (2L * 1000L) + 500L + 1L
    val maxOrdinalValue = (3L * 1000L) - 1L

    forall(genData(minOrdinalValue, maxOrdinalValue)) {
      case (trust, ordinal) =>
        for {
          (store, _, updater) <- init(trust, ordinal)

          _ <- store.updateNext(SnapshotOrdinal(1000L))
          _ <- updater.update

          current <- store.getCurrentOrdinalTrust
          next <- store.getNextOrdinalTrust
          expectedCurrent = OrdinalTrustMap.empty.copy(ordinal = SnapshotOrdinal(1000L), trust = trust)
          expectedNext = OrdinalTrustMap.empty.copy(ordinal = SnapshotOrdinal(2000L), trust = trust)
        } yield expect.eql((expectedCurrent, expectedNext), (current, next))
    }
  }

  test("snapshot ordinal public trust is gossiped after update") {
    val minOrdinalValue = (2L * 1000L) + 500L + 1L
    val maxOrdinalValue = (3L * 1000L) - 1L

    forall(genData(minOrdinalValue, maxOrdinalValue)) {
      case (trust, ordinal) =>
        for {
          (store, gossiped, updater) <- init(trust, ordinal)

          _ <- store.updateNext(SnapshotOrdinal(1000L))
          _ <- updater.update

          wasGossiped <- gossiped.get
          expectedGossip = SnapshotOrdinalPublicTrust(
            SnapshotOrdinal(2000L),
            trust.toPublicTrust
          )
        } yield expect.eql(List(expectedGossip), wasGossiped)
    }
  }
}

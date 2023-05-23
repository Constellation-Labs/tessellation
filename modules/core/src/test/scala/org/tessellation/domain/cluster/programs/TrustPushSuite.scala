package org.tessellation.domain.cluster.programs

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.option._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.domain.trust.storage.{SnapshotOrdinalTrustStorage, TrustStorage}
import org.tessellation.infrastructure.trust.TrustInfoGenerator.genPeerTrustInfo
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.generators.snapshotOrdinalGen
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._
import org.tessellation.sdk.domain.gossip.Gossip

import eu.timepit.refined.auto._
import io.circe.Encoder
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object TrustPushSuite extends SimpleIOSuite with Checkers {

  def mkTrustStorage(trusts: Map[PeerId, TrustInfo]): F[TrustStorage[F]] = for {
    trustRef <- Ref.of(trusts)
    storage = TrustStorage.make(trustRef)
  } yield storage

  def mkMockGossip(spreadRef: Ref[F, List[SnapshotOrdinalPublicTrust]]) = new Gossip[IO] {
    override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] =
      spreadRef.update(rumorContent.asInstanceOf[SnapshotOrdinalPublicTrust] :: _)

    override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] =
      IO.raiseError(new Exception("spreadCommon: Unexpected call"))

    def getGossiped = spreadRef.get
  }.pure[F]

  def mkMockSnapshotOrdinalTrustStorage(
    ordinalTrusts: Ref[F, Map[PeerId, SnapshotOrdinalTrustInfo]],
    ordinalPublicTrust: Ref[F, SnapshotOrdinalPublicTrust],
    ordinal: SnapshotOrdinal
  ) =
    new SnapshotOrdinalTrustStorage[F] {

      override def getTrust: F[Map[PeerId, SnapshotOrdinalTrustInfo]] = ordinalTrusts.get

      override def getSnapshotOrdinalPublicTrust: F[SnapshotOrdinalPublicTrust] = ordinalPublicTrust.get

      override def update(updates: Map[PeerId, TrustInfo]): F[SnapshotOrdinal] = ordinal.pure[F]
    }

  def mkOrdinalTrustRef(trust: Map[PeerId, TrustInfo], ordinal: SnapshotOrdinal) = {
    val ordinalTrust = trust.view.mapValues(SnapshotOrdinalTrustInfo(_, ordinal)).toMap

    Ref[F].of(ordinalTrust)
  }

  def mkOrdinalPublicTrustRef(trust: Map[PeerId, TrustInfo], ordinal: SnapshotOrdinal) = {
    val ordinalPublicTrustValues = trust.view.mapValues(_ => (ordinal, 1.0.some)).toMap
    val ordinalPublicTrust = SnapshotOrdinalPublicTrust(ordinalPublicTrustValues)

    Ref[F].of(ordinalPublicTrust)
  }

  test("successfully publish ordinal updates, trust updated") {
    val gen = for {
      ordinal <- snapshotOrdinalGen
      trusts <- Gen.mapOfN(4, genPeerTrustInfo)
    } yield (ordinal, trusts)

    forall(gen) {
      case (ordinal, trusts) =>
        for {
          spreadRef <- Ref[F].of[List[SnapshotOrdinalPublicTrust]](List.empty)
          gossip <- mkMockGossip(spreadRef)
          trustStorage <- mkTrustStorage(trusts)

          ordinalPublicTrustRef <- mkOrdinalPublicTrustRef(trusts, ordinal)
          ordinalTrustRef <- mkOrdinalTrustRef(trusts, ordinal)

          ordinalTrustStorage = mkMockSnapshotOrdinalTrustStorage(ordinalTrustRef, ordinalPublicTrustRef, ordinal)
          trustPush <- TrustPush.make(trustStorage, ordinalTrustStorage, gossip)

          _ <- trustPush.publishOrdinalUpdates

          expectedGossipped <- ordinalTrustStorage.getSnapshotOrdinalPublicTrust
          actualGossipped <- gossip.getGossiped

          expectedTargetOrdinal = ordinal.plus(trustPush.snapshotOrdinalTrustGossipInterval)
          actualTargetOrdinal <- trustPush.targetSnapshotOrdinal.get
        } yield
          expect.all(
            List(expectedGossipped) === actualGossipped,
            expectedTargetOrdinal === actualTargetOrdinal
          )
    }
  }

  test("successfully update ordinal trust, no gossip") {
    val gen = for {
      ordinal <- snapshotOrdinalGen
      trusts <- Gen.mapOfN(4, genPeerTrustInfo)
    } yield (ordinal, trusts)

    forall(gen) {
      case (ordinal, trusts) =>
        for {
          updatedOrdinal <- ordinal.plus(SnapshotOrdinal.MinValue.value).plus(10L).pure[F]
          spreadRef <- Ref[IO].of[List[SnapshotOrdinalPublicTrust]](List.empty)
          gossip <- mkMockGossip(spreadRef)
          trustStorage <- mkTrustStorage(trusts)

          ordinalTrustRef <- mkOrdinalTrustRef(trusts, ordinal)
          ordinalPublicTrustRef <- mkOrdinalPublicTrustRef(trusts, ordinal)
          ordinalTrustStorage = mkMockSnapshotOrdinalTrustStorage(
            ordinalTrustRef,
            ordinalPublicTrustRef,
            SnapshotOrdinal.MinValue
          )

          ordinalRef <- Ref[IO].of[SnapshotOrdinal](updatedOrdinal)
          trustPush <- TrustPush.make(ordinalRef, trustStorage, ordinalTrustStorage, gossip)

          _ <- trustPush.publishOrdinalUpdates

          expectedGossipped = List.empty[SnapshotOrdinalPublicTrust]
          actualGossipped <- gossip.getGossiped

          expectedTargetOrdinal <- ordinalRef.get
          actualTargetOrdinal <- trustPush.targetSnapshotOrdinal.get
        } yield
          expect.all(
            expectedGossipped === actualGossipped,
            expectedTargetOrdinal === actualTargetOrdinal
          )
    }
  }
}

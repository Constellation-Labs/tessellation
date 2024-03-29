package org.tessellation.currency.l0.snapshot.services

import cats.data.NonEmptyList
import cats.effect.kernel.Resource
import cats.effect.std.Random
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.generators.{addressGen, signedOf}
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.security._
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegInt
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object SentStateChannelBinaryTrackingServiceSuite extends MutableIOSuite with Checkers {

  type Res = Hasher[IO]

  val hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash }

  override def sharedResource: Resource[IO, SentStateChannelBinaryTrackingServiceSuite.Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    implicit0(js: JsonSerializer[IO]) <- Resource.liftK[IO](JsonSerializer.forSync[IO])
    hs = Hasher.forSync[IO](hashSelect)
  } yield hs

  def createGlobalIncrementalSnapshot(identifier: Address, binaries: List[Signed[StateChannelSnapshotBinary]])(
    implicit hs: Hasher[IO]
  ): IO[GlobalIncrementalSnapshot] =
    GlobalIncrementalSnapshot.fromGlobalSnapshot(
      GlobalSnapshot
        .mkGenesis(Map.empty, EpochProgress.MinValue)
        .copy(
          stateChannelSnapshots = NonEmptyList
            .fromList(binaries)
            .map(binaries => SortedMap(identifier -> binaries))
            .getOrElse(SortedMap.empty[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]])
        ),
      hashSelect
    )

  def mkService(identifier: Address)(
    implicit hs: Hasher[IO]
  ): IO[(SentStateChannelBinaryTrackingService[IO], Ref[IO, List[(Hashed[StateChannelSnapshotBinary], NonNegInt)]])] =
    Ref
      .of[IO, List[(Hashed[StateChannelSnapshotBinary], NonNegInt)]](List.empty)
      .flatMap { pendingR =>
        IdentifierStorage.make[IO].flatMap { identifierStorage =>
          identifierStorage.setInitial(identifier).as {
            val service = SentStateChannelBinaryTrackingService.make[IO](pendingR, identifierStorage)
            (service, pendingR)
          }
        }
      }

  val gen: Gen[(List[Signed[StateChannelSnapshotBinary]], Address)] = for {
    binaries <- Gen.nonEmptyListOf(signedOf(arbitrary[StateChannelSnapshotBinary]))
    address <- addressGen
  } yield (binaries, address)

  test("stores and clears pending binaries") { implicit hs =>
    forall(gen) {
      case (binaries, identifier) =>
        for {
          (service, pendingR) <- mkService(identifier)
          shuffled <- Random.scalaUtilRandom[IO].flatMap(_.shuffleList(binaries))
          _ <- shuffled.traverse(service.setPending)
          snapshot <- createGlobalIncrementalSnapshot(identifier, shuffled)
          _ <- service.updateByGlobalSnapshot(snapshot)
          pending <- pendingR.get
          toRetry <- service.getRetriable
        } yield
          expect.all(
            pending.isEmpty,
            toRetry.isEmpty
          )
    }
  }

  test("stores and clears older pending binaries when newer is confirmed") { implicit hs =>
    forall(gen) {
      case (binaries, identifier) =>
        for {
          (service, pendingR) <- mkService(identifier)
          shuffled <- Random.scalaUtilRandom[IO].flatMap(_.shuffleList(binaries))
          _ <- shuffled.traverse(service.setPending)
          snapshot <- createGlobalIncrementalSnapshot(identifier, List(shuffled.last))
          _ <- service.updateByGlobalSnapshot(snapshot)
          pending <- pendingR.get
          toRetry <- service.getRetriable
        } yield
          expect.all(
            pending.isEmpty,
            toRetry.isEmpty
          )
    }
  }

  test("increments checks for pending binaries") { implicit hs =>
    forall(gen) {
      case (binaries, identifier) =>
        for {
          (service, pendingR) <- mkService(identifier)
          shuffled <- Random.scalaUtilRandom[IO].flatMap(_.shuffleList(binaries)).flatMap(_.traverse(_.toHashed))
          _ <- shuffled.traverse(s => service.setPending(s.signed))
          prevPending <- pendingR.get
          snapshot1 <- createGlobalIncrementalSnapshot(identifier, List.empty)
          _ <- service.updateByGlobalSnapshot(snapshot1)
          newPending <- pendingR.get
        } yield
          expect.eql(
            newPending,
            prevPending.map { case (binary, _) => (binary, NonNegInt(1)) }
          )
    }
  }

  test("returns binaries to retry in FIFO manner") { implicit hs =>
    forall(gen) {
      case (binaries, identifier) =>
        for {
          (service, _) <- mkService(identifier)
          shuffled <- Random.scalaUtilRandom[IO].flatMap(_.shuffleList(binaries))
          _ <- shuffled.traverse(service.setPending)
          snapshot1 <- createGlobalIncrementalSnapshot(identifier, List.empty)
          _ <- service.updateByGlobalSnapshot(snapshot1) >> service.updateByGlobalSnapshot(snapshot1) >> service
            .updateByGlobalSnapshot(snapshot1)
          retriable <- service.getRetriable
        } yield expect.eql(retriable, shuffled)
    }
  }
}

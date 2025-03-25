package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.{IO, Resource}
import cats.implicits.catsSyntaxApplicativeId

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.dagL0KryoRegistrar
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.UpdateNodeParametersEvent
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.generators.signatureProofN
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.syntax.sortedCollection.sortedMapSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object UpdateNodeParametersCutterSuite extends MutableIOSuite with Checkers {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar ++ dagL0KryoRegistrar)
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forJson[IO]
    } yield (j, h, sp)

  test("no events should not raise error") { res =>
    implicit val (j, h, _) = res

    mkCutter(3).cut(List.empty, mkGlobalContext(), SnapshotOrdinal(NonNegLong(10))).map(cutList => expect.same(List(), cutList))
  }

  test("no events cut because the input list is not exceed max size") { res =>
    implicit val (j, h, _) = res

    val gen = eventsGen(5, 10)

    forall(gen) { events =>
      for {
        cutter <- mkCutter(30).pure[IO]
        eventsAfterCut <- cutter.cut(events, mkGlobalContext(), SnapshotOrdinal.MinValue)
      } yield expect.all(events.toSet == eventsAfterCut.toSet)
    }
  }

  test("remove extra events when the global context contains previous updates") { res =>
    implicit val (j, h, _) = res

    val gen = eventsGen(5, 10)

    forall(gen) { events =>
      for {
        cutter <- mkCutter(3).pure[IO]
        (expectedEvents, cutEvents) = (events.take(3), events.drop(3))
        context = cutEvents
          .map(e => e.updateNodeParameters.proofs.head.id -> (e.updateNodeParameters, SnapshotOrdinal.MinValue.plus(NonNegLong(5))))
          .toSortedMap
        eventsAfterCut <- cutter.cut(events, mkGlobalContext(context), SnapshotOrdinal.MinValue.plus(NonNegLong(10)))
      } yield expect.all(eventsAfterCut.toSet == expectedEvents.toSet)
    }
  }

  def mkCutter(maxSize: Int): UpdateNodeParametersCutter[IO] =
    UpdateNodeParametersCutter.make[IO](PosInt.unsafeFrom(maxSize))

  def mkGlobalContext(updateNodeParameters: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)] = SortedMap.empty) =
    GlobalSnapshotInfo.empty.copy(updateNodeParameters = Some(updateNodeParameters))

  val zeroLongGen: Gen[Long] = Gen.const(0L)
  val posLongGen: Gen[Long] = Gen.choose(1L, Long.MaxValue)
  val nonNegLongGen: Gen[Long] = Gen.choose(0L, Long.MaxValue)
  val rewardFractionGen: Gen[Int] = Gen.choose(0, DelegatedStakeRewardParameters.MaxRewardFraction.value)

  val nodeMetadataParametersGen: Gen[NodeMetadataParameters] =
    for {
      name <- Gen.asciiPrintableStr
      description <- Gen.alphaStr
    } yield NodeMetadataParameters(name, description)

  val delegatedStakeRewardParametersGen: Gen[DelegatedStakeRewardParameters] =
    for {
      reward <- rewardFractionGen
    } yield DelegatedStakeRewardParameters(RewardFraction.unsafeFrom(reward))

  val updateNodeParametersGen: Gen[UpdateNodeParameters] =
    for {
      delegatedStakeRewardParameters <- delegatedStakeRewardParametersGen
      nodeMetadataParameters <- nodeMetadataParametersGen
    } yield
      UpdateNodeParameters(
        Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB"),
        delegatedStakeRewardParameters,
        nodeMetadataParameters,
        UpdateNodeParametersReference.empty
      )

  def signedOf[A](valueGen: Gen[A]): Gen[Signed[A]] =
    for {
      txn <- valueGen
      signatureProof <- signatureProofN(1)
    } yield Signed(txn, signatureProof)

  val signedUpdateNodeParametersGen: Gen[Signed[UpdateNodeParameters]] = signedOf(updateNodeParametersGen)

  def listOfN[A](min: Int, max: Int, gen: Gen[A]): Gen[List[A]] =
    Gen.choose(min, max).flatMap(Gen.listOfN(_, gen))

  def eventsGen(min: Int, max: Int): Gen[List[UpdateNodeParametersEvent]] =
    for {
      events <- listOfN(min, max, signedUpdateNodeParametersGen)
    } yield events.map(UpdateNodeParametersEvent(_))

}

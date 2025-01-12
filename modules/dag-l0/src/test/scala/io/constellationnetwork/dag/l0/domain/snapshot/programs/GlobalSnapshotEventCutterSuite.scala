package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet

import io.constellationnetwork.block.generators.{blockReferencesGen, signedBlockGen}
import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.dag.l0.dagL0KryoRegistrar
import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalSnapshotContext
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.{DAGEvent, StateChannelEvent}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.generators.nonEmptyStringGen
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.Block._
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.transaction.TransactionFee
import io.constellationnetwork.schema.{Block, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotEventCutterSuite extends MutableIOSuite with Checkers {
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

    makeCutter(PosInt.MaxValue)
      .cut(Nil, Nil, makeSnapshotInfo(), SnapshotOrdinal.MinValue)
      .map(actual => expect.all((Nil, Nil) == actual))
  }

  test("all events cut because max size too small") { res =>
    implicit val (ks, h, _) = res

    val gen = eventsGen(5, 10)
    forall(gen) {
      case (scEvents, dagEvents) =>
        for {
          cutter <- makeCutter(PosInt.MinValue).pure[F]
          actual <- cutter.cut(
            scEvents,
            dagEvents,
            makeSnapshotInfo(),
            SnapshotOrdinal.MinValue
          )
        } yield expect.all(actual == (Nil, Nil))
    }
  }

  test("no events cut") { res =>
    implicit val (j, h, _) = res

    val gen = eventsGen(5, 15)
    forall(gen) {
      case (scEvents, dagEvents) =>
        for {
          scEventsSize <- j.serialize(scEvents).map(_.length)
          dagEventsSize <- j.serialize(dagEvents).map(_.length)
          cutter = makeCutter(PosInt.unsafeFrom(scEventsSize + dagEventsSize))
          (scEventsAfterCut, dagEventsAfterCut) <- cutter.cut(scEvents, dagEvents, makeSnapshotInfo(), SnapshotOrdinal.MinValue)
        } yield expect.all(scEvents == scEventsAfterCut, dagEvents == dagEventsAfterCut)
    }
  }

  test("remove lowest fees but not parents") { res =>
    implicit val (j, h, _) = res

    val gen = for {
      scEvents <- listOfN(5, 10, stateChannelOutputGenWithFee(posLongGen)).map(_.map(StateChannelEvent(_)))
      scParent <- stateChannelOutputGenWithFee(zeroLongGen)
      dagEvents <- childrenBlockGen
      dagParent <- parentBlockGen
    } yield (scEvents, scParent, dagEvents, dagParent)

    forall(gen) {
      case (scEvents, scParent, dagEvents, dagParent) =>
        for {
          scParentHash <- scParent.snapshotBinary.toHashed[IO].map(_.hash)
          adjustedScEvents = scEvents.map { posFeeEvent =>
            val childBinary = posFeeEvent.value.snapshotBinary.value.copy(lastSnapshotHash = scParentHash)
            posFeeEvent.value.copy(
              address = scParent.address,
              // NOTE: Safe to overwrite value of signed Signed[StateChannelSnapshotBinary]
              snapshotBinary = posFeeEvent.value.snapshotBinary.copy(value = childBinary)
            )
          }.map(StateChannelEvent(_))

          sortedScEvents <- adjustedScEvents
            .traverse(WrappedStateChannelEvent(_))
            .map(_.sorted)

          dagParentRef <- dagParent.toHashed[IO].map(_.ownReference).map(NonEmptyList.of(_))

          // NOTE: Safe to remap since generated Signed[Block] not dependent on parent refs
          adjustedDagEvents = dagEvents.map(sb => Signed(sb.value.copy(parent = dagParentRef), sb.proofs)).map(DAGEvent(_))
          sortedDagEvents <- adjustedDagEvents
            .traverse(WrappedDAGEvent(_))
            .map(_.sorted)

          (prunedScEvents, prunedDagEvents) = dropEvents(5, sortedScEvents, sortedDagEvents)
          expectedScEvents = prunedScEvents.map(_.event) :+ StateChannelEvent(scParent)
          expectedDagEvents = prunedDagEvents.map(_.event) :+ DAGEvent(dagParent)

          expectedSize <- (
            j.serialize(expectedScEvents).map(_.length),
            j.serialize(expectedDagEvents).map(_.length)
          ).mapN(_ + _)

          cutter = makeCutter(PosInt.unsafeFrom(expectedSize))
          (actualScEvents, actualDagEvents) <- cutter.cut(
            StateChannelEvent(scParent) :: adjustedScEvents,
            DAGEvent(dagParent) :: adjustedDagEvents,
            makeSnapshotInfo(),
            SnapshotOrdinal.MinValue
          )
        } yield
          expect.all(
            expectedScEvents == actualScEvents,
            expectedDagEvents == actualDagEvents
          )
    }
  }

  def makeSnapshotInfo() =
    GlobalSnapshotInfo.empty

  val feeCalculator = new SnapshotBinaryFeeCalculator[IO] {
    override def calculateFee(
      event: StateChannelEvent,
      info: GlobalSnapshotContext,
      ordinal: SnapshotOrdinal
    ): IO[NonNegLong] =
      event.value.snapshotBinary.value.fee.value.pure[IO]
  }

  def makeCutter(maxSize: PosInt)(implicit J: JsonSerializer[IO], H: Hasher[IO]) =
    GlobalSnapshotEventCutter.make[IO](maxSize, feeCalculator)

  val zeroLongGen: Gen[Long] = Gen.const(0L)
  val posLongGen: Gen[Long] = Gen.choose(1L, Long.MaxValue)
  val nonNegLongGen: Gen[Long] = Gen.choose(0L, Long.MaxValue)

  def snapshotFeeGen(feeGen: Gen[Long]): Gen[SnapshotFee] =
    feeGen.map(fee => SnapshotFee(NonNegLong.unsafeFrom(fee)))

  def stateChannelSnapshotBinaryGen(feeGen: Gen[Long]): Gen[StateChannelSnapshotBinary] =
    for {
      hash <- Hash.arbitrary.arbitrary
      content <- nonEmptyStringGen
      fee <- snapshotFeeGen(feeGen)
    } yield StateChannelSnapshotBinary(hash, content.getBytes, fee)

  def stateChannelOutputGenWithFee(feeGen: Gen[Long]): Gen[StateChannelOutput] =
    for {
      address <- addressGen
      signedBinary <- signedOf(stateChannelSnapshotBinaryGen(feeGen))
    } yield StateChannelOutput(address, signedBinary)

  val stateChannelOutputGen: Gen[StateChannelOutput] =
    stateChannelOutputGenWithFee(nonNegLongGen)

  def listOfN[A](min: Int, max: Int, gen: Gen[A]): Gen[List[A]] =
    Gen.choose(min, max).flatMap(Gen.listOfN(_, gen))

  def eventsGen(min: Int, max: Int): Gen[(List[StateChannelEvent], List[DAGEvent])] =
    for {
      scEvents <- listOfN(min, max, stateChannelOutputGen)
      dagEvents <- listOfN(min, max, signedBlockGen)
    } yield (scEvents.map(StateChannelEvent(_)), dagEvents.map(DAGEvent(_)))

  def blockWithFeeGen(fee: Long): Gen[Block] =
    for {
      blockReferences <- blockReferencesGen
      signedTxn <- signedOf(transactionGen.map(_.copy(fee = TransactionFee(NonNegLong.unsafeFrom(fee)))))
    } yield Block(blockReferences, NonEmptySet.fromSetUnsafe(SortedSet(signedTxn)))

  val parentBlockGen: Gen[Signed[Block]] =
    signedOf(blockWithFeeGen(0L))

  val childrenBlockGen: Gen[List[Signed[Block]]] =
    listOfN(5, 10, posLongGen.flatMap(fee => signedOf(blockWithFeeGen(fee))))

  case class WrappedStateChannelEvent(event: StateChannelEvent, fee: Long, hash: Hash)

  object WrappedStateChannelEvent {
    def apply(event: StateChannelEvent)(implicit hasher: Hasher[IO]): IO[WrappedStateChannelEvent] = {
      val fee = event.value.snapshotBinary.fee.value.value
      event.value.snapshotBinary.toHashed[IO].map(h => WrappedStateChannelEvent(event, fee, h.hash))
    }
    implicit val ordering: Ordering[WrappedStateChannelEvent] = Ordering.by(w => (w.fee, w.hash))
  }

  case class WrappedDAGEvent(event: DAGEvent, fee: Long, hash: Hash)

  object WrappedDAGEvent {
    def apply(event: DAGEvent)(implicit hasher: Hasher[IO]): IO[WrappedDAGEvent] = {
      val fee = event.value.transactions.toIterable.map(_.value.fee.value.value).sum
      event.value.toHashed[IO].map(h => WrappedDAGEvent(event, fee, h.hash))
    }
    implicit val ordering: Ordering[WrappedDAGEvent] = Ordering.by(w => (w.fee, w.hash))
  }

  /* NOTE:
   * Drop n events from the two (sorted) lists:
   * Drop lowest head from the lists
   * If heads are equal, drop from the longer list, or first list if lengths are equal
   */
  @tailrec
  def dropEvents(
    n: Int,
    stateChannelEvents: List[WrappedStateChannelEvent],
    dagEvents: List[WrappedDAGEvent]
  ): (List[WrappedStateChannelEvent], List[WrappedDAGEvent]) =
    if (n == 0)
      (stateChannelEvents, dagEvents)
    else
      (stateChannelEvents, dagEvents) match {
        case (ls, Nil) => (ls.drop(n), Nil)
        case (Nil, rs) => (Nil, rs.drop(n))
        case (h1 :: t1, h2 :: t2) =>
          if (h1.fee < h2.fee) dropEvents(n - 1, t1, dagEvents)
          else if (h1.fee > h2.fee) dropEvents(n - 1, stateChannelEvents, t2)
          else if (t1.length >= t2.length) dropEvents(n - 1, t1, dagEvents)
          else dropEvents(n - 1, stateChannelEvents, t2)
      }

}

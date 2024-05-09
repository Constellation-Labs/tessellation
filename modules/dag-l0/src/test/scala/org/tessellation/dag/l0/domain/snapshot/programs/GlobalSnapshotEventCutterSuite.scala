package org.tessellation.dag.l0.domain.snapshot.programs

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet

import org.tessellation.block.generators.{blockReferencesGen, signedBlockGen}
import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.dag.l0.dagL0KryoRegistrar
import org.tessellation.dag.l0.infrastructure.snapshot.{DAGEvent, GlobalSnapshotContext, StateChannelEvent}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo.RefinedSerializer
import org.tessellation.generators.nonEmptyStringGen
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block._
import org.tessellation.schema.generators._
import org.tessellation.schema.transaction.TransactionFee
import org.tessellation.schema.{Block, GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotEventCutterSuite extends MutableIOSuite with Checkers {
  type Res = (KryoSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar ++ dagL0KryoRegistrar)
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forJson[IO]
    } yield (ks, h, sp)

  test("no events should not raise error") { res =>
    implicit val (ks, h, _) = res

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
          actual <- cutter.cut(scEvents, dagEvents, makeSnapshotInfo(), SnapshotOrdinal.MinValue)
        } yield expect.all(actual == (Nil, Nil))
    }
  }

  test("no events cut") { res =>
    implicit val (ks, h, _) = res

    val gen = eventsGen(5, 15)
    forall(gen) {
      case (scEvents, dagEvents) =>
        for {
          scEventsSize <- scEvents.toBinary.liftTo[IO].map(_.length)
          dagEventsSize <- dagEvents.toBinary.liftTo[IO].map(_.length)
          cutter = makeCutter(PosInt.unsafeFrom(scEventsSize + dagEventsSize))
          (scEventsAfterCut, dagEventsAfterCut) <- cutter.cut(scEvents, dagEvents, makeSnapshotInfo(), SnapshotOrdinal.MinValue)
        } yield expect.all(scEvents == scEventsAfterCut, dagEvents == dagEventsAfterCut)
    }
  }

  test("remove lowest fees but not parents") { res =>
    implicit val (ks, h, _) = res

    val gen = for {
      scEvents <- listOfN(5, 10, stateChannelOutputGenWithFee(posLongGen))
      scParent <- stateChannelOutputGenWithFee(zeroLongGen)
      dagEvents <- childrenBlockGen
      dagParent <- parentBlockGen
    } yield (scEvents, scParent, dagEvents, dagParent)

    forall(gen) {
      case (scEvents, scParent, dagEvents, dagParent) =>
        for {
          scParentHash <- scParent.snapshotBinary.toHashed[IO].map(_.hash)
          adjustedScEvents = scEvents.map { posFeeEvent =>
            val childBinary = posFeeEvent.snapshotBinary.value.copy(lastSnapshotHash = scParentHash)
            posFeeEvent.copy(
              address = scParent.address,
              // NOTE: Safe to overwrite value of signed Signed[StateChannelSnapshotBinary]
              snapshotBinary = posFeeEvent.snapshotBinary.copy(value = childBinary)
            )
          }

          sortedScEvents <- adjustedScEvents
            .traverse(WrappedStateChannelEvent(_))
            .map(_.sorted)

          dagParentRef <- dagParent.toHashed[IO].map(_.ownReference).map(NonEmptyList.of(_))

          // NOTE: Safe to remap since generated Signed[Block] not dependent on parent refs
          adjustedDagEvents = dagEvents.map(sb => Signed(sb.value.copy(parent = dagParentRef), sb.proofs))
          sortedDagEvents <- adjustedDagEvents
            .traverse(WrappedDAGEvent(_))
            .map(_.sorted)

          (prunedScEvents, prunedDagEvents) = dropEvents(5, sortedScEvents, sortedDagEvents)
          expectedScEvents = prunedScEvents.map(_.event) :+ scParent
          expectedDagEvents = prunedDagEvents.map(_.event) :+ dagParent

          expectedSize <- (
            expectedScEvents.toBinary.liftTo[IO].map(_.length),
            expectedDagEvents.toBinary.liftTo[IO].map(_.length)
          ).mapN(_ + _)

          cutter = makeCutter(PosInt.unsafeFrom(expectedSize))
          (actualScEvents, actualDagEvents) <- cutter.cut(
            scParent :: adjustedScEvents,
            dagParent :: adjustedDagEvents,
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
      event.snapshotBinary.value.fee.value.pure[IO]
  }

  def makeCutter(maxSize: PosInt)(implicit K: KryoSerializer[IO], H: Hasher[IO]) =
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

  def eventsGen(min: Int, max: Int): Gen[(List[StateChannelOutput], List[Signed[Block]])] =
    for {
      scEvents <- listOfN(min, max, stateChannelOutputGen)
      dagEvents <- listOfN(min, max, signedBlockGen)
    } yield (scEvents, dagEvents)

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
      val fee = event.snapshotBinary.fee.value.value
      event.snapshotBinary.toHashed[IO].map(h => WrappedStateChannelEvent(event, fee, h.hash))
    }
    implicit val ordering: Ordering[WrappedStateChannelEvent] = Ordering.by(w => (w.fee, w.hash))
  }

  case class WrappedDAGEvent(event: DAGEvent, fee: Long, hash: Hash)

  object WrappedDAGEvent {
    def apply(event: DAGEvent)(implicit hasher: Hasher[IO]): IO[WrappedDAGEvent] = {
      val fee = event.value.transactions.toIterable.map(_.value.fee.value.value).sum
      event.toHashed[IO].map(h => WrappedDAGEvent(event, fee, h.hash))
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

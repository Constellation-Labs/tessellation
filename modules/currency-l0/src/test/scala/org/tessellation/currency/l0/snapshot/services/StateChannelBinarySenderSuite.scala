package org.tessellation.currency.l0.snapshot.services

import cats.data.{Kleisli, NonEmptyList, NonEmptySet}
import cats.effect._
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.generators.nonEmptyStringGen
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import org.tessellation.node.shared.http.p2p.PeerResponse.PeerResponse
import org.tessellation.node.shared.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.generators.{chooseNumRefined, signedOf}
import org.tessellation.schema.peer.{L0Peer, P2PContext, PeerId}
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.StateChannelSnapshotBinary

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object StateChannelBinarySenderSuite extends MutableIOSuite with Checkers {

  def mkEmptySnapshots(n: Long, identifier: Address)(
    implicit hs: Hasher[IO]
  ): IO[List[GlobalIncrementalSnapshot]] =
    (1L to n).toList.traverse(ordinal => mkSnapshot(SnapshotOrdinal(NonNegLong.unsafeFrom(ordinal)), identifier, List.empty))

  def mkSnapshot(ordinal: SnapshotOrdinal, identifier: Address, confirmedBinaries: List[Signed[StateChannelSnapshotBinary]])(
    implicit hs: Hasher[IO]
  ): IO[GlobalIncrementalSnapshot] =
    GlobalIncrementalSnapshot
      .fromGlobalSnapshot[F](
        GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue),
        (_: SnapshotOrdinal) => JsonHash
      )
      .map(
        _.copy(
          ordinal = ordinal,
          stateChannelSnapshots =
            NonEmptyList.fromList(confirmedBinaries).map(nel => SortedMap(identifier -> nel)).getOrElse(SortedMap.empty)
        )
      )

  def mkService(
    identifier: Address,
    currentOrdinal: SnapshotOrdinal,
    state: State
  )(
    implicit sp: SecurityProvider[IO],
    hs: Hasher[IO]
  ): IO[(StateChannelBinarySender[IO], Ref[IO, State], Ref[IO, List[Hashed[StateChannelSnapshotBinary]]])] =
    for {
      identifierStorage <- new IdentifierStorage[IO] {
        def setInitial(address: Address): IO[Unit] = ???
        def get: IO[address.Address] = identifier.pure[IO]
      }.pure[IO]
      globalL0ClusterStorage = new L0ClusterStorage[IO] {
        def getPeers: IO[NonEmptySet[L0Peer]] = ???
        def getPeer(id: peer.PeerId): IO[Option[peer.L0Peer]] = ???
        def getRandomPeer: IO[peer.L0Peer] = L0Peer(PeerId(Hex("")), Host.fromString("0.0.0.0").get, Port.fromInt(100).get).pure[IO]
        def addPeers(l0Peers: Set[peer.L0Peer]): IO[Unit] = ???
        def setPeers(l0Peers: NonEmptySet[peer.L0Peer]): IO[Unit] = ???
      }
      lastSnapshotStorage = new LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
        def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): StateChannelBinarySenderSuite.F[Unit] = ???
        def setInitial(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): StateChannelBinarySenderSuite.F[Unit] = ???
        def get: StateChannelBinarySenderSuite.F[Option[Hashed[GlobalIncrementalSnapshot]]] = ???
        def getCombined: StateChannelBinarySenderSuite.F[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???
        def getCombinedStream
          : fs2.Stream[StateChannelBinarySenderSuite.F, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???
        def getOrdinal: StateChannelBinarySenderSuite.F[Option[SnapshotOrdinal]] = currentOrdinal.some.pure[F]
        def getHeight: StateChannelBinarySenderSuite.F[Option[height.Height]] = ???
      }

      postedRef <- Ref.of[IO, List[Hashed[StateChannelSnapshotBinary]]](List.empty)
      stateChannelSnapshotClient = new StateChannelSnapshotClient[F] {
        def send(
          identifier: address.Address,
          data: Signed[StateChannelSnapshotBinary]
        ): PeerResponse[F, Either[NonEmptyList[StateChannelValidationError], Unit]] =
          Kleisli[F, P2PContext, Either[NonEmptyList[StateChannelValidationError], Unit]] { _ =>
            data.toHashed.flatMap { hashed =>
              postedRef.update(_ :+ hashed).map(_.asRight[NonEmptyList[StateChannelValidationError]])
            }
          }
      }
      stateRef <- Ref.of[IO, State](state)
      sender = StateChannelBinarySender.make[IO](
        stateRef,
        identifierStorage,
        globalL0ClusterStorage,
        lastSnapshotStorage,
        stateChannelSnapshotClient
      )
    } yield (sender, stateRef, postedRef)

  type Res = (KryoSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forSync[IO]((_: SnapshotOrdinal) => JsonHash)
    } yield (ks, h, sp)

  def binaryGen: Gen[Signed[StateChannelSnapshotBinary]] =
    for {
      hash <- Hash.arbitrary.arbitrary
      content <- nonEmptyStringGen
      signedBinary <- signedOf(StateChannelSnapshotBinary(hash, content.getBytes, SnapshotFee.MinValue))
    } yield signedBinary

  test("should remove confirmed binaries from queue") { res =>
    implicit val (_, hs, sp) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, _) <- mkService(kp.getPublic.toAddress, currentOrdinal = SnapshotOrdinal.MinValue, state = State.empty)
        hashed <- binaries.traverse(_.toHashed)
        _ <- hashed.traverse(sender.process)
        globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp.getPublic.toAddress, binaries)
        _ <- sender.confirm(globalSnapshot)
        state <- stateRef.get
      } yield expect(state.pending.isEmpty)
    }
  }

  test("should transition to retry mode when a snapshot is not confirmed for 5 or more ordinals") { res =>
    implicit val (_, hs, sp) = res

    forall(binaryGen) { binary =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, _) <- mkService(kp.getPublic.toAddress, currentOrdinal = SnapshotOrdinal.MinValue, state = State.empty)
        hashed <- binary.toHashed
        _ <- sender.process(hashed)
        globalSnapshot <- mkSnapshot(SnapshotOrdinal(6L), kp.getPublic.toAddress, List.empty)
        _ <- sender.confirm(globalSnapshot)
        state <- stateRef.get
      } yield expect(state.retryMode)
    }
  }

  test("normal mode - process should enqueue and send a binary right away") { res =>
    implicit val (_, hs, sp) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, postedRef) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = State.empty.copy(retryMode = true)
        )
        hashed <- binaries.traverse(_.toHashed)
        _ <- hashed.traverse(sender.process)
        state <- stateRef.get
        posted <- postedRef.get
      } yield
        expect(state.pending.nonEmpty)
          .and(expect(state.pending.map(_.binary).toSet.subsetOf(hashed.toSet)))
          .and(expect(posted.toSet.subsetOf(hashed.toSet)))
    }
  }

  test("retry mode - should switch to normal mode if cap >= enqueued count, all sent and no stalled") { res =>
    implicit val (_, hs, sp) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, _) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = State.empty.copy(
            retryMode = true,
            cap = NonNegLong.unsafeFrom(binaries.length.toLong)
          )
        )

        hashed <- binaries.traverse(_.toHashed)
        _ <- hashed.traverse(sender.process)

        globalSnapshot <- mkSnapshot(SnapshotOrdinal(5L), kp.getPublic.toAddress, List.empty)
        _ <- sender.confirm(globalSnapshot)
        capReachedButNoSendsSoFar <- stateRef.get

        _ <- stateRef.update { state =>
          state.copy(
            pending = state.pending.map(t => t.copy(sendsSoFar = NonNegLong(1L), enqueuedAtOrdinal = SnapshotOrdinal(0L))),
            cap = NonNegLong.unsafeFrom(state.pending.length.toLong)
          )
        }
        _ <- sender.confirm(globalSnapshot)
        capReachedAllSentButHasStalled <- stateRef.get

        _ <- stateRef.update { state =>
          state.copy(
            pending = state.pending.map(t => t.copy(sendsSoFar = NonNegLong(1L), enqueuedAtOrdinal = SnapshotOrdinal(1L))),
            cap = NonNegLong.unsafeFrom(state.pending.length.toLong)
          )
        }
        _ <- sender.confirm(globalSnapshot)
        capReachedAllSentAndNoStalled <- stateRef.get
      } yield
        expect(capReachedButNoSendsSoFar.retryMode)
          .and(expect(capReachedAllSentButHasStalled.retryMode))
          .and(expect(!capReachedAllSentAndNoStalled.retryMode))
    }
  }

  test("retry mode - process should enqueue binary without sending") { res =>
    implicit val (_, hs, sp) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, postedRef) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = State.empty.copy(retryMode = true)
        )
        hashed <- binaries.traverse(_.toHashed)
        _ <- hashed.traverse(sender.process)
        state <- stateRef.get
        posted <- postedRef.get
      } yield
        expect(state.pending.nonEmpty)
          .and(expect(state.pending.map(_.binary).toSet.subsetOf(hashed.toSet)))
          .and(expect(posted.isEmpty))
    }
  }

  test("retry mode - cap should decrement by 1 if no confirmations") { res =>
    implicit val (_, hs, sp) = res

    val gen = for {
      binary <- binaryGen
      cap <- chooseNumRefined(NonNegLong(1L), NonNegLong(100L))
    } yield (binary, cap)

    forall(gen) {
      case (binary, cap) =>
        for {
          kp <- KeyPairGenerator.makeKeyPair
          (sender, stateRef, _) <- mkService(
            kp.getPublic.toAddress,
            currentOrdinal = SnapshotOrdinal.MinValue,
            state = State.empty.copy(cap = cap, retryMode = true)
          )
          hashedBinary <- binary.toHashed
          _ <- sender.process(hashedBinary)
          globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp.getPublic.toAddress, List.empty)
          prevState <- stateRef.get
          _ <- sender.confirm(globalSnapshot)
          state <- stateRef.get
        } yield expect.eql(state.cap.value, prevState.cap.value - 1)
    }
  }

  test("retry mode - cap should increment with every confirmation but no more than 4*confirmedCount") { res =>
    implicit val (_, hs, sp) = res

    val gen = for {
      nBinaries <- Gen.nonEmptyListOf(binaryGen)
      binary <- binaryGen
      binaries = nBinaries :+ binary // To be sure that we have at least 2 elements to avoid exiting from retryMode
      howManyToConfirm <- Gen.choose(1, binaries.length - 1)
      confirmedBinaries = binaries.take(howManyToConfirm)
    } yield (binaries, confirmedBinaries)

    forall(gen) {
      case (binaries, confirmedBinaries) =>
        for {
          kp <- KeyPairGenerator.makeKeyPair
          (sender, stateRef, _) <- mkService(
            kp.getPublic.toAddress,
            currentOrdinal = SnapshotOrdinal.MinValue,
            state = State.empty.copy(
              cap = 1L,
              retryMode = true
            )
          )

          _ <- binaries.traverse_(bin => bin.toHashed.flatMap(sender.process))
          globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp.getPublic.toAddress, confirmedBinaries)
          prevState <- stateRef.get
          _ <- sender.confirm(globalSnapshot)
          state <- stateRef.get
        } yield expect(state.cap.value >= prevState.cap.value).and(expect(state.cap.value <= confirmedBinaries.length * 4))
    }
  }

  test("retry mode - should switch to exponential mode when cap goes to 0") { res =>
    implicit val (_, hs, sp) = res

    forall(binaryGen) { binary =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, _) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = State.empty.copy(cap = 1L, retryMode = true)
        )
        hashedBinary <- binary.toHashed
        _ <- sender.process(hashedBinary)
        globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp.getPublic.toAddress, List.empty)
        _ <- sender.confirm(globalSnapshot)
        state <- stateRef.get
      } yield
        expect
          .eql(state.cap.value, 0L)
          .and(expect.eql(state.backoffExponent.value, 1L))
          .and(expect.eql(state.noConfirmationsSinceRetryCount.value, 1L))
    }
  }

  test("retry mode (exponential) - retries 1 only if waited 2^n ordinals") { res =>
    implicit val (_, hs, sp) = res

    val gen = for {
      binary <- binaryGen
      exponent <- chooseNumRefined(NonNegLong(1L), NonNegLong(10L))
    } yield (binary, exponent)

    forall(gen) {
      case (binary, exponent) =>
        for {
          kp <- KeyPairGenerator.makeKeyPair
          (sender, _, postedRef) <- mkService(
            kp.getPublic.toAddress,
            currentOrdinal = SnapshotOrdinal.MinValue,
            state = State.empty.copy(cap = 0L, retryMode = true, backoffExponent = exponent, noConfirmationsSinceRetryCount = 1L)
          )
          hashedBinary <- binary.toHashed
          _ <- sender.process(hashedBinary)

          expectedNoConfirmationsToRetry = Math.pow(2.0, exponent.value.toDouble).toLong
          snapshots <- mkEmptySnapshots(expectedNoConfirmationsToRetry, kp.getPublic.toAddress)

          lessThanNeeded = snapshots.take(expectedNoConfirmationsToRetry.toInt - 2)
          _ <- lessThanNeeded.traverse(snapshot => sender.confirm(snapshot) >> sender.processPending)

          postedAfterSendingLessThanNeeded <- postedRef.get

          _ <- sender.confirm(snapshots.last) >> sender.processPending

          postedAfterSendingLast <- postedRef.get

        } yield expect(postedAfterSendingLessThanNeeded.isEmpty).and(expect.eql(postedAfterSendingLast.length, 1))
    }
  }

  test("retry mode (exponential) - increments exponent if passed 2^n without confirmations and resets counter") { res =>
    implicit val (_, hs, sp) = res

    val gen = for {
      binary <- binaryGen
      exponent <- chooseNumRefined(NonNegLong(1L), NonNegLong(100L))
    } yield (binary, exponent)

    forall(gen) {
      case (binary, exponent) =>
        for {
          kp <- KeyPairGenerator.makeKeyPair
          (sender, stateR, _) <- mkService(
            kp.getPublic.toAddress,
            currentOrdinal = SnapshotOrdinal.MinValue,
            state = State.empty.copy(
              cap = 1L,
              retryMode = true,
              backoffExponent = exponent,
              noConfirmationsSinceRetryCount = NonNegLong.unsafeFrom(Math.pow(2.0, exponent.value.toDouble).toLong - 1L)
            )
          )
          hashedBinary <- binary.toHashed
          _ <- sender.process(hashedBinary)
          snapshot <- mkSnapshot(ordinal = SnapshotOrdinal.MinValue, kp.getPublic.toAddress, List.empty)
          prevState <- stateR.get
          _ <- sender.confirm(snapshot) >> sender.processPending
          state <- stateR.get
        } yield
          expect
            .eql(state.backoffExponent.value, prevState.backoffExponent.value + 1L)
            .and(expect.eql(state.noConfirmationsSinceRetryCount, NonNegLong(1L)))
            .and(expect.eql(state.cap, NonNegLong(0L)))
    }
  }
}

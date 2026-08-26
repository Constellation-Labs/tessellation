package io.constellationnetwork.currency.l0.snapshot.services

import java.security.KeyPair

import cats.data.{Kleisli, NonEmptyList, NonEmptySet}
import cats.effect._
import cats.effect.std.Supervisor
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._

import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment.{Dev, Mainnet}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.generators.nonEmptyStringGen
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.clients.StateChannelSnapshotClient
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generators.{chooseNumRefined, signedOf}
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object StateChannelBinarySenderSuite extends MutableIOSuite with Checkers {

  def mkEmptySnapshots(n: Long, keyPair: KeyPair)(
    implicit hs: Hasher[IO],
    sp: SecurityProvider[IO]
  ): IO[List[Hashed[GlobalIncrementalSnapshot]]] =
    (1L to n).toList.traverse(ordinal => mkSnapshot(SnapshotOrdinal(NonNegLong.unsafeFrom(ordinal)), keyPair, List.empty))

  def mkSnapshot(ordinal: SnapshotOrdinal, keyPair: KeyPair, confirmedBinaries: List[Signed[StateChannelSnapshotBinary]])(
    implicit hs: Hasher[IO],
    sp: SecurityProvider[IO]
  ): IO[Hashed[GlobalIncrementalSnapshot]] = {
    val identifier = keyPair.getPublic.toAddress

    GlobalIncrementalSnapshot
      .fromGlobalSnapshot[IO](
        GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      )
      .map(
        _.copy(
          ordinal = ordinal,
          stateChannelSnapshots =
            NonEmptyList.fromList(confirmedBinaries).map(nel => SortedMap(identifier -> nel)).getOrElse(SortedMap.empty),
          delegateRewards = None
        )
      )
      .flatMap(snapshot => Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](snapshot, keyPair))
      .flatMap(_.toHashed)
  }

  def mkService(
    identifier: Address,
    currentOrdinal: SnapshotOrdinal,
    state: TrackerState,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]] = None,
    selfId: PeerId = PeerId(Hex("0000000000000000")),
    environment: AppEnvironment = Dev
  )(
    implicit sp: SecurityProvider[IO],
    hs: Hasher[IO],
    metrics: Metrics[IO]
  ): Resource[IO, (StateChannelBinarySender[IO], BinaryTracker[IO], Ref[IO, List[Hashed[StateChannelSnapshotBinary]]])] =
    for {
      identifierStorage <- Resource.pure(new IdentifierStorage[IO] {
        def setInitial(address: Address): IO[Unit] = ???

        def get: IO[Address] = identifier.pure[IO]
      })

      globalL0ClusterStorage = new L0ClusterStorage[IO] {
        def getPeers: IO[NonEmptySet[L0Peer]] = ???

        def getPeer(id: PeerId): IO[Option[L0Peer]] = ???

        def getRandomPeer: IO[L0Peer] = L0Peer(PeerId(Hex("")), Host.fromString("0.0.0.0").get, Port.fromInt(100).get).pure[IO]

        def getRandomPeerExistentOnList(peers: List[PeerId]): IO[Option[L0Peer]] =
          L0Peer(PeerId(Hex("")), Host.fromString("0.0.0.0").get, Port.fromInt(100).get).some.pure[IO]

        def addPeers(l0Peers: Set[L0Peer]): IO[Unit] = ???

        def setPeers(l0Peers: NonEmptySet[L0Peer]): IO[Unit] = ???
      }

      lastSnapshotStorage = new LastSnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
        def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] = ???

        def setInitial(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] = ???

        def get: IO[Option[Hashed[GlobalIncrementalSnapshot]]] = ???

        def getCombined: IO[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???

        def getCombinedStream: fs2.Stream[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???

        def getOrdinal: IO[Option[SnapshotOrdinal]] = currentOrdinal.some.pure[IO]

        def getHeight: IO[Option[Height]] = ???
      }

      postedRef <- Resource.eval(Ref.of[IO, List[Hashed[StateChannelSnapshotBinary]]](List.empty))

      stateChannelSnapshotClient = new StateChannelSnapshotClient[IO] {
        def send(
          identifier: Address,
          data: Signed[StateChannelSnapshotBinary]
        ): PeerResponse[IO, Either[NonEmptyList[StateChannelValidationError], Unit]] =
          Kleisli[IO, P2PContext, Either[NonEmptyList[StateChannelValidationError], Unit]] { _ =>
            data.toHashed.flatMap { hashed =>
              postedRef.update(_ :+ hashed).map(_.asRight[NonEmptyList[StateChannelValidationError]])
            }
          }
      }

      tracker <- Resource.eval(BinaryTracker.make[IO])
      _ <- Resource.eval(tracker.updateState(_ => state))

      poster = new BinaryPoster[IO](
        identifierStorage,
        globalL0ClusterStorage,
        stateChannelSnapshotClient,
        stateChannelAllowanceLists,
        selfId,
        environment,
        none,
        tracker
      )

      supervisor <- Supervisor[IO]

      sender = new TestStateChannelBinarySender[IO](
        tracker,
        poster,
        lastSnapshotStorage,
        identifierStorage,
        supervisor
      )
    } yield (sender, tracker, postedRef)

  // Test implementation that exposes internal methods for testing
  class TestStateChannelBinarySender[G[_]: Async: Hasher: Metrics](
    tracker: BinaryTracker[G],
    poster: BinaryPoster[G],
    lastGlobalSnapshotStorage: LastSnapshotStorage[G, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    identifierStorage: IdentifierStorage[G],
    supervisor: Supervisor[G]
  ) extends StateChannelBinarySender[G] {

    def enqueue(
      binary: Hashed[StateChannelSnapshotBinary],
      currencySnapshotOrdinal: SnapshotOrdinal,
      lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
    ): G[Unit] =
      for {
        currentGlobalOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))
        _ <- tracker.enqueue(binary, currencySnapshotOrdinal, currentGlobalOrdinal)
      } yield ()

    // For testing: immediately process the queue after enqueuing
    def process(
      binary: Hashed[StateChannelSnapshotBinary],
      lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
    ): G[Unit] =
      for {
        currentOrdinal <- lastGlobalSnapshotStorage.getOrdinal.map(_.getOrElse(SnapshotOrdinal.MinValue))
        _ <- enqueue(binary, currentOrdinal, lastGlobalSnapshotSigners)
        state <- tracker.getState
        _ <-
          if (!state.retryMode) {
            // In normal mode, send immediately for testing
            poster.post(binary, lastGlobalSnapshotSigners).void
          } else {
            Async[G].unit
          }
      } yield ()

    def confirm(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): G[Unit] =
      for {
        identifier <- identifierStorage.get
        confirmedHashes <- getConfirmedHashes(identifier, globalSnapshot)
        state <- tracker.getState
        oldRetryMode = state.retryMode
        proof = GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot)
        _ <- tracker.markAsConfirmed(confirmedHashes, proof)
        updatedState <- tracker.getState
        retryMode = RetryStrategy.shouldEnterRetryMode(updatedState, globalSnapshot.ordinal)
        _ <- tracker.updateState(_.copy(retryMode = retryMode))
        _ <- tracker.updateState(RetryStrategy.updateRetryParameters(_, oldRetryMode))
      } yield ()

    // Manually trigger queue processing for tests (simulates background worker)
    def processQueue(globalSnapshot: Hashed[GlobalIncrementalSnapshot]): G[Unit] =
      tracker.getState.flatMap { state =>
        if (state.retryMode) {
          val lastGlobalSnapshotSigners = globalSnapshot.signed.proofs.map(_.id.toPeerId).some
          tracker.getPendingToRetry(state.cap.value.toInt).flatMap { toRetry =>
            toRetry.traverse_(pending => poster.post(pending.binary, lastGlobalSnapshotSigners).void)
          }
        } else {
          // Process unsent binaries in normal mode
          tracker.getPendingToRetry(10).flatMap { pending =>
            val unsent = pending.filter(_.sendsSoFar.value === 0L)
            unsent.traverse_(p => poster.post(p.binary, none).void)
          }
        }
      }

    def clearPending: G[Unit] = tracker.clear

    private def getConfirmedHashes(
      identifier: Address,
      globalSnapshot: Hashed[GlobalIncrementalSnapshot]
    ): G[Set[Hash]] = {
      val binaries = globalSnapshot.stateChannelSnapshots.get(identifier).toList.flatMap(_.toList)
      binaries.traverse(b => b.toHashed[G]).map(_.map(_.hash).toSet)
    }
  }

  def mkGlobalSnapshotInfo(lastStateChannelSnapshotHashes: SortedMap[Address, Hash] = SortedMap.empty) =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      None,
      None,
      None,
      None,
      None,
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )

  type Res = (KryoSerializer[IO], Hasher[IO], SecurityProvider[IO], Metrics[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forJson[IO]
      metrics <- Metrics.forAsync[IO](Seq.empty)
    } yield (ks, h, sp, metrics)

  def binaryGen: Gen[Signed[StateChannelSnapshotBinary]] =
    for {
      hash <- Hash.arbitrary.arbitrary
      content <- nonEmptyStringGen
      signedBinary <- signedOf(StateChannelSnapshotBinary(hash, content.getBytes, SnapshotFee.MinValue))
    } yield signedBinary

  test("should add confirmation proof for confirmed binaries in the queue") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, _) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = TrackerState.empty
        )
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse(binaryHashed => sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(binaryHashed, none))
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, binaries)
            _ <- sender.confirm(globalSnapshot)
            state <- tracker.getState
            currentOrdinal = SnapshotOrdinal.MinValue
            expected = hashed.map { binary =>
              ConfirmedBinary(
                PendingBinary(binary, currentOrdinal, currentOrdinal, NonNegLong.unsafeFrom(0L)), // Changed from 0L to 1L
                GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot)
              )
            }
          } yield expect.eql(state.tracked.toList, expected)
        )
      } yield result).use(IO.pure)
    }
  }

  test("should transition to retry mode when a snapshot is not confirmed for 5 or more ordinals") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(binaryGen) { binary =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, _) <- mkService(kp.getPublic.toAddress, currentOrdinal = SnapshotOrdinal.MinValue, state = TrackerState.empty)
        result <- Resource.eval(
          for {
            hashed <- binary.toHashed
            _ <- sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(hashed, none)
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(6L), kp, List.empty)
            _ <- sender.confirm(globalSnapshot)
            state <- tracker.getState
          } yield expect(state.retryMode)
        )
      } yield result).use(IO.pure)
    }
  }

  test("normal mode - process should enqueue and send a binary right away") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, postedRef) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = TrackerState.empty.copy(retryMode = false)
        )
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse(binary => sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(binary, none))
            state <- tracker.getState
            posted <- postedRef.get
          } yield
            expect(state.tracked.nonEmpty)
              .and(expect(state.tracked.map {
                case PendingBinary(binary, _, _, _)    => binary
                case ConfirmedBinary(pendingBinary, _) => pendingBinary.binary
              }.toSet.subsetOf(hashed.toSet)))
              .and(expect(posted.toSet.subsetOf(hashed.toSet)))
        )
      } yield result).use(IO.pure)
    }
  }

  test("retry mode - should switch to normal mode if cap >= enqueued count, all sent and no stalled") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, _) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = TrackerState.empty.copy(
            retryMode = true,
            cap = NonNegLong.unsafeFrom(binaries.length.toLong)
          )
        )
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse(binary => sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(binary, none))

            globalSnapshot <- mkSnapshot(SnapshotOrdinal(5L), kp, List.empty)
            _ <- sender.confirm(globalSnapshot)
            capReachedButNoSendsSoFar <- tracker.getState

            _ <- tracker.updateState { state =>
              state.copy(
                tracked = state.tracked.map {
                  case pending @ PendingBinary(_, _, _, _) =>
                    pending.copy(sendsSoFar = NonNegLong.unsafeFrom(1L), enqueuedAtOrdinal = SnapshotOrdinal(0L))
                  case confirmed => confirmed
                },
                cap = NonNegLong.unsafeFrom(state.tracked.length.toLong)
              )
            }
            _ <- sender.confirm(globalSnapshot)
            capReachedAllSentButHasStalled <- tracker.getState

            _ <- tracker.updateState { state =>
              state.copy(
                tracked = state.tracked.map {
                  case pending @ PendingBinary(_, _, _, _) =>
                    pending.copy(sendsSoFar = NonNegLong.unsafeFrom(1L), enqueuedAtOrdinal = SnapshotOrdinal(1L))
                  case confirmed => confirmed
                },
                cap = NonNegLong.unsafeFrom(state.tracked.length.toLong)
              )
            }
            _ <- sender.confirm(globalSnapshot)
            capReachedAllSentAndNoStalled <- tracker.getState
          } yield
            expect(capReachedButNoSendsSoFar.retryMode)
              .and(expect(capReachedAllSentButHasStalled.retryMode))
              .and(expect(!capReachedAllSentAndNoStalled.retryMode))
        )
      } yield result).use(IO.pure)
    }
  }

  test("retry mode - process should enqueue binary without sending") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, postedRef) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = TrackerState.empty.copy(retryMode = true)
        )
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse(binary => sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(binary, none))
            state <- tracker.getState
            posted <- postedRef.get
          } yield
            expect(state.tracked.nonEmpty)
              .and(expect(state.tracked.map {
                case PendingBinary(binary, _, _, _)    => binary
                case ConfirmedBinary(pendingBinary, _) => pendingBinary.binary
              }.toSet.subsetOf(hashed.toSet)))
              .and(expect(posted.isEmpty))
        )
      } yield result).use(IO.pure)
    }
  }

  test("retry mode - cap should decrement by 1 if no confirmations") { res =>
    implicit val (_, hs, sp, metrics) = res

    val gen = for {
      binary <- binaryGen
      cap <- chooseNumRefined(NonNegLong.unsafeFrom(1L), NonNegLong.unsafeFrom(100L))
    } yield (binary, cap)

    forall(gen) {
      case (binary, cap) =>
        (for {
          kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
          (sender, tracker, _) <- mkService(
            kp.getPublic.toAddress,
            currentOrdinal = SnapshotOrdinal.MinValue,
            state = TrackerState.empty.copy(cap = cap, retryMode = true)
          )
          result <- Resource.eval(
            for {
              hashedBinary <- binary.toHashed
              _ <- sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(hashedBinary, none)
              globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, List.empty)
              prevState <- tracker.getState
              _ <- sender.confirm(globalSnapshot)
              state <- tracker.getState
            } yield expect.eql(state.cap.value, prevState.cap.value - 1)
          )
        } yield result).use(IO.pure)
    }
  }

  test("retry mode - cap should increment with every confirmation but no more than 4*confirmedCount") { res =>
    implicit val (_, hs, sp, metrics) = res

    val gen = for {
      nBinaries <- Gen.nonEmptyListOf(binaryGen)
      binary <- binaryGen
      binaries = nBinaries :+ binary
      howManyToConfirm <- Gen.choose(1, binaries.length - 1)
      confirmedBinaries = binaries.take(howManyToConfirm)
    } yield (binaries, confirmedBinaries)

    forall(gen) {
      case (binaries, confirmedBinaries) =>
        (for {
          kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
          (sender, tracker, _) <- mkService(
            kp.getPublic.toAddress,
            currentOrdinal = SnapshotOrdinal.MinValue,
            state = TrackerState.empty.copy(
              cap = NonNegLong.unsafeFrom(1L),
              retryMode = true
            )
          )
          result <- Resource.eval(
            for {
              _ <- binaries.traverse_(bin =>
                bin.toHashed.flatMap(binary => sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(binary, none))
              )
              globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, confirmedBinaries)
              prevState <- tracker.getState
              _ <- sender.confirm(globalSnapshot)
              state <- tracker.getState
            } yield expect(state.cap.value >= prevState.cap.value).and(expect(state.cap.value <= confirmedBinaries.length * 4))
          )
        } yield result).use(IO.pure)
    }
  }

  test("retry mode - should switch to exponential mode when cap goes to 0") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(binaryGen) { binary =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, _) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = TrackerState.empty.copy(cap = NonNegLong.unsafeFrom(1L), retryMode = true)
        )
        result <- Resource.eval(
          for {
            hashedBinary <- binary.toHashed
            _ <- sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(hashedBinary, none)
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, List.empty)
            _ <- sender.confirm(globalSnapshot)
            state <- tracker.getState
          } yield
            expect
              .eql(state.cap.value, 0L)
              .and(expect.eql(state.backoffExponent.value, 1L))
              .and(expect.eql(state.noConfirmationsSinceRetryCount.value, 1L))
        )
      } yield result).use(IO.pure)
    }
  }

  test("retry mode (exponential) - increments exponent if passed 2^n without confirmations and resets counter") { res =>
    implicit val (_, hs, sp, metrics) = res

    val gen = for {
      binary <- binaryGen
      exponent <- chooseNumRefined(NonNegLong.unsafeFrom(1L), NonNegLong.unsafeFrom(100L))
    } yield (binary, exponent)

    forall(gen) {
      case (binary, exponent) =>
        (for {
          kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
          (sender, tracker, _) <- mkService(
            kp.getPublic.toAddress,
            currentOrdinal = SnapshotOrdinal.MinValue,
            state = TrackerState.empty.copy(
              cap = NonNegLong.unsafeFrom(1L),
              retryMode = true,
              backoffExponent = exponent,
              noConfirmationsSinceRetryCount = NonNegLong.unsafeFrom(Math.pow(2.0, exponent.value.toDouble).toLong - 1L)
            )
          )
          result <- Resource.eval(
            for {
              hashedBinary <- binary.toHashed
              _ <- sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(hashedBinary, none)
              snapshot <- mkSnapshot(ordinal = SnapshotOrdinal.MinValue, kp, List.empty)
              prevState <- tracker.getState
              _ <- sender.confirm(snapshot) >>
                sender.asInstanceOf[TestStateChannelBinarySender[IO]].processQueue(snapshot)
              state <- tracker.getState
            } yield
              expect
                .eql(state.backoffExponent.value, prevState.backoffExponent.value + 1L)
                .and(expect.eql(state.noConfirmationsSinceRetryCount, NonNegLong.unsafeFrom(1L)))
                .and(expect.eql(state.cap, NonNegLong.unsafeFrom(0L)))
          )
        } yield result).use(IO.pure)
    }
  }

  test("should reject when not on allowance list") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        selfId = PeerId(Hex("0000000000000000"))
        allowed = PeerId(Hex("000000000000011"))
        allowanceList = Map(kp.getPublic.toAddress -> NonEmptySet.of(allowed))

        (sender, tracker, postedRef) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = TrackerState.empty,
          allowanceList.some,
          selfId,
          Mainnet
        )
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse(binaryHashed => sender.asInstanceOf[TestStateChannelBinarySender[IO]].process(binaryHashed, none))
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, binaries)
            _ <- sender.confirm(globalSnapshot)
            state <- tracker.getState
            expected = hashed.map { binary =>
              ConfirmedBinary(
                PendingBinary(binary, SnapshotOrdinal.MinValue, SnapshotOrdinal.MinValue, NonNegLong.unsafeFrom(0L)),
                GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot)
              )
            }
            posted <- postedRef.get
          } yield
            expect.all(
              state.tracked.toList === expected,
              posted.size === 0
            )
        )
      } yield result).use(IO.pure)
    }
  }

  test("should pick deterministic peer to send snapshots - with allowed peers") { res =>
    implicit val (_, hs, sp, metrics) = res
    val selfId = PeerId(Hex("123"))

    val lastSigners: List[PeerId] = List(PeerId(Hex("123")), PeerId(Hex("456")), PeerId(Hex("789")))
    val lastSigners1: List[PeerId] = List(PeerId(Hex("123")), PeerId(Hex("789")), PeerId(Hex("456")))
    val lastSigners2: List[PeerId] = List(PeerId(Hex("456")), PeerId(Hex("123")), PeerId(Hex("789")))
    val lastSigners3: List[PeerId] = List(PeerId(Hex("456")), PeerId(Hex("789")), PeerId(Hex("123")))
    val lastSigners4: List[PeerId] = List(PeerId(Hex("789")), PeerId(Hex("123")), PeerId(Hex("456")))
    val lastSigners5: List[PeerId] = List(PeerId(Hex("789")), PeerId(Hex("456")), PeerId(Hex("123")))

    val allowedPeers: List[PeerId] = List(PeerId(Hex("123")), PeerId(Hex("456")), PeerId(Hex("789")))
    for {
      selectedPeer <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer1 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners1,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer2 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners2,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer3 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners3,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer4 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners4,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer5 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners5,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
    } yield
      expect.all(
        selectedPeer.value.value === selectedPeer1.value.value,
        selectedPeer1.value.value === selectedPeer2.value.value,
        selectedPeer2.value.value === selectedPeer3.value.value,
        selectedPeer3.value.value === selectedPeer4.value.value,
        selectedPeer4.value.value === selectedPeer5.value.value,
        selectedPeer5.value.value === "456"
      )
  }

  test("should pick deterministic peer to send snapshots - without allowed peers") { res =>
    implicit val (_, hs, sp, metrics) = res
    val selfId = PeerId(Hex("123"))

    val lastSigners: List[PeerId] = List(PeerId(Hex("123")), PeerId(Hex("456")), PeerId(Hex("789")))
    val lastSigners1: List[PeerId] = List(PeerId(Hex("123")), PeerId(Hex("789")), PeerId(Hex("456")))
    val lastSigners2: List[PeerId] = List(PeerId(Hex("456")), PeerId(Hex("123")), PeerId(Hex("789")))
    val lastSigners3: List[PeerId] = List(PeerId(Hex("456")), PeerId(Hex("789")), PeerId(Hex("123")))
    val lastSigners4: List[PeerId] = List(PeerId(Hex("789")), PeerId(Hex("123")), PeerId(Hex("456")))
    val lastSigners5: List[PeerId] = List(PeerId(Hex("789")), PeerId(Hex("456")), PeerId(Hex("123")))

    for {
      selectedPeer <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer1 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners1,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer2 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners2,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer3 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners3,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer4 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners4,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer5 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners5,
          List.empty,
          selfId,
          Hash.empty
        )
      )
    } yield
      expect.all(
        selectedPeer.value.value === selectedPeer1.value.value,
        selectedPeer1.value.value === selectedPeer2.value.value,
        selectedPeer2.value.value === selectedPeer3.value.value,
        selectedPeer3.value.value === selectedPeer4.value.value,
        selectedPeer4.value.value === selectedPeer5.value.value,
        selectedPeer5.value.value === "456"
      )
  }

  test("should pick deterministic peer to send snapshots - without allowed peers - different hash") { res =>
    implicit val (_, hs, sp, metrics) = res
    val selfId = PeerId(Hex("123"))

    val lastSigners: List[PeerId] = List(PeerId(Hex("123")), PeerId(Hex("456")), PeerId(Hex("789")))
    val lastSigners1: List[PeerId] = List(PeerId(Hex("123")), PeerId(Hex("789")), PeerId(Hex("456")))
    val lastSigners2: List[PeerId] = List(PeerId(Hex("456")), PeerId(Hex("123")), PeerId(Hex("789")))
    val lastSigners3: List[PeerId] = List(PeerId(Hex("456")), PeerId(Hex("789")), PeerId(Hex("123")))
    val lastSigners4: List[PeerId] = List(PeerId(Hex("789")), PeerId(Hex("123")), PeerId(Hex("456")))
    val lastSigners5: List[PeerId] = List(PeerId(Hex("789")), PeerId(Hex("456")), PeerId(Hex("123")))

    for {
      selectedPeer <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer1 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners1,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer2 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners2,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer3 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners3,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer4 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners4,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer5 <- IO.pure(
        PeerSelector.pickDeterministicPeer(
          lastSigners5,
          List.empty,
          selfId,
          Hash("123")
        )
      )
    } yield
      expect.all(
        selectedPeer.value.value === selectedPeer1.value.value,
        selectedPeer1.value.value === selectedPeer2.value.value,
        selectedPeer2.value.value === selectedPeer3.value.value,
        selectedPeer3.value.value === selectedPeer4.value.value,
        selectedPeer4.value.value === selectedPeer5.value.value,
        selectedPeer5.value.value === "123"
      )
  }
}

package io.constellationnetwork.currency.l0.snapshot.services

import java.security.KeyPair

import cats.data.{Kleisli, NonEmptyList, NonEmptySet}
import cats.effect._
import cats.syntax.all._

import scala.collection.immutable.SortedMap

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
      .fromGlobalSnapshot[F](
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
    state: State,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]] = None,
    selfId: PeerId = PeerId(Hex("0000000000000000")),
    environment: AppEnvironment = Dev
  )(
    implicit sp: SecurityProvider[IO],
    hs: Hasher[IO],
    metrics: Metrics[IO]
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

        def getRandomPeerExistentOnList(peers: List[PeerId]): IO[Option[L0Peer]] =
          L0Peer(PeerId(Hex("")), Host.fromString("0.0.0.0").get, Port.fromInt(100).get).some.pure[IO]

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
        stateChannelSnapshotClient,
        stateChannelAllowanceLists,
        selfId,
        environment
      )
    } yield (sender, stateRef, postedRef)

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
      for {
        kp <- KeyPairGenerator.makeKeyPair
        currentOrdinal = SnapshotOrdinal.MinValue
        (sender, stateRef, _) <- mkService(kp.getPublic.toAddress, currentOrdinal = currentOrdinal, state = State.empty)
        hashed <- binaries.traverse(_.toHashed)
        _ <- hashed.traverse(binaryHashed => sender.process(binaryHashed, List.empty, none))
        globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, binaries)
        _ <- sender.confirm(globalSnapshot)
        state <- stateRef.get
        expected = hashed.map { binary =>
          ConfirmedBinary(
            PendingBinary(binary, currentOrdinal, NonNegLong(1L)),
            GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot)
          )
        }
      } yield expect.eql(state.tracked.toList, expected)
    }
  }

  test("should transition to retry mode when a snapshot is not confirmed for 5 or more ordinals") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(binaryGen) { binary =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, _) <- mkService(kp.getPublic.toAddress, currentOrdinal = SnapshotOrdinal.MinValue, state = State.empty)
        hashed <- binary.toHashed
        _ <- sender.process(hashed, List.empty, none)
        globalSnapshot <- mkSnapshot(SnapshotOrdinal(6L), kp, List.empty)
        _ <- sender.confirm(globalSnapshot)
        state <- stateRef.get
      } yield expect(state.retryMode)
    }
  }

  test("normal mode - process should enqueue and send a binary right away") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, postedRef) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = State.empty.copy(retryMode = true)
        )
        hashed <- binaries.traverse(_.toHashed)
        _ <- hashed.traverse(binary => sender.process(binary, List.empty, none))
        state <- stateRef.get
        posted <- postedRef.get
      } yield
        expect(state.tracked.nonEmpty)
          .and(expect(state.tracked.map {
            case PendingBinary(binary, _, _)       => binary
            case ConfirmedBinary(pendingBinary, _) => pendingBinary.binary
          }.toSet.subsetOf(hashed.toSet)))
          .and(expect(posted.toSet.subsetOf(hashed.toSet)))
    }
  }

  test("retry mode - should switch to normal mode if cap >= enqueued count, all sent and no stalled") { res =>
    implicit val (_, hs, sp, metrics) = res

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
        _ <- hashed.traverse(binary => sender.process(binary, List.empty, none))

        globalSnapshot <- mkSnapshot(SnapshotOrdinal(5L), kp, List.empty)
        _ <- sender.confirm(globalSnapshot)
        capReachedButNoSendsSoFar <- stateRef.get

        _ <- stateRef.update { state =>
          state.copy(
            tracked = state.tracked.map {
              case pending @ PendingBinary(_, _, _) => pending.copy(sendsSoFar = NonNegLong(1L), enqueuedAtOrdinal = SnapshotOrdinal(0L))
              case confirmed                        => confirmed
            },
            cap = NonNegLong.unsafeFrom(state.tracked.length.toLong)
          )
        }
        _ <- sender.confirm(globalSnapshot)
        capReachedAllSentButHasStalled <- stateRef.get

        _ <- stateRef.update { state =>
          state.copy(
            tracked = state.tracked.map {
              case pending @ PendingBinary(_, _, _) => pending.copy(sendsSoFar = NonNegLong(1L), enqueuedAtOrdinal = SnapshotOrdinal(1L))
              case confirmed                        => confirmed
            },
            cap = NonNegLong.unsafeFrom(state.tracked.length.toLong)
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
    implicit val (_, hs, sp, metrics) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, postedRef) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = State.empty.copy(retryMode = true)
        )
        hashed <- binaries.traverse(_.toHashed)
        _ <- hashed.traverse(binary => sender.process(binary, List.empty, none))
        state <- stateRef.get
        posted <- postedRef.get
      } yield
        expect(state.tracked.nonEmpty)
          .and(expect(state.tracked.map {
            case PendingBinary(binary, _, _)       => binary
            case ConfirmedBinary(pendingBinary, _) => pendingBinary.binary
          }.toSet.subsetOf(hashed.toSet)))
          .and(expect(posted.isEmpty))
    }
  }

  test("retry mode - cap should decrement by 1 if no confirmations") { res =>
    implicit val (_, hs, sp, metrics) = res

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
          _ <- sender.process(hashedBinary, List.empty, none)
          globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, List.empty)
          prevState <- stateRef.get
          _ <- sender.confirm(globalSnapshot)
          state <- stateRef.get
        } yield expect.eql(state.cap.value, prevState.cap.value - 1)
    }
  }

  test("retry mode - cap should increment with every confirmation but no more than 4*confirmedCount") { res =>
    implicit val (_, hs, sp, metrics) = res

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

          _ <- binaries.traverse_(bin => bin.toHashed.flatMap(binary => sender.process(binary, List.empty, none)))
          globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, confirmedBinaries)
          prevState <- stateRef.get
          _ <- sender.confirm(globalSnapshot)
          state <- stateRef.get
        } yield expect(state.cap.value >= prevState.cap.value).and(expect(state.cap.value <= confirmedBinaries.length * 4))
    }
  }

  test("retry mode - should switch to exponential mode when cap goes to 0") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(binaryGen) { binary =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        (sender, stateRef, _) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = SnapshotOrdinal.MinValue,
          state = State.empty.copy(cap = 1L, retryMode = true)
        )
        hashedBinary <- binary.toHashed
        _ <- sender.process(hashedBinary, List.empty, none)
        globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, List.empty)
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
    implicit val (_, hs, sp, metrics) = res

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
          _ <- sender.process(hashedBinary, List.empty, none)

          expectedNoConfirmationsToRetry = Math.pow(2.0, exponent.value.toDouble).toLong
          snapshots <- mkEmptySnapshots(expectedNoConfirmationsToRetry, kp)
          info = mkGlobalSnapshotInfo()
          lessThanNeeded = snapshots.take(expectedNoConfirmationsToRetry.toInt - 2)
          _ <- lessThanNeeded.traverse(snapshot => sender.confirm(snapshot) >> sender.processPending(snapshot, info))

          postedAfterSendingLessThanNeeded <- postedRef.get

          _ <- sender.confirm(snapshots.last) >> sender.processPending(snapshots.last, info)

          postedAfterSendingLast <- postedRef.get

        } yield expect(postedAfterSendingLessThanNeeded.isEmpty).and(expect.eql(postedAfterSendingLast.length, 1))
    }
  }

  test("retry mode (exponential) - increments exponent if passed 2^n without confirmations and resets counter") { res =>
    implicit val (_, hs, sp, metrics) = res

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
          _ <- sender.process(hashedBinary, List.empty, none)
          snapshot <- mkSnapshot(ordinal = SnapshotOrdinal.MinValue, kp, List.empty)
          prevState <- stateR.get
          info = mkGlobalSnapshotInfo()
          _ <- sender.confirm(snapshot) >> sender.processPending(snapshot, info)
          state <- stateR.get
        } yield
          expect
            .eql(state.backoffExponent.value, prevState.backoffExponent.value + 1L)
            .and(expect.eql(state.noConfirmationsSinceRetryCount, NonNegLong(1L)))
            .and(expect.eql(state.cap, NonNegLong(0L)))
    }
  }

  test("should reject when not on allowance list") { res =>
    implicit val (_, hs, sp, metrics) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      for {
        kp <- KeyPairGenerator.makeKeyPair
        currentOrdinal = SnapshotOrdinal.MinValue
        selfId = PeerId(Hex("0000000000000000"))
        allowed = PeerId(Hex("000000000000011"))
        allowanceList = Map(kp.getPublic.toAddress -> NonEmptySet.of(allowed))

        (sender, stateRef, postedRef) <- mkService(
          kp.getPublic.toAddress,
          currentOrdinal = currentOrdinal,
          state = State.empty,
          allowanceList.some,
          selfId,
          Mainnet
        )
        hashed <- binaries.traverse(_.toHashed)
        _ <- hashed.traverse(binaryHashed => sender.process(binaryHashed, List.empty, none))
        globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, binaries)
        _ <- sender.confirm(globalSnapshot)
        state <- stateRef.get
        expected = hashed.map { binary =>
          ConfirmedBinary(
            PendingBinary(binary, currentOrdinal, NonNegLong(0L)),
            GlobalSnapshotConfirmationProof.fromGlobalSnapshot(globalSnapshot)
          )
        }
        posted <- postedRef.get
      } yield
        expect.all(
          state.tracked.toList === expected,
          posted.size === 0
        )
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
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer1 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners1,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer2 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners2,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer3 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners3,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer4 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners4,
          allowedPeers,
          selfId,
          Hash.empty
        )
      )
      selectedPeer5 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
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
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer1 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners1,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer2 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners2,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer3 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners3,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer4 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners4,
          List.empty,
          selfId,
          Hash.empty
        )
      )
      selectedPeer5 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
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
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer1 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners1,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer2 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners2,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer3 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners3,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer4 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
          lastSigners4,
          List.empty,
          selfId,
          Hash("123")
        )
      )
      selectedPeer5 <- IO.pure(
        StateChannelBinarySender.pickDeterministicPeer(
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

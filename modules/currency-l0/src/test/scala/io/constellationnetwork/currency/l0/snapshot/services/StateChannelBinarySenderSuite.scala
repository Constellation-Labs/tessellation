package io.constellationnetwork.currency.l0.snapshot.services

import java.security.KeyPair

import cats.data._
import cats.effect._
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.snapshot.storage.{RecoverySyncPublicationStorage, StateChannelBinaryOutboxStorage}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotStateProof, SnapshotFee}
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncOrdinal}
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment.{Dev, Mainnet}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.generators.nonEmptyStringGen
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.clients.StateChannelSnapshotClient
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.RecoveryGlobalSnapshotSync.ResetInheritedMultiPeerView
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSentGlobalSnapshotSyncStorage.RequiredRecoveryRefresh
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{IdentifierStorage, LastSentGlobalSnapshotSyncStorage}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.generators.{chooseNumRefined, signedOf}
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.schema.{GlobalStateProofSelector, _}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import fs2.Stream
import fs2.io.file.Files
import org.scalacheck.Gen
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object StateChannelBinarySenderSuite extends MutableIOSuite with Checkers {
  implicit val globalStateProofSelector: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  private val receiptProof = SignatureProof(Id(Hex("a" * 128)), Signature(Hex("b" * 128)))

  private def currencyArtifact(ordinal: Long, hash: String): Hashed[CurrencyIncrementalSnapshot] =
    Hashed(
      Signed(
        CurrencyIncrementalSnapshot(
          ordinal = SnapshotOrdinal.unsafeApply(ordinal),
          height = Height.MinValue,
          subHeight = SubHeight.MinValue,
          lastSnapshotHash = Hash.empty,
          blocks = SortedSet.empty,
          rewards = SortedSet.empty,
          tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
          stateProof = CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
          epochProgress = EpochProgress.MinValue,
          dataApplication = None,
          messages = None,
          globalSnapshotSyncs = None,
          feeTransactions = None,
          artifacts = None,
          allowSpendBlocks = None,
          tokenLockBlocks = None,
          globalSyncView = None
        ),
        NonEmptySet.one(receiptProof)
      ),
      Hash(hash),
      ProofsHash(s"$hash-proofs")
    )

  private val requiredRecoveryRefresh = RequiredRecoveryRefresh(
    Signed(
      GlobalSnapshotSync(
        GlobalSnapshotSyncOrdinal.MinValue,
        SnapshotOrdinal.unsafeApply(100L),
        Hash("global-100"),
        SessionToken(Generation(PosLong.unsafeFrom(2L)))
      ),
      NonEmptySet.one(receiptProof)
    ),
    ResetInheritedMultiPeerView,
    SnapshotOrdinal.unsafeApply(147L)
  )

  private def recoveryBinary(discriminator: Byte)(implicit hasher: Hasher[IO]): IO[Hashed[StateChannelSnapshotBinary]] =
    Signed(
      StateChannelSnapshotBinary(Hash.empty, Array[Byte](discriminator, 2, 3), SnapshotFee.MinValue),
      NonEmptySet.one(receiptProof)
    ).toHashed

  def mkSnapshot(ordinal: SnapshotOrdinal, keyPair: KeyPair, confirmedBinaries: List[Signed[StateChannelSnapshotBinary]])(
    implicit hs: Hasher[IO],
    sp: SecurityProvider[IO],
    globalStateProofSelector: GlobalStateProofSelector,
    js: JsonSerializer[IO]
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

  // A cluster storage that reports a configurable set of responsive peers (default: none, i.e. only self is alive).
  private def clusterStorage(responsive: Set[Peer] = Set.empty): ClusterStorage[IO] =
    new ClusterStorage[IO] {
      def getPeers: IO[Set[Peer]] = responsive.pure[IO]
      def getResponsivePeers: IO[Set[Peer]] = responsive.pure[IO]
      def getPeer(id: PeerId): IO[Option[Peer]] = none[Peer].pure[IO]
      def addPeer(peer: Peer): IO[Boolean] = ???
      def hasPeerId(id: PeerId): IO[Boolean] = ???
      def hasPeerHostPort(host: Host, p2pPort: Port): IO[Boolean] = ???
      def updatePeerState(id: PeerId, state: NodeState): IO[Boolean] = ???
      def setPeerResponsiveness(id: PeerId, responsiveness: PeerResponsiveness): IO[Unit] = ???
      def removePeer(id: PeerId): IO[Unit] = ???
      def removePeers(ids: Set[PeerId]): IO[Unit] = ???
      def peerChanges: Stream[IO, Ior[Peer, Peer]] = Stream.empty
      def createToken: IO[ClusterSessionToken] = ???
      def getToken: IO[Option[ClusterSessionToken]] = none[ClusterSessionToken].pure[IO]
      def setToken(token: ClusterSessionToken): IO[Unit] = ???
      def getClusterId: ClusterId = ???
    }

  /** Build the REAL StateChannelBinarySenderImpl with synchronous send scheduling, so that ordering and re-send behaviour are observable
    * deterministically (no fire-and-forget races in the test).
    */
  def mkSender(
    identifier: Address,
    enqueueAtOrdinal: SnapshotOrdinal,
    state: TrackerState,
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]] = None,
    selfId: PeerId = PeerId(Hex("0000000000000000")),
    environment: AppEnvironment = Dev,
    maxTrackedBinaries: Int = 10000,
    publishingEnabled: Boolean = true,
    nodeMayPublish: Boolean = true,
    recoveryStorage: Option[RecoverySyncPublicationStorage[IO]] = None,
    outboxStorage: Option[StateChannelBinaryOutboxStorage[IO]] = None,
    beforePost: IO[Unit] = IO.unit,
    onRecoveryPublicationConfirmed: IO[Unit] = IO.unit,
    onCanonicalMismatch: StateChannelBinaryOutboxStorage.CanonicalTipMismatch => IO[Unit] = _.raiseError[IO, Unit]
  )(
    implicit hs: Hasher[IO],
    metrics: Metrics[IO]
  ): Resource[IO, (StateChannelBinarySenderImpl[IO], BinaryTracker[IO], Ref[IO, List[Hashed[StateChannelSnapshotBinary]]])] =
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
        def removePeer(id: PeerId): IO[Unit] = IO.unit
      }

      lastSnapshotStorage = new LastSnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
        def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] = ???
        def setInitial(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] = ???
        def get: IO[Option[Hashed[GlobalIncrementalSnapshot]]] = ???
        def getCombined: IO[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = none.pure[IO]
        def getCombinedStream: fs2.Stream[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???
        def getOrdinal: IO[Option[SnapshotOrdinal]] = enqueueAtOrdinal.some.pure[IO]
        def getHeight: IO[Option[Height]] = ???
        def clear: IO[Unit] = ().pure[IO]
        def setForRecovery(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] = ().pure[IO]
      }

      postedRef <- Resource.eval(Ref.of[IO, List[Hashed[StateChannelSnapshotBinary]]](List.empty))

      stateChannelSnapshotClient = new StateChannelSnapshotClient[IO] {
        def send(
          identifier: Address,
          data: Signed[StateChannelSnapshotBinary]
        ): PeerResponse[IO, Either[NonEmptyList[StateChannelValidationError], Unit]] =
          Kleisli[IO, P2PContext, Either[NonEmptyList[StateChannelValidationError], Unit]] { _ =>
            beforePost >> data.toHashed.flatMap { hashed =>
              postedRef.update(_ :+ hashed).map(_.asRight[NonEmptyList[StateChannelValidationError]])
            }
          }
      }

      tracker <- Resource.eval(BinaryTracker.make[IO](maxTrackedBinaries))
      _ <- Resource.eval(tracker.updateState(_ => state))
      publicationEnabled <- Resource.eval(Ref.of[IO, Boolean](publishingEnabled))

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

      logger = Slf4jLogger.getLogger[IO]

      sender = new StateChannelBinarySenderImpl[IO](
        tracker,
        poster,
        lastSnapshotStorage,
        identifierStorage,
        clusterStorage(),
        selfId,
        maxTrackedBinaries,
        // Run posting synchronously so the test can observe send ordering / re-sends deterministically.
        identity,
        logger,
        recoverySyncPublicationStorage = recoveryStorage,
        stateChannelBinaryOutboxStorage = outboxStorage,
        publicationEnabled = publicationEnabled,
        nodeMayPublish = nodeMayPublish.pure[IO],
        onRecoveryPublicationConfirmed = onRecoveryPublicationConfirmed,
        onCanonicalMismatch = onCanonicalMismatch
      )
    } yield (sender, tracker, postedRef)

  type Res = (KryoSerializer[IO], Hasher[IO], SecurityProvider[IO], Metrics[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      h = Hasher.forJson[IO]
      metrics <- Metrics.forAsync[IO](Seq.empty)
    } yield (ks, h, sp, metrics, j)

  def binaryGen: Gen[Signed[StateChannelSnapshotBinary]] =
    for {
      hash <- Hash.arbitrary.arbitrary
      content <- nonEmptyStringGen
      signedBinary <- signedOf(StateChannelSnapshotBinary(hash, content.getBytes, SnapshotFee.MinValue))
    } yield signedBinary

  // ---------------------------------------------------------------------------------------------------------------
  // Production path: normal-mode sending, confirmation + pruning (exercising the REAL impl, not a divergent double)
  // ---------------------------------------------------------------------------------------------------------------

  test("normal mode - posts every pending binary exactly once, in chain order") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    // Keep the list within the normal-mode window (10) so we can assert the full, ordered set was posted.
    val gen = Gen.choose(1, 8).flatMap(n => Gen.listOfN(n, binaryGen))

    forall(gen) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, _, postedRef) <- mkSender(kp.getPublic.toAddress, SnapshotOrdinal(1L), TrackerState.empty)
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse_(b => sender.enqueue(b, SnapshotOrdinal(1L), none))
            _ <- sender.processQueueWithoutSnapshot
            posted <- postedRef.get
          } yield expect.eql(posted.map(_.hash), hashed.map(_.hash))
        )
      } yield result).use(IO.pure)
    }
  }

  test("run-rollback publication gate prevents stale posts until cleanup explicitly enables it") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(binaryGen) { binary =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, _, postedRef) <- mkSender(
          kp.getPublic.toAddress,
          SnapshotOrdinal(1L),
          TrackerState.empty,
          publishingEnabled = false
        )
        result <- Resource.eval(for {
          hashed <- binary.toHashed
          _ <- sender.enqueue(hashed, SnapshotOrdinal(1L), none)
          _ <- sender.processQueueWithoutSnapshot
          before <- postedRef.get
          _ <- sender.enablePublishing
          _ <- sender.processQueueWithoutSnapshot
          after <- postedRef.get
        } yield expect.all(before.isEmpty, after.map(_.hash) === List(hashed.hash)))
      } yield result).use(IO.pure)
    }
  }

  test("canonical replacement waits for an in-flight publication before the gate closes") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(binaryGen) { binary =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        sendStarted <- Resource.eval(Deferred[IO, Unit])
        releaseSend <- Resource.eval(Deferred[IO, Unit])
        disableFinished <- Resource.eval(Ref.of[IO, Boolean](false))
        (sender, _, postedRef) <- mkSender(
          kp.getPublic.toAddress,
          SnapshotOrdinal(1L),
          TrackerState.empty,
          beforePost = sendStarted.complete(()).void >> releaseSend.get
        )
        result <- Resource.eval(for {
          hashed <- binary.toHashed
          _ <- sender.enqueue(hashed, SnapshotOrdinal(1L), none)
          sendFiber <- sender.processQueueWithoutSnapshot.start
          _ <- sendStarted.get
          disableFiber <- sender.disablePublishing.guarantee(disableFinished.set(true)).start
          _ <- IO.sleep(100.millis)
          finishedWhileSendBlocked <- disableFinished.get
          _ <- releaseSend.complete(())
          _ <- sendFiber.joinWithNever
          _ <- disableFiber.joinWithNever
          finishedAfterDrain <- disableFinished.get
          _ <- sender.processQueueWithoutSnapshot
          posted <- postedRef.get
        } yield expect.all(!finishedWhileSendBlocked, finishedAfterDrain, posted.map(_.hash) === List(hashed.hash)))
      } yield result).use(IO.pure)
    }
  }

  test("exact GL0 recovery confirmation clears both the receipt and construction guard exactly once") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    Files[IO].tempDirectory.use { directory =>
      for {
        storage <- RecoverySyncPublicationStorage.make[IO](directory)
        guard <- LastSentGlobalSnapshotSyncStorage.make[IO]()
        callbackCount <- Ref.of[IO, Int](0)
        binary <- recoveryBinary(21)
        _ <- storage.prepare(requiredRecoveryRefresh, binary, currencyArtifact(21L, "currency-21"))
        _ <- storage.markLocallyCommitted(binary.hash)
        _ <- guard.armRecoveryRefresh(requiredRecoveryRefresh)
        result <- (for {
          kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
          (sender, _, _) <- mkSender(
            kp.getPublic.toAddress,
            SnapshotOrdinal(1L),
            TrackerState.empty,
            publishingEnabled = false,
            recoveryStorage = storage.some,
            onRecoveryPublicationConfirmed = guard.clearRequiredRecoveryRefresh >> callbackCount.update(_ + 1)
          )
          assertions <- Resource.eval(
            for {
              _ <- sender.confirmRecoveryPublication(Set(Hash("other")), SnapshotOrdinal.unsafeApply(200L))
              beforeReceipt <- storage.get
              beforeGuard <- guard.getRequiredRecoveryRefresh
              beforeCount <- callbackCount.get
              _ <- sender.confirmRecoveryPublication(Set(binary.hash), SnapshotOrdinal.unsafeApply(201L))
              afterReceipt <- storage.get
              afterGuard <- guard.getRequiredRecoveryRefresh
              afterCount <- callbackCount.get
              _ <- sender.confirmRecoveryPublication(Set(binary.hash), SnapshotOrdinal.unsafeApply(202L))
              finalCount <- callbackCount.get
            } yield
              expect.all(
                beforeReceipt.nonEmpty,
                beforeGuard.nonEmpty,
                beforeCount === 0,
                afterReceipt.isEmpty,
                afterGuard.isEmpty,
                afterCount === 1,
                finalCount === 1
              )
          )
        } yield assertions).use(IO.pure)
      } yield result
    }
  }

  test("an expired recovery binary cannot be rearmed through the ordinary durable outbox") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    Files[IO].tempDirectory.use { directory =>
      val recoveryDirectory = directory / "recovery"
      val outboxDirectory = directory / "outbox"

      for {
        _ <- Files[IO].createDirectories(recoveryDirectory)
        _ <- Files[IO].createDirectories(outboxDirectory)
        recovery <- RecoverySyncPublicationStorage.make[IO](recoveryDirectory)
        outbox <- StateChannelBinaryOutboxStorage.make[IO](outboxDirectory)
        binary <- recoveryBinary(22)
        artifact = currencyArtifact(22L, "currency-22")
        _ <- recovery.prepare(requiredRecoveryRefresh, binary, artifact)
        _ <- recovery.markLocallyCommitted(binary.hash)
        _ <- outbox.prepare(binary, artifact)
        _ <- outbox.markLocallyCommitted(binary.hash)
        _ <- recovery.expireAt(SnapshotOrdinal.unsafeApply(requiredRecoveryRefresh.validThroughGlobalParent.value.value + 1L))
        kp <- KeyPairGenerator.makeKeyPair
        first <- mkSender(
          kp.getPublic.toAddress,
          SnapshotOrdinal(1L),
          TrackerState.empty,
          publishingEnabled = false,
          recoveryStorage = recovery.some,
          outboxStorage = outbox.some
        ).use {
          case (sender, tracker, _) =>
            sender.clearPending >> tracker.getState
        }
        second <- mkSender(
          kp.getPublic.toAddress,
          SnapshotOrdinal(1L),
          TrackerState.empty,
          publishingEnabled = false,
          recoveryStorage = recovery.some,
          outboxStorage = outbox.some
        ).use {
          case (sender, tracker, _) =>
            sender.refillFromOutbox >> tracker.getState
        }
        receipt <- recovery.get
        stats <- outbox.stats
      } yield
        expect.all(
          first.tracked.isEmpty,
          second.tracked.isEmpty,
          receipt.exists(_.expired),
          stats.pendingCount === 1
        )
    }
  }

  test("an enabled queue still cannot post while the node is outside Ready") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(binaryGen) { binary =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, _, postedRef) <- mkSender(
          kp.getPublic.toAddress,
          SnapshotOrdinal(1L),
          TrackerState.empty,
          publishingEnabled = true,
          nodeMayPublish = false
        )
        result <- Resource.eval(for {
          hashed <- binary.toHashed
          _ <- sender.enqueue(hashed, SnapshotOrdinal(1L), none)
          _ <- sender.processQueueWithoutSnapshot
          posted <- postedRef.get
        } yield expect(posted.isEmpty))
      } yield result).use(IO.pure)
    }
  }

  test("confirm marks the chain prefix confirmed and prunes it from the queue") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, _) <- mkSender(kp.getPublic.toAddress, SnapshotOrdinal(1L), TrackerState.empty)
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse_(b => sender.enqueue(b, SnapshotOrdinal(1L), none))
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, binaries)
            _ <- sender.confirm(globalSnapshot)
            state <- tracker.getState
          } yield expect(state.tracked.isEmpty).and(expect(state.inFlight.isEmpty))
        )
      } yield result).use(IO.pure)
    }
  }

  test("confirm with no matching binaries leaves the queue untouched") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, _) <- mkSender(kp.getPublic.toAddress, SnapshotOrdinal(1L), TrackerState.empty)
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse_(b => sender.enqueue(b, SnapshotOrdinal(1L), none))
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, List.empty)
            _ <- sender.confirm(globalSnapshot)
            state <- tracker.getState
          } yield expect.eql(state.tracked.size, hashed.size)
        )
      } yield result).use(IO.pure)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // F4 — re-send until *confirmed*, not until merely delivered once
  // ---------------------------------------------------------------------------------------------------------------

  test("re-sends an unconfirmed binary on a later tick (delivery is not acceptance)") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(binaryGen) { binary =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, _, postedRef) <- mkSender(kp.getPublic.toAddress, SnapshotOrdinal(1L), TrackerState.empty)
        result <- Resource.eval(
          for {
            hashed <- binary.toHashed
            _ <- sender.enqueue(hashed, SnapshotOrdinal(1L), none)
            s1 <- mkSnapshot(SnapshotOrdinal(1L), kp, List.empty)
            s3 <- mkSnapshot(SnapshotOrdinal(3L), kp, List.empty)
            _ <- sender.processQueue(s1) // first attempt, stamps lastAttempt = 1
            _ <- sender.processQueue(s3) // ordinal advanced by >= resend interval -> re-send
            posted <- postedRef.get
          } yield expect.eql(posted.count(_.hash === hashed.hash), 2)
        )
      } yield result).use(IO.pure)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Retry mode escalation + chain ordering
  // ---------------------------------------------------------------------------------------------------------------

  test("retry mode escalates: every permitted node posts the stalled binaries, in chain order") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, postedRef) <- mkSender(kp.getPublic.toAddress, SnapshotOrdinal(1L), TrackerState.empty)
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse_(b => sender.enqueue(b, SnapshotOrdinal(1L), none))
            _ <- tracker.updateState(_.copy(retryMode = true, cap = NonNegLong.unsafeFrom(binaries.length.toLong)))
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(2L), kp, List.empty)
            _ <- sender.processQueue(globalSnapshot)
            posted <- postedRef.get
          } yield expect.eql(posted.map(_.hash), hashed.map(_.hash)) // posted in enqueue/chain order
        )
      } yield result).use(IO.pure)
    }
  }

  test("does not post when not on the allowance list (Mainnet, self excluded)") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(Gen.nonEmptyListOf(binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        selfId = PeerId(Hex("0000000000000000"))
        allowed = PeerId(Hex("000000000000011"))
        allowanceList = Map(kp.getPublic.toAddress -> NonEmptySet.of(allowed))
        (sender, _, postedRef) <- mkSender(
          kp.getPublic.toAddress,
          SnapshotOrdinal(1L),
          TrackerState.empty.copy(retryMode = true, cap = NonNegLong.unsafeFrom(binaries.length.toLong)),
          allowanceList.some,
          selfId,
          Mainnet
        )
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            _ <- hashed.traverse_(b => sender.enqueue(b, SnapshotOrdinal(1L), none))
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(2L), kp, List.empty)
            _ <- sender.processQueue(globalSnapshot) // even retry-mode escalation must respect the allowance gate
            posted <- postedRef.get
          } yield expect.eql(posted.size, 0)
        )
      } yield result).use(IO.pure)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Retry FSM transitions (unchanged logic, exercised through the REAL atomic confirm)
  // ---------------------------------------------------------------------------------------------------------------

  test("transitions to retry mode when a binary is unconfirmed for >= 5 ordinals") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(binaryGen) { binary =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, _) <- mkSender(kp.getPublic.toAddress, SnapshotOrdinal(1L), TrackerState.empty)
        result <- Resource.eval(
          for {
            hashed <- binary.toHashed
            _ <- sender.enqueue(hashed, SnapshotOrdinal(1L), none) // enqueuedAt = 1 (not MinValue)
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(6L), kp, List.empty)
            _ <- sender.confirm(globalSnapshot)
            state <- tracker.getState
          } yield expect(state.retryMode)
        )
      } yield result).use(IO.pure)
    }
  }

  test("does NOT enter retry mode for a not-yet-anchored binary (enqueuedAt == MinValue)") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(binaryGen) { binary =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        // enqueueAtOrdinal = MinValue simulates startup before any global snapshot is known
        (sender, tracker, _) <- mkSender(kp.getPublic.toAddress, SnapshotOrdinal.MinValue, TrackerState.empty)
        result <- Resource.eval(
          for {
            hashed <- binary.toHashed
            _ <- sender.enqueue(hashed, SnapshotOrdinal(1L), none)
            globalSnapshot <- mkSnapshot(SnapshotOrdinal(6L), kp, List.empty)
            _ <- sender.confirm(globalSnapshot)
            state <- tracker.getState
          } yield expect(!state.retryMode)
        )
      } yield result).use(IO.pure)
    }
  }

  test("retry mode - cap decrements by 1 on a confirmation-less ordinal") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    val gen = for {
      binary <- binaryGen
      cap <- chooseNumRefined(NonNegLong.unsafeFrom(2L), NonNegLong.unsafeFrom(100L))
    } yield (binary, cap)

    forall(gen) {
      case (binary, cap) =>
        (for {
          kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
          (sender, tracker, _) <- mkSender(
            kp.getPublic.toAddress,
            SnapshotOrdinal(1L),
            TrackerState.empty.copy(cap = cap, retryMode = true)
          )
          result <- Resource.eval(
            for {
              hashed <- binary.toHashed
              _ <- sender.enqueue(hashed, SnapshotOrdinal(1L), none)
              globalSnapshot <- mkSnapshot(SnapshotOrdinal(1L), kp, List.empty)
              prevState <- tracker.getState
              _ <- sender.confirm(globalSnapshot)
              state <- tracker.getState
            } yield expect.eql(state.cap.value, prevState.cap.value - 1)
          )
        } yield result).use(IO.pure)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // F5 — never go silent: cap floors at 1 (silence would guarantee the external 5-min health restart)
  // ---------------------------------------------------------------------------------------------------------------

  test("retry mode never silences: cap floors at 1 even after a long confirmation drought") { res =>
    implicit val (_, hs, sp, _, _) = res

    forall(binaryGen) { binary =>
      for {
        hashed <- binary.toHashed
        // Already at the minimum budget, retry mode, no confirmations: cap must STAY at 1 (never 0), so the head
        // keeps being posted every tick. The stall exponent advances (clamped) only for observability.
        pending = PendingBinary(hashed, SnapshotOrdinal(1L), SnapshotOrdinal(1L), NonNegLong.unsafeFrom(1L), none)
        stalled = TrackerState.empty.copy(
          tracked = scala.collection.immutable.Queue[TrackedBinary](pending),
          cap = NonNegLong.unsafeFrom(1L),
          retryMode = true,
          backoffExponent = NonNegLong.unsafeFrom(6L)
        )
        // Apply the transition repeatedly to simulate many confirmation-less ordinals.
        afterMany = (1 to 20).foldLeft(stalled)((s, _) => RetryStrategy.updateRetryParameters(s, previousRetryMode = true))
      } yield
        expect
          .eql(afterMany.cap.value, 1L)
          .and(expect(afterMany.backoffExponent.value <= 6L))
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // F6 — bounded queue (backpressure instead of unbounded growth -> OOM/restart loop)
  // ---------------------------------------------------------------------------------------------------------------

  test("enqueue is bounded: drops (returns false) once the queue is full") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(Gen.listOfN(5, binaryGen)) { binaries =>
      (for {
        tracker <- Resource.eval(BinaryTracker.make[IO](maxTrackedBinaries = 3))
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            outcomes <- hashed.traverse(b => tracker.enqueue(b, SnapshotOrdinal(1L), SnapshotOrdinal(1L)))
            state <- tracker.getState
          } yield
            expect
              .eql(state.tracked.size, 3)
              .and(expect.eql(outcomes.count(identity), 3))
              .and(expect.eql(outcomes.count(o => !o), 2))
        )
      } yield result).use(IO.pure)
    }
  }

  test("enqueue replays are idempotent for pending and confirmed binaries even at capacity") { res =>
    implicit val (_, hs, sp, _, _) = res

    forall(binaryGen) { binary =>
      for {
        tracker <- BinaryTracker.make[IO](maxTrackedBinaries = 1)
        hashed <- binary.toHashed
        first <- tracker.enqueue(hashed, SnapshotOrdinal(1L), SnapshotOrdinal(2L))
        pendingReplay <- tracker.enqueue(hashed, SnapshotOrdinal(10L), SnapshotOrdinal(20L))
        pendingState <- tracker.getState
        proof = GlobalSnapshotConfirmationProof(Hash("confirmed"), SnapshotOrdinal(3L), EpochProgress.MinValue)
        _ <- tracker.updateState(state => BinaryTracker.markConfirmedUpToHighest(state, Set(hashed.hash), proof))
        confirmedReplay <- tracker.enqueue(hashed, SnapshotOrdinal(30L), SnapshotOrdinal(40L))
        confirmedState <- tracker.getState
        expectedPending = PendingBinary(
          hashed,
          SnapshotOrdinal(1L),
          SnapshotOrdinal(2L),
          NonNegLong.MinValue,
          none
        )
      } yield
        expect(first)
          .and(expect(pendingReplay))
          .and(expect.eql(pendingState.tracked.toList, List[TrackedBinary](expectedPending)))
          .and(expect(confirmedReplay))
          .and(expect.eql(confirmedState.tracked.toList, List[TrackedBinary](ConfirmedBinary(expectedPending, proof))))
    }
  }

  test("sender enqueue backpressures at capacity and keeps the exact queued prefix") { res =>
    implicit val (_, hs, sp, metrics, j) = res

    forall(Gen.listOfN(6, binaryGen)) { binaries =>
      (for {
        kp <- Resource.eval(KeyPairGenerator.makeKeyPair)
        (sender, tracker, _) <- mkSender(kp.getPublic.toAddress, SnapshotOrdinal(1L), TrackerState.empty, maxTrackedBinaries = 4)
        result <- Resource.eval(
          for {
            hashed <- binaries.traverse(_.toHashed)
            accepted <- hashed.take(4).traverse_(b => sender.enqueue(b, SnapshotOrdinal(1L), none)).attempt
            rejected <- sender.enqueue(hashed(4), SnapshotOrdinal(1L), none).attempt
            state <- tracker.getState
          } yield
            expect(accepted.isRight)
              .and(expect(rejected.isLeft))
              .and(expect(rejected.swap.exists(_.getMessage.contains("queue is full"))))
              .and(
                expect
                  .eql(state.tracked.size, 4)
                  .and(expect.eql(state.tracked.collect { case p: PendingBinary => p.binary.hash }.toList, hashed.take(4).map(_.hash)))
              )
        )
      } yield result).use(IO.pure)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // F8 — per-binary in-flight de-duplication
  // ---------------------------------------------------------------------------------------------------------------

  test("tryBeginSend de-duplicates concurrent sends of the same binary") { res =>
    implicit val (_, hs, sp, _, _) = res

    forall(binaryGen) { binary =>
      for {
        tracker <- BinaryTracker.make[IO]()
        hashed <- binary.toHashed
        _ <- tracker.enqueue(hashed, SnapshotOrdinal(1L), SnapshotOrdinal(1L))
        first <- tracker.tryBeginSend(hashed.hash, none)
        second <- tracker.tryBeginSend(hashed.hash, none) // already in flight
        _ <- tracker.endSend(hashed.hash)
        third <- tracker.tryBeginSend(hashed.hash, none) // released -> allowed again
        unknown <- tracker.tryBeginSend(Hash("deadbeef"), none) // not pending -> refused
      } yield
        expect(first)
          .and(expect(!second))
          .and(expect(third))
          .and(expect(!unknown))
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // markConfirmedUpToHighest pure semantics (chain-prefix confirmation)
  // ---------------------------------------------------------------------------------------------------------------

  test("markConfirmedUpToHighest confirms every entry up to the highest confirmed index") { res =>
    implicit val (_, hs, sp, _, _) = res

    forall(Gen.listOfN(4, binaryGen)) { binaries =>
      for {
        hashed <- binaries.traverse(_.toHashed)
        pendings = hashed.map(b => PendingBinary(b, SnapshotOrdinal(1L), SnapshotOrdinal(1L), NonNegLong.MinValue, none))
        state = TrackerState.empty.copy(tracked = scala.collection.immutable.Queue[TrackedBinary](pendings: _*))
        // confirm only the *second* binary's hash -> entries at index 0 and 1 become confirmed, 2 and 3 stay pending
        proof = GlobalSnapshotConfirmationProof(Hash("aa"), SnapshotOrdinal(1L), EpochProgress.MinValue)
        confirmed = BinaryTracker.markConfirmedUpToHighest(state, Set(hashed(1).hash), proof)
        confirmedCount = confirmed.tracked.count(_.isInstanceOf[ConfirmedBinary])
        pendingCount = confirmed.tracked.count(_.isInstanceOf[PendingBinary])
      } yield expect.eql(confirmedCount, 2).and(expect.eql(pendingCount, 2))
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // F1/F2 — liveness-aware deterministic peer selection with self-fallback
  // ---------------------------------------------------------------------------------------------------------------

  test("liveness: a dead deterministic owner is skipped and a live peer (or self) takes over") { _ =>
    val a = PeerId(Hex("aaa"))
    val b = PeerId(Hex("bbb"))
    val c = PeerId(Hex("ccc"))
    val signers = List(a, b, c)
    // self is deliberately NOT a signer, so the self-fallback does not mask the "next live peer takes over" path.
    val self = PeerId(Hex("eee"))

    // The primary owner is computed over the full signer set (liveness-independent) and is alive here.
    val primary = PeerSelector.pickDeterministicPeer(signers, Nil, self, Hash("seed"), Some(Set(a, b, c)))
    // Kill the primary: a different, still-alive signer must take over (never silence).
    val stillAlive = Set(a, b, c) - primary
    val takeover = PeerSelector.pickDeterministicPeer(signers, Nil, self, Hash("seed"), Some(stillAlive))
    // Nobody among the signers is alive -> self takes over.
    val noneAlive = PeerSelector.pickDeterministicPeer(signers, Nil, self, Hash("seed"), Some(Set.empty[PeerId]))

    IO {
      expect(signers.contains(primary))
        .and(expect(takeover =!= primary))
        .and(expect(stillAlive.contains(takeover)))
        .and(expect.eql(noneAlive, self))
    }
  }

  test("liveness None degrades to pure deterministic selection (stable across nodes)") { _ =>
    val signers = List(PeerId(Hex("123")), PeerId(Hex("456")), PeerId(Hex("789")))
    val self = PeerId(Hex("123"))
    val a = PeerSelector.pickDeterministicPeer(signers, signers, self, Hash.empty, None)
    val b = PeerSelector.pickDeterministicPeer(signers.reverse, signers.reverse, self, Hash.empty, None)
    IO(expect.eql(a.value.value, b.value.value).and(expect.eql(a.value.value, "456")))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // F9 — empty eligible set must not make every node post (no thundering herd)
  // ---------------------------------------------------------------------------------------------------------------

  test("empty eligible set falls back to a single deterministic signer, not self") { _ =>
    val signers = List(PeerId(Hex("aaa")), PeerId(Hex("bbb")))
    val disjointAllowed = List(PeerId(Hex("ccc")), PeerId(Hex("ddd")))
    val self = PeerId(Hex("eee"))
    val selected = PeerSelector.pickDeterministicPeer(signers, disjointAllowed, self, Hash("seed"), None)
    IO(expect(signers.contains(selected)).and(expect(selected =!= self)))
  }
}

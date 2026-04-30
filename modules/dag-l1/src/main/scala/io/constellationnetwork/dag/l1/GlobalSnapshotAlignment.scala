package io.constellationnetwork.dag.l1

import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration.DurationInt

import io.constellationnetwork.dag.l1.domain.snapshot.programs.SnapshotProcessor.SnapshotProcessingResult
import io.constellationnetwork.dag.l1.modules._
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.security._

import fs2.Stream
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class GlobalSnapshotAlignment[F[_]: Async: HasherSelector: SecurityProvider, P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[
  P
], R <: CliMethod](
  services: Services[F, P, S, SI, R],
  programs: Programs[F, P, S, SI],
  storages: Storages[F, P, S, SI],
  sharedStorages: SharedStorages[F]
) {

  private val maxEpochProgressesBehind = 5L
  private implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  private def withRetry[A](
    operation: F[A],
    operationName: String,
    maxRetries: Int = 3
  ): F[A] = {
    import retry._

    retryingOnSomeErrors(
      policy = RetryPolicies.limitRetries[F](maxRetries),
      isWorthRetrying = (_: Throwable) => true.pure[F],
      onError = (err: Throwable, details: RetryDetails) => logger.warn(err)(s"$operationName failed on attempt ${details.retriesSoFar + 1}")
    )(operation).handleErrorWith { e =>
      logger.error(e)(s"$operationName failed after $maxRetries retries") >>
        Async[F].raiseError(e)
    }
  }

  def performCheckAlignment(): F[Unit] = {
    def checkSynchronization(
      lastGlobalSnapshotFromStorage: Hashed[GlobalIncrementalSnapshot],
      lastGlobalSnapshotFromNetwork: Hashed[GlobalIncrementalSnapshot]
    ) =
      if (
        lastGlobalSnapshotFromStorage.epochProgress.value.value + maxEpochProgressesBehind < lastGlobalSnapshotFromNetwork.epochProgress.value.value
      ) {
        val message = "Detected synchronization issue: TooFarEpochProgress. Forcing re-download"
        logger.info(message) >>
          storages.globalL0Alignment.updateShouldRedownload(
            value = true,
            reasons = List(message)
          )
      } else {
        ().pure
      }

    for {
      _ <- logger.info("Checking global snapshot alignment")
      maybeLastSnapshotOnStorage <- sharedStorages.lastGlobalSnapshot.get
      // Conditional fetch: when we have a local snapshot, send `If-None-Match: <localOrdinal>`.
      // 304 means the L0 peer is at the same ordinal we are — no possible TooFarEpochProgress
      // sync issue, no body to apply, no work needed. 200 means the peer has advanced; do the
      // existing epoch-progress comparison. Saves the ~60 MB combined-snapshot body on every
      // alignment cycle when the L1 is already aligned with L0 (the common steady-state).
      _ <- maybeLastSnapshotOnStorage match {
        case Some(lastSnapshotOnStorage) =>
          services.globalL0.pullLatestSnapshotIfNewer(lastSnapshotOnStorage.ordinal).flatMap {
            case None => Async[F].unit // 304 — already aligned, comparison redundant
            case Some((lastGlobalSnapshotFromNetwork, _)) =>
              checkSynchronization(lastSnapshotOnStorage, lastGlobalSnapshotFromNetwork)
          }
        case None =>
          val message = "Last snapshot not found on storage, forcing re-download!"
          logger.info(message) >>
            storages.globalL0Alignment.updateShouldRedownload(
              value = true,
              reasons = List(message)
            )
      }
    } yield ()
  }

  def performL0PeerDiscovery(): F[Unit] =
    storages.lastSnapshot.get.flatMap {
      case None =>
        storages.l0Cluster.getRandomPeer.flatMap(p => programs.l0PeerDiscovery.discoverFrom(p))
      case Some(latestSnapshot) =>
        programs.l0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
    }

  def performGlobalSnapshotProcessingUntilCaughtUp()(
    implicit stateProofSelector: GlobalStateProofSelector
  ): F[Unit] = {
    def loop(isFirstCall: Boolean): F[Unit] =
      performGlobalSnapshotProcessing().flatMap {
        case Left(_) if isFirstCall                => loop(isFirstCall = false)
        case Left(_)                               => ().pure
        case Right(snapshots) if snapshots.isEmpty => ().pure
        case Right(_)                              => loop(isFirstCall = false)
      }
    loop(isFirstCall = true)
  }

  def performGlobalSnapshotProcessing()(
    implicit stateProofSelector: StateProofSelector
  ): F[Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), List[Hashed[GlobalIncrementalSnapshot]]]] = {
    def logSnapshots(
      snapshots: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), List[Hashed[GlobalIncrementalSnapshot]]]
    ): F[Unit] = {
      def log(snapshot: Hashed[GlobalIncrementalSnapshot]) =
        logger.info(s"Pulled following global snapshot: ${SnapshotReference.fromHashedSnapshot(snapshot).show}")

      snapshots match {
        case Left((snapshot, _)) => log(snapshot)
        case Right(snapshots)    => snapshots.traverse(log).void
      }
    }

    def processSnapshots(
      snapshots: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), List[Hashed[GlobalIncrementalSnapshot]]]
    ): F[List[SnapshotProcessingResult]] =
      snapshots match {
        case Left((snapshot, state)) =>
          withRetry(
            operation = HasherSelector[F].withCurrent { implicit hasher =>
              programs.snapshotProcessor.process((snapshot, state).asLeft[Hashed[GlobalIncrementalSnapshot]]).map(List(_))
            },
            operationName = s"Process single snapshot ${SnapshotReference.fromHashedSnapshot(snapshot).show}"
          )
        case Right(snapshots) =>
          withRetry(
            operation = performSnapshotsBatchProcessing(snapshots),
            operationName = s"Process ${snapshots.size} snapshots batch"
          )
      }

    def logResults(results: List[SnapshotProcessingResult]): F[Unit] =
      results.traverse(result => logger.info(s"Snapshot processing result: ${result.show}")).void.handleErrorWith { e =>
        logger.warn(e)("Failed to log snapshot processing results")
      }

    for {
      snapshots <- withRetry(
        operation = services.globalL0.pullGlobalSnapshots,
        operationName = "Pull global snapshots"
      )
      _ <- logSnapshots(snapshots)
      results <- processSnapshots(snapshots)
      _ <- logResults(results)
    } yield snapshots
  }

  private def performSnapshotsBatchProcessing(
    snapshots: List[Hashed[GlobalIncrementalSnapshot]]
  )(
    implicit stateProofSelector: StateProofSelector
  ): F[List[SnapshotProcessingResult]] =
    (snapshots, List.empty[SnapshotProcessingResult]).tailRecM {
      case (snapshot :: nextSnapshots, aggResults) =>
        HasherSelector[F].withCurrent { implicit hasher =>
          programs.snapshotProcessor
            .process(snapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])
        }
          .map(result => (nextSnapshots, aggResults :+ result).asLeft[List[SnapshotProcessingResult]])
          .handleErrorWith { e =>
            val message = s"Failed to process snapshot ${SnapshotReference.fromHashedSnapshot(snapshot).show}, skipping"
            for {
              _ <- storages.globalL0Alignment.updateShouldRedownload(
                value = true,
                reasons = List(message)
              )
              _ <- logger.error(e)(message)
            } yield (nextSnapshots, aggResults).asLeft[List[SnapshotProcessingResult]]
          }

      case (Nil, aggResults) =>
        aggResults.asRight[(List[Hashed[GlobalIncrementalSnapshot]], List[SnapshotProcessingResult])].pure[F]
    }

  private def checkAlignment: Stream[F, Unit] = Stream
    .awakeEvery(1.minute)
    .evalMap { _ =>
      withRetry(
        operation = performCheckAlignment(),
        operationName = "Check alignment"
      )
    }
    .handleErrorWith {
      case e =>
        Stream.eval(logger.error(e)("Check alignment stream failed, restarting")) ++ checkAlignment
    }

  private def l0PeerDiscovery: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap { _ =>
      withRetry(
        operation = performL0PeerDiscovery(),
        operationName = "L0 peer discovery"
      )
    }
    .handleErrorWith { e =>
      Stream.eval(logger.error(e)("L0 peer discovery stream failed, restarting")) ++ l0PeerDiscovery
    }

  private def globalSnapshotProcessing(
    implicit stateProofSelector: GlobalStateProofSelector
  ): Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap { _ =>
      performGlobalSnapshotProcessing().void
    }
    .handleErrorWith { e =>
      Stream.eval(logger.error(e)("Global snapshot processing stream failed, restarting")) ++ globalSnapshotProcessing
    }

  def runtime()(
    implicit stateProofSelector: GlobalStateProofSelector
  ): Stream[F, Unit] =
    Stream(l0PeerDiscovery, globalSnapshotProcessing, checkAlignment)
      .covary[F]
      .parJoinUnbounded
}

object GlobalSnapshotAlignment {
  def make[F[_]: Async: HasherSelector: SecurityProvider, P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P], R <: CliMethod](
    services: Services[F, P, S, SI, R],
    programs: Programs[F, P, S, SI],
    storages: Storages[F, P, S, SI],
    sharedStorages: SharedStorages[F]
  ): GlobalSnapshotAlignment[F, P, S, SI, R] =
    new GlobalSnapshotAlignment[F, P, S, SI, R](services, programs, storages, sharedStorages)
}

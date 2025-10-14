package io.constellationnetwork.dag.l1

import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration.DurationInt

import io.constellationnetwork.dag.l1.domain.snapshot.programs.SnapshotProcessor.SnapshotProcessingResult
import io.constellationnetwork.dag.l1.modules._
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.security._

import fs2.Stream
import fs2.concurrent.SignallingRef
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
      isWorthRetrying = (_: Throwable) => true.pure[F], // Retry all errors
      onError = (err: Throwable, details: RetryDetails) => logger.warn(err)(s"$operationName failed on attempt ${details.retriesSoFar + 1}")
    )(operation).handleErrorWith { e =>
      logger.error(e)(s"$operationName failed after $maxRetries retries") >>
        Async[F].raiseError(e)
    }
  }

  private def checkSynchronization(
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

  private val checkAlignment: Stream[F, Unit] = Stream
    .awakeEvery(1.minute)
    .evalMap { _ =>
      withRetry(
        operation = for {
          _ <- logger.info("Checking global snapshot alignment")
          maybeLastSnapshotOnStorage <- sharedStorages.lastGlobalSnapshot.get
          lastCombinedGlobalSnapshotFromNetwork <- services.globalL0.pullLatestSnapshot
          _ <- maybeLastSnapshotOnStorage match {
            case Some(lastSnapshotOnStorage) =>
              val (lastGlobalSnapshotFromNetwork, _) = lastCombinedGlobalSnapshotFromNetwork
              checkSynchronization(lastSnapshotOnStorage, lastGlobalSnapshotFromNetwork)
            case None =>
              val message = "Last snapshot not found on storage, forcing re-download!"
              logger.info(message) >>
                storages.globalL0Alignment.updateShouldRedownload(
                  value = true,
                  reasons = List(message)
                )
          }
        } yield (),
        operationName = "Check alignment"
      )
    }
    .handleErrorWith { e =>
      Stream.eval(logger.error(e)("Check alignment stream failed, restarting")) ++ checkAlignment
    }

  private val l0PeerDiscovery: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap { _ =>
      withRetry(
        operation = storages.lastSnapshot.get.flatMap {
          case None =>
            storages.l0Cluster.getRandomPeer.flatMap(p => programs.l0PeerDiscovery.discoverFrom(p))
          case Some(latestSnapshot) =>
            programs.l0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
        },
        operationName = "L0 peer discovery"
      )
    }
    .handleErrorWith { e =>
      Stream.eval(logger.error(e)("L0 peer discovery stream failed, restarting")) ++ l0PeerDiscovery
    }

  private val globalSnapshotProcessing: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap { _ =>
      withRetry(
        operation = services.globalL0.pullGlobalSnapshots,
        operationName = "Pull global snapshots"
      )
    }
    .evalTap { snapshots =>
      def log(snapshot: Hashed[GlobalIncrementalSnapshot]) =
        logger.info(s"Pulled following global snapshot: ${SnapshotReference.fromHashedSnapshot(snapshot).show}")

      snapshots match {
        case Left((snapshot, _)) => log(snapshot)
        case Right(snapshots)    => snapshots.traverse(log).void
      }
    }
    .evalMap {
      case Left((snapshot, state)) =>
        withRetry(
          operation = HasherSelector[F].withCurrent { implicit hasher =>
            programs.snapshotProcessor.process((snapshot, state).asLeft[Hashed[GlobalIncrementalSnapshot]]).map(List(_))
          },
          operationName = s"Process single snapshot ${SnapshotReference.fromHashedSnapshot(snapshot).show}"
        )
      case Right(snapshots) =>
        withRetry(
          operation = (snapshots, List.empty[SnapshotProcessingResult]).tailRecM {
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
          },
          operationName = s"Process ${snapshots.size} snapshots batch"
        )
    }
    .evalMap { results =>
      results.traverse(result => logger.info(s"Snapshot processing result: ${result.show}")).void.handleErrorWith { e =>
        logger.warn(e)("Failed to log snapshot processing results")
      }
    }
    .handleErrorWith { e =>
      Stream.eval(logger.error(e)("Global snapshot processing stream failed, restarting")) ++ globalSnapshotProcessing
    }

  def runtime(): Stream[F, Unit] =
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
  ) = new GlobalSnapshotAlignment[F, P, S, SI, R](services, programs, storages, sharedStorages)
}

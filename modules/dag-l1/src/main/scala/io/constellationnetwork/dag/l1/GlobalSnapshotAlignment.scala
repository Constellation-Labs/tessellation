package io.constellationnetwork.dag.l1

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.concurrent.duration.DurationInt

import io.constellationnetwork.dag.l1.domain.snapshot.programs.SnapshotProcessor.SnapshotProcessingResult
import io.constellationnetwork.dag.l1.modules._
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.security._

import fs2.Stream
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class GlobalSnapshotAlignment[F[_]: Async: HasherSelector: SecurityProvider, P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P], R <: CliMethod](
  services: Services[F, P, S, SI, R],
  programs: Programs[F, P, S, SI],
  storages: Storages[F, P, S, SI]
) {

  private implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  private val l0PeerDiscovery: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap { _ =>
      storages.lastSnapshot.get.flatMap {
        case None =>
          storages.l0Cluster.getRandomPeer.flatMap(p => programs.l0PeerDiscovery.discoverFrom(p))
        case Some(latestSnapshot) =>
          programs.l0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
      }
    }

  private val globalSnapshotProcessing: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap(_ => services.globalL0.pullGlobalSnapshots)
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
        HasherSelector[F].withCurrent { implicit hasher =>
          programs.snapshotProcessor.process((snapshot, state).asLeft[Hashed[GlobalIncrementalSnapshot]]).map(List(_))
        }
      case Right(snapshots) =>
        (snapshots, List.empty[SnapshotProcessingResult]).tailRecM {
          case (snapshot :: nextSnapshots, aggResults) =>
            HasherSelector[F].withCurrent { implicit hasher =>
              programs.snapshotProcessor
                .process(snapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])
            }
              .map(result => (nextSnapshots, aggResults :+ result).asLeft[List[SnapshotProcessingResult]])

          case (Nil, aggResults) =>
            aggResults.asRight[(List[Hashed[GlobalIncrementalSnapshot]], List[SnapshotProcessingResult])].pure[F]
        }
    }
    .evalMap {
      _.traverse(result => logger.info(s"Snapshot processing result: ${result.show}")).void
    }

  val runtime = globalSnapshotProcessing.merge(l0PeerDiscovery)

}

object GlobalSnapshotAlignment {
  def make[F[_]: Async: HasherSelector: SecurityProvider, P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P], R <: CliMethod](
    services: Services[F, P, S, SI, R],
    programs: Programs[F, P, S, SI],
    storages: Storages[F, P, S, SI]
  ) = new GlobalSnapshotAlignment[F, P, S, SI, R](services, programs, storages)
}

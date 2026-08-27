package io.constellationnetwork.currency.l0.snapshot

import cats.MonadThrow
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataCalculatedState, L0NodeContext}
import io.constellationnetwork.currency.l0.http.p2p.DataApplicationClient
import io.constellationnetwork.currency.schema.CurrencyStateKey
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotContext, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.mempool.EventMempool
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencySnapshotArtifact, CurrencySnapshotEvent}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security.HasherSelector
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait CurrencySnapshotRecoveryStorage[F[_]] {
  def synchronize(artifact: Signed[CurrencySnapshotArtifact], context: CurrencySnapshotContext): F[Unit]
}

object CurrencySnapshotRecoveryStorage {

  final case class ConfigurationMismatch(hasArtifactState: Boolean, hasService: Boolean, hasStorage: Boolean) extends NoStackTrace {
    override def getMessage: String =
      s"Currency recovery calculated-state configuration mismatch: artifact=$hasArtifactState service=$hasService storage=$hasStorage"
  }

  final case class RecoveryModeMismatch(hasArtifactState: Boolean, hasCalculatedStateHooks: Boolean) extends NoStackTrace {
    override def getMessage: String =
      s"Currency recovery mode mismatch: artifact=$hasArtifactState calculatedStateHooks=$hasCalculatedStateHooks"
  }

  final case class CalculatedStateUnavailable(ordinal: SnapshotOrdinal, attemptedPeers: Int) extends NoStackTrace {
    override def getMessage: String =
      s"No peer served the calculated state certified at Currency snapshot ordinal=${ordinal.show}; attemptedPeers=$attemptedPeers"
  }

  final case class CalculatedStateProofMismatch(ordinal: SnapshotOrdinal, actual: Hash, expected: Hash) extends NoStackTrace {
    override def getMessage: String =
      s"Downloaded calculated state at ordinal=${ordinal.show} has hash=${actual.show}, expected=${expected.show}"
  }

  final case class CalculatedStateRejected(ordinal: SnapshotOrdinal) extends NoStackTrace {
    override def getMessage: String =
      s"Data application rejected the recovered calculated state at ordinal=${ordinal.show}"
  }

  private[snapshot] final case class CalculatedStateHooks[F[_], State](
    fetchExact: SnapshotOrdinal => F[State],
    hash: State => F[Hash],
    persist: (SnapshotOrdinal, State) => F[Unit]
  )

  /** Ordered fail-closed recovery seam. The snapshot head is never advanced until the exact calculated state has passed its certified proof
    * and has been persisted. Mempool clearing is last and is safe to repeat after a partial local failure.
    */
  private[snapshot] def synchronizeSteps[F[_]: MonadThrow, State](
    ordinal: SnapshotOrdinal,
    expectedProof: Option[Hash],
    calculatedState: Option[CalculatedStateHooks[F, State]],
    setSnapshotHead: F[Unit],
    clearMempool: F[Unit]
  ): F[Unit] =
    (expectedProof, calculatedState) match {
      case (None, None) => setSnapshotHead >> clearMempool
      case (Some(expected), Some(hooks)) =>
        for {
          state <- hooks.fetchExact(ordinal)
          actual <- hooks.hash(state)
          _ <- CalculatedStateProofMismatch(ordinal, actual, expected).raiseError[F, Unit].whenA(actual =!= expected)
          _ <- hooks.persist(ordinal, state)
          _ <- setSnapshotHead
          _ <- clearMempool
        } yield ()
      case _ =>
        RecoveryModeMismatch(expectedProof.nonEmpty, calculatedState.nonEmpty).raiseError[F, Unit]
    }

  def make[F[_]: Async: Random: HasherSelector](
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    clusterStorage: ClusterStorage[F],
    dataApplicationClient: DataApplicationClient[F],
    maybeDataApplication: Option[BaseDataApplicationL0Service[F]],
    maybeCalculatedStateStorage: Option[CalculatedStateLocalFileSystemStorage[F]],
    eventMempool: EventMempool[F, CurrencySnapshotEvent, CurrencyStateKey],
    nodeContext: L0NodeContext[F]
  ): CurrencySnapshotRecoveryStorage[F] =
    new CurrencySnapshotRecoveryStorage[F] {
      private val logger = Slf4jLogger.getLogger[F]
      private implicit val context: L0NodeContext[F] = nodeContext

      private def fetchExactCalculatedState(
        ordinal: SnapshotOrdinal,
        dataApplication: BaseDataApplicationL0Service[F]
      ): F[DataCalculatedState] = {
        implicit val decoder = dataApplication.calculatedStateDecoder

        clusterStorage.getResponsivePeers
          .map(NodeState.ready)
          .map(_.toList)
          .flatMap(Random[F].shuffleList)
          .flatMap { peers =>
            def go(remaining: List[io.constellationnetwork.schema.peer.Peer]): F[DataCalculatedState] =
              remaining match {
                case Nil => CalculatedStateUnavailable(ordinal, peers.size).raiseError[F, DataCalculatedState]
                case peer :: tail =>
                  dataApplicationClient
                    .getCalculatedState(ordinal)
                    .run(peer)
                    .flatMap(_.liftTo[F](CalculatedStateUnavailable(ordinal, 1)))
                    .handleErrorWith { error =>
                      logger.warn(error)(
                        s"[RecoveryDownload] Peer ${peer.id.value.value.take(8)} could not serve calculated state ordinal=${ordinal.show}"
                      ) >> go(tail)
                    }
              }

            go(peers)
          }
      }

      private def calculatedStateHooks(
        artifact: Signed[CurrencySnapshotArtifact]
      ): F[Option[CalculatedStateHooks[F, DataCalculatedState]]] =
        (artifact.dataApplication, maybeDataApplication, maybeCalculatedStateStorage) match {
          case (None, None, None) => none[CalculatedStateHooks[F, DataCalculatedState]].pure[F]
          case (Some(_), Some(dataApplication), Some(storage)) =>
            CalculatedStateHooks[F, DataCalculatedState](
              fetchExact = fetchExactCalculatedState(_, dataApplication),
              hash = dataApplication.hashCalculatedState,
              persist = (ordinal, state) =>
                storage.write(ordinal, state)(dataApplication.serializeCalculatedState) >>
                  dataApplication
                    .setCalculatedState(ordinal, state)
                    .flatMap(accepted => CalculatedStateRejected(ordinal).raiseError[F, Unit].unlessA(accepted))
            ).some.pure[F]
          case (artifactState, service, storage) =>
            ConfigurationMismatch(artifactState.nonEmpty, service.nonEmpty, storage.nonEmpty)
              .raiseError[F, Option[CalculatedStateHooks[F, DataCalculatedState]]]
        }

      def synchronize(artifact: Signed[CurrencySnapshotArtifact], context: CurrencySnapshotContext): F[Unit] =
        HasherSelector[F].withCurrent { implicit hasher =>
          for {
            _ <- logger.info(
              s"[RecoveryDownload] Aligning Currency application storage to newer accepted consensus outcome ordinal=${artifact.ordinal.show}"
            )
            hooks <- calculatedStateHooks(artifact)
            _ <- synchronizeSteps(
              artifact.ordinal,
              artifact.dataApplication.map(_.calculatedStateProof),
              hooks,
              snapshotStorage.setHeadForRecovery(artifact, context.snapshotInfo),
              eventMempool.clear
            )
          } yield ()
        }
    }
}

package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.StateT
import cats.effect.{Async, Clock}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import io.constellationnetwork.node.shared.config.types.ConsensusConfig
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class Previous[A](a: A)

object ConsensusStateAdvancer {}

trait ConsensusStateAdvancer[F[_], Key, Artifact, Context, Status, Outcome, Kind] {

  type State = ConsensusState[Key, Status, Outcome, Kind]
  type Resources = ConsensusResources[Artifact, Kind]

  def getConsensusOutcome(
    state: ConsensusState[Key, Status, Outcome, Kind]
  ): Option[(Previous[Key], Outcome)]

  def advanceStatus(resources: ConsensusResources[Artifact, Kind]): StateT[F, ConsensusState[Key, Status, Outcome, Kind], F[Unit]]

  protected def maybeGetAllDeclarations[A](
    state: State,
    resources: Resources,
    config: ConsensusConfig
  )(
    getter: PeerDeclarations => Option[A],
    staleTimer: Resources => Option[FiniteDuration]
  )(implicit asyncF: Async[F]): F[Option[SortedMap[PeerId, A]]] = {

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](ConsensusStateAdvancer.getClass)

    def processNonStale = {
      println(s"[CONSENSUS-ADVANCE] processNonStale: facilitators=${state.facilitators.value}")
      val result = state.facilitators.value.traverse { peerId =>
        val decl = resources.peerDeclarationsMap.get(peerId)
        val extracted = decl.flatMap(getter)
        println(s"[CONSENSUS-ADVANCE] processNonStale: peerId=$peerId, hasDecl=${decl.isDefined}, extracted=${extracted.isDefined}")
        extracted.map((peerId, _))
      }.map(SortedMap.from(_))
      println(s"[CONSENSUS-ADVANCE] processNonStale result=${result.isDefined}")
      result
    }

    def processStale = {
      val results = state.facilitators.value.flatMap { peerId =>
        resources.peerDeclarationsMap
          .get(peerId)
          .flatMap(getter)
          .map((peerId, _))
      }

      if (results.nonEmpty) Some(SortedMap.from(results))
      else None
    }

    for {
      now <- Clock[F].monotonic
      started = resources.createdAt
      latestUnique = staleTimer(resources)
      uniqueDelta = latestUnique.map(_ - started)
      elapsed = now - latestUnique.getOrElse(resources.updatedAt)
      isStale = elapsed > config.peersDeclarationTimeout
      _ <- logger.info(
        s"Checking staleness: state.key=${state.key}, elapsed=${elapsed.toSeconds}s, " +
          s"timeout=${config.peersDeclarationTimeout.toSeconds}s, isStale=$isStale, " +
          s"latestUnique=${latestUnique.map(_.toSeconds).getOrElse("None")}s, " +
          s"uniqueDelta=${uniqueDelta.map(_.toSeconds).getOrElse("None")}s, " +
          s"now=${now.toSeconds}s, started=${started.toSeconds}s, " +
          s"updatedAt=${resources.updatedAt.toSeconds}s"
      )
      result <-
        if (isStale) {
          logger.warn(
            s"The process is stale when getting all declarations. Elapsed: ${elapsed.toSeconds}s, " +
              s"Timeout: ${config.peersDeclarationTimeout.toSeconds}s " +
              s"latestUnique ${latestUnique.map(_.toSeconds).getOrElse(0)}s " +
              s"uniqueDelta ${uniqueDelta.map(_.toSeconds).getOrElse(0)}s"
          ) >> processStale.pure
        } else {
          processNonStale.pure
        }
    } yield result
  }
}

package io.constellationnetwork.node.shared.infrastructure.consensus

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus.{CertifiedProposalQC, ProposalValue}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.ConsensusPeerOutcomeVote
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import io.circe.Encoder

/** Generic round orchestration for the v35 prepare/QC phase.
  *
  * Layer advancers retain responsibility for artifact validation and their different state transitions. This shared coordinator owns the
  * identical storage, lock, signature, quorum-assembly, verification, and direct-gossip sequence so DAG and Currency cannot drift.
  */
object CertifiedConsensusRound {

  final case class Progress[F[_]](
    proposalQc: Option[CertifiedProposalQC],
    voteEmitted: Boolean,
    voteTransport: F[Unit]
  )

  def prepare[
    F[_]: Async: Hasher: SecurityProvider,
    Event,
    Key: Encoder: TypeTag,
    Artifact,
    Context,
    Status,
    Outcome,
    Kind
  ](
    key: Key,
    value: ProposalValue,
    carriedQc: Option[CertifiedProposalQC],
    resources: ConsensusResources[Artifact, Kind],
    frozenCommittee: Set[PeerId],
    frozenCore: Set[PeerId],
    configuredFraction: Double,
    selfId: PeerId,
    keyPair: KeyPair,
    storage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
    gossip: Gossip[F],
    allowVoteEmission: Boolean = true
  ): F[Either[VoteRejection, Progress[F]]] =
    for {
      valueHash <- CertifiedConsensus.valueHash[F](value)
      // Hydrate the durable safety journal before selecting carry-forward evidence or attempting a vote. On a restart resources are
      // empty, but a previously verified QC must still be available to the first proposal/view-change path.
      persistedLock <- storage.getCertifiedVoteLock(key)
      relayed = resources.peerDeclarationsMap.valuesIterator.flatMap(_.signature.flatMap(_.proposalQc)).toList
      existingQc <- CertifiedConsensus.firstVerifiedProposalQc[F](
        value,
        carriedQc.toList ++ persistedLock.flatMap(_.lockedQc).toList ++ resources.certifiedProposalQcs.values.toList ++ relayed,
        frozenCommittee,
        frozenCore,
        configuredFraction
      )
      isCore = frozenCore.contains(selfId)
      lockResult <-
        if (existingQc.isEmpty && (!isCore || !allowVoteEmission)) ().asRight[VoteRejection].pure[F]
        else
          // A verified carried/assembled QC is a new safety fact for every recipient, including a
          // non-Core follower or a Core peer abstaining under a local-only admission headroom gate.
          // Check it against the restored lock before it can drive artifact/CoreCommit progression.
          // Only a path with no QC and no locally eligible vote has no safety fact to persist.
          storage
            .tryLockCertifiedVote(key, value.committedView, valueHash, existingQc)
            .map(_.void)
      voteResult <- lockResult match {
        case Left(rejection) => rejection.asLeft[(Boolean, F[Unit])].pure[F]
        case Right(_) if existingQc.isDefined || !isCore || !allowVoteEmission =>
          (false -> Async[F].unit).asRight[VoteRejection].pure[F]
        case Right(_) =>
          storage.getResources(key).flatMap { current =>
            val alreadyStored = current.outcomeVotes.get((value.committedView, valueHash)).exists(_.contains(selfId))
            if (alreadyStored) (false -> Async[F].unit).asRight[VoteRejection].pure[F]
            else
              CertifiedConsensus.signOutcomeVote[F](value, keyPair).flatMap {
                case (_, vote) =>
                  storage
                    .addOutcomeVote(selfId, key, vote)
                    .void
                    .as(
                      (true -> gossip.spreadDirect(ConsensusPeerOutcomeVote(key, vote), frozenCore - selfId))
                        .asRight[VoteRejection]
                    )
              }
          }
      }
      result <- voteResult match {
        case Left(rejection) => rejection.asLeft[Progress[F]].pure[F]
        case Right((voteEmitted, voteTransport)) =>
          for {
            refreshed <- storage.getResources(key)
            assembledQc <- existingQc.fold {
              val voteMap = SortedMap.from(
                refreshed.outcomeVotes.getOrElse((value.committedView, valueHash), Map.empty)
              )
              CertifiedConsensus
                .buildProposalQc[F](value, voteMap, frozenCommittee, frozenCore, configuredFraction)
                .map(_.toOption)
            }(_.some.pure[F])
            _ <- assembledQc.traverse_ { qc =>
              storage.addCertifiedProposalQc(key, qc).void >> storage.advanceCertifiedLockedQc(key, qc)
            }
          } yield Progress(assembledQc, voteEmitted, voteTransport).asRight[VoteRejection]
      }
    } yield result
}

package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.dataApplication.L0NodeContext
import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.schema.currency._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.sdk.infrastructure.snapshot._
import org.tessellation.sdk.snapshot.currency.CurrencySnapshotEvent
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider: KryoSerializer]
    extends SnapshotConsensusFunctions[
      F,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      ConsensusTrigger
    ] {}

object CurrencySnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: L0NodeContext](
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    collateral: Amount,
    rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    currencySnapshotCreator: CurrencySnapshotCreator[F],
    currencySnapshotValidator: CurrencySnapshotValidator[F],
    gossip: Gossip[F]
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    def getRequiredCollateral: Amount = collateral

    def getBalances(context: CurrencySnapshotContext): SortedMap[Address, Balance] = context.snapshotInfo.balances

    def consumeSignedMajorityArtifact(
      signedArtifact: Signed[CurrencyIncrementalSnapshot],
      context: CurrencySnapshotContext
    ): F[Unit] =
      stateChannelSnapshotService.consume(signedArtifact, context) >>
        gossipForkInfo(gossip, signedArtifact)

    def validateArtifact(
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      artifact: CurrencySnapshotArtifact,
      facilitators: Set[PeerId]
    ): F[Either[ConsensusFunctions.InvalidArtifact, (CurrencySnapshotArtifact, CurrencySnapshotContext)]] =
      currencySnapshotValidator
        .validateSnapshot(lastSignedArtifact, lastContext, artifact, facilitators)
        .map(_.leftMap(_ => ArtifactMismatch).toEither)

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      facilitators: Set[PeerId]
    ): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] = {
      val blocksForAcceptance: Set[CurrencySnapshotEvent] = events.filter {
        case Left(currencyBlock) => currencyBlock.height > lastArtifact.height
        case Right(_)            => true
      }

      currencySnapshotCreator
        .createProposalArtifact(lastKey, lastArtifact, lastContext, trigger, blocksForAcceptance, rewards, facilitators)
        .map(created => (created.artifact, created.context, created.awaitingEvents))
    }
  }
}

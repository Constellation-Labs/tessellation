package org.tessellation.currency.l0.snapshot

import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.order._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency._
import org.tessellation.node.shared.domain.consensus.ConsensusFunctions
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.node.shared.infrastructure.snapshot._
import org.tessellation.node.shared.snapshot.currency.{BlockEvent, CurrencySnapshotArtifact, CurrencySnapshotEvent}
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      ConsensusTrigger
    ] {}

object CurrencySnapshotConsensusFunctions {

  def make[F[_]: Async: SecurityProvider](
    collateral: Amount,
    rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    currencySnapshotCreator: CurrencySnapshotCreator[F],
    currencySnapshotValidator: CurrencySnapshotValidator[F]
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {

    def getRequiredCollateral: Amount = collateral

    def getBalances(context: CurrencySnapshotContext): SortedMap[Address, Balance] = context.snapshotInfo.balances

    def validateArtifact(
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      artifact: CurrencySnapshotArtifact,
      facilitators: Set[PeerId]
    )(implicit hasher: Hasher[F]): F[Either[ConsensusFunctions.InvalidArtifact, (CurrencySnapshotArtifact, CurrencySnapshotContext)]] =
      currencySnapshotValidator
        .validateSnapshot(lastSignedArtifact, lastContext, artifact, facilitators)
        .map(_.leftMap(_ => ArtifactMismatch).toEither)

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      lastArtifactHasher: Hasher[F],
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      facilitators: Set[PeerId]
    )(implicit hasher: Hasher[F]): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] = {
      val blocksForAcceptance: Set[CurrencySnapshotEvent] = events.filter {
        case BlockEvent(currencyBlock) => currencyBlock.height > lastArtifact.height
        case _                         => true
      }

      currencySnapshotCreator
        .createProposalArtifact(lastKey, lastArtifact, lastContext, lastArtifactHasher, trigger, blocksForAcceptance, rewards, facilitators)
        .map(created => (created.artifact, created.context, created.awaitingEvents))
    }
  }
}

package io.constellationnetwork.currency.l0.snapshot

import cats.data.Validated.Invalid
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

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
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("CurrencySnapshotConsensusFunctions")
    override def triggerPredicate(event: CurrencySnapshotEvent): Boolean = event match {
      case GlobalSnapshotSyncEvent(_) => false // NOTE: Sync events should not trigger consensus to avoid infinite loop
      case _                          => true
    }

    def getRequiredCollateral: Amount = collateral

    def getBalances(context: CurrencySnapshotContext): SortedMap[Address, Balance] = context.snapshotInfo.balances

    def validateArtifact(
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      artifact: CurrencySnapshotArtifact,
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
    )(implicit hasher: Hasher[F]): F[Either[ConsensusFunctions.InvalidArtifact, (CurrencySnapshotArtifact, CurrencySnapshotContext)]] =
      currencySnapshotValidator
        .validateSnapshot(
          lastSignedArtifact,
          lastContext,
          artifact,
          facilitators,
          getGlobalSnapshotByOrdinal
        )
        .flatTap {
          case Invalid(errors) =>
            logger.warn(s"Failed when validating currency artifact. Errors: ${errors.toList}")
          case _ => Async[F].unit
        }
        .map(_.leftMap(errors => CurrencyArtifactMismatch(errors.toList)).toEither)

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      lastArtifactHasher: Hasher[F],
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
    )(implicit hasher: Hasher[F]): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] = {
      val blocksForAcceptance: Set[CurrencySnapshotEvent] = events.filter {
        case BlockEvent(currencyBlock) => currencyBlock.height > lastArtifact.height
        case _                         => true
      }

      currencySnapshotCreator
        .createProposalArtifact(
          lastKey,
          lastArtifact,
          lastContext,
          lastArtifactHasher,
          trigger,
          blocksForAcceptance,
          rewards,
          facilitators,
          None,
          None,
          getGlobalSnapshotByOrdinal,
          shouldValidateCollateral = true
        )
        .map(created => (created.artifact, created.context, created.awaitingEvents))
    }
  }
}

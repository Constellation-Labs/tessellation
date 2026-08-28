package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.{Validated, ValidatedNec}
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationService, DataCalculatedState}
import io.constellationnetwork.currency.schema.CurrencySnapshotSemantics
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.syntax.validated.validatedSyntax
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hashed, Hasher}

import derevo.cats.{eqv, show}
import derevo.derive
import monocle.syntax.all._

trait CurrencySnapshotValidator[F[_]] {

  type CurrencySnapshotValidationErrorOr[A] = ValidatedNec[CurrencySnapshotValidationError, A]

  def validateSignedSnapshot(
    lastArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    artifact: Signed[CurrencySnapshotArtifact],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    historicalDependencyResolution: Boolean = false
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]]

  def validateSnapshot(
    lastArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    artifact: CurrencySnapshotArtifact,
    facilitators: Set[PeerId],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    peerHistory: Option[ConsensusOperationalState] = None,
    historicalDependencyResolution: Boolean = false
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotValidationErrorOr[(CurrencyIncrementalSnapshot, CurrencySnapshotContext)]]
}

object CurrencySnapshotValidator {

  /** Whether a re-created currency artifact matches the expected signed value.
    *
    * Legacy `0.0.1` preserves release/mainnet compatibility: `globalSyncView` came from live, time-varying GL0 sync state, so historical
    * validation pins that one signed field before comparing the rest. Version `1.0.0` closes recreation over consensus-carried inputs and
    * therefore requires every field, including `globalSyncView`, to rederive exactly.
    */
  def matchesExpected(recreated: CurrencyIncrementalSnapshot, expected: CurrencyIncrementalSnapshot): Boolean =
    if (CurrencySnapshotSemantics.usesDeterministicHistory(expected.version)) recreated === expected
    else recreated.focus(_.globalSyncView).replace(expected.globalSyncView) === expected

  def make[F[_]: Async: KryoSerializer: JsonSerializer](
    currencySnapshotCreator: CurrencySnapshotCreator[F],
    signedValidator: SignedValidator[F],
    maybeRewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    maybeDataApplication: Option[BaseDataApplicationService[F]],
    fixingAllowSpendDestinationCredit: SnapshotOrdinal
  ): CurrencySnapshotValidator[F] = new CurrencySnapshotValidator[F] {
    def validateSignedSnapshot(
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      artifact: Signed[CurrencySnapshotArtifact],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      historicalDependencyResolution: Boolean = false
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]] =
      validateSigned(artifact).flatMap {
        case Validated.Invalid(errors) =>
          Async[F].pure[CurrencySnapshotValidationErrorOr[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotContext)]](
            Validated.Invalid(errors)
          )
        case Validated.Valid(validatedArtifact) =>
          val facilitators = artifact.proofs.map(_.id).map(PeerId.fromId).toSortedSet
          val historicalModes = AllowSpendBlockAcceptanceMode.currencyHistoricalRecreationModes(
            lastArtifact.globalSyncView,
            fixingAllowSpendDestinationCredit
          )

          validateSnapshotWithModes(
            lastArtifact,
            lastContext,
            artifact,
            facilitators,
            getGlobalSnapshotByOrdinal,
            // Chain-replay path: no live consensus state to consult, so re-feed the
            // artifact's own claim as the recreation input. The signature-validation
            // above already binds the value to the signing facilitators -- if it
            // were tampered with, this would have failed first.
            artifact.value.peerHistory,
            historicalDependencyResolution,
            historicalModes
          ).map(_.map { case (_, info) => (validatedArtifact, info) })
      }

    def validateSnapshot(
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      artifact: CurrencySnapshotArtifact,
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      peerHistory: Option[ConsensusOperationalState] = None,
      historicalDependencyResolution: Boolean = false
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotValidationErrorOr[(CurrencyIncrementalSnapshot, CurrencySnapshotContext)]] =
      validateSnapshotWithModes(
        lastArtifact,
        lastContext,
        artifact,
        facilitators,
        getGlobalSnapshotByOrdinal,
        peerHistory,
        historicalDependencyResolution,
        List(AllowSpendBlockAcceptanceMode.live)
      )

    private def validateSnapshotWithModes(
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      artifact: CurrencySnapshotArtifact,
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      peerHistory: Option[ConsensusOperationalState],
      historicalDependencyResolution: Boolean,
      allowSpendRecreationModes: List[AllowSpendBlockAcceptanceMode]
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotValidationErrorOr[(CurrencyIncrementalSnapshot, CurrencySnapshotContext)]] = for {
      contentV <- validateRecreateContent(
        lastArtifact,
        lastContext,
        artifact,
        facilitators,
        getGlobalSnapshotByOrdinal,
        peerHistory,
        historicalDependencyResolution,
        allowSpendRecreationModes
      )
      blocksV <- contentV.map(validateNotAcceptedEvents).pure[F]
    } yield
      (contentV, blocksV).mapN {
        case (creationResult, _) => (creationResult.artifact, creationResult.context)
      }

    def validateSigned(
      signedSnapshot: Signed[CurrencyIncrementalSnapshot]
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotValidationErrorOr[Signed[CurrencyIncrementalSnapshot]]] = {
      val snapshot = signedSnapshot.value
      val proofs = signedSnapshot.proofs

      val validateSnapshot =
        signedValidator.validateSignatures(signedSnapshot).map(_.errorMap[CurrencySnapshotValidationError](InvalidSigned(_)))

      val validateKryoSnapshot = signedValidator
        .validateSignatures(Signed(CurrencyIncrementalSnapshotV1.fromCurrencyIncrementalSnapshot(snapshot), proofs))
        .map(_.errorMap[CurrencySnapshotValidationError](InvalidSigned(_)))
        .map(_.map {
          case Signed(s, p) => Signed(s.toCurrencyIncrementalSnapshot, p)
        })

      // All current public-network production is JSON. This fallback remains
      // solely for replaying pre-JSON historical snapshots; no new Currency
      // functionality adds another Kryo encoding path.
      validateSnapshot.handleErrorWith(_ => validateKryoSnapshot)
    }

    def validateRecreateContent(
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      expected: CurrencySnapshotArtifact,
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      peerHistory: Option[ConsensusOperationalState],
      historicalDependencyResolution: Boolean,
      allowSpendRecreationModes: List[AllowSpendBlockAcceptanceMode]
    )(implicit hasher: Hasher[F]): F[CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]] = {
      def dataApplicationBlocks = maybeDataApplication.flatTraverse { service =>
        expected.dataApplication.map(_.blocks).traverse {
          _.traverse(b => service.deserializeBlock(b))
        }
      }.map(_.map(_.flatMap(_.toOption)))
        .map(_.getOrElse(List.empty))

      def mkEvents: F[Set[CurrencySnapshotEvent]] = for {
        dataApplicationEvents <- dataApplicationBlocks.map(_.map(DataApplicationBlockEvent(_)).toSet)
        blockEvents = expected.blocks.unsorted.map(_.block).map(BlockEvent(_))
        tokenLockBlockEvents = expected.tokenLockBlocks.map(_.unsorted.map(TokenLockBlockEvent(_))).getOrElse(Set.empty)
        allowSpendsBlockEvents = expected.allowSpendBlocks.map(_.unsorted.map(AllowSpendBlockEvent(_))).getOrElse(Set.empty)
        messageEvents = expected.messages.map(_.toSet.map(CurrencyMessageEvent(_))).getOrElse(Set.empty[CurrencyMessageEvent])
        globalSnapshotSyncEvents = expected.globalSnapshotSyncs
          .map(_.toSet.map(GlobalSnapshotSyncEvent(_)))
          .getOrElse(Set.empty[GlobalSnapshotSyncEvent])
      } yield
        dataApplicationEvents ++ blockEvents ++ messageEvents ++ globalSnapshotSyncEvents ++ tokenLockBlockEvents ++ allowSpendsBlockEvents

      // Rewrite if implementation not provided
      val rewards = maybeRewards.orElse(Some {
        new Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent] {
          def distribute(
            lastArtifact: Signed[CurrencySnapshotArtifact],
            lastBalances: SortedMap[address.Address, balance.Balance],
            acceptedTransactions: SortedSet[Signed[transaction.Transaction]],
            trigger: ConsensusTrigger,
            events: Set[CurrencySnapshotEvent],
            maybeCalculatedState: Option[DataCalculatedState] = None
          ): F[SortedSet[transaction.RewardTransaction]] = expected.rewards.pure[F]
        }
      })

      def recreateFn(
        trigger: ConsensusTrigger,
        allowSpendBlockAcceptanceMode: AllowSpendBlockAcceptanceMode
      ): F[CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]] =
        mkEvents.flatMap { events =>
          def usingHasher = (lastArtifactHasher: Hasher[F]) =>
            currencySnapshotCreator
              .createProposalArtifact(
                lastArtifact.ordinal,
                lastArtifact,
                lastContext,
                lastArtifactHasher,
                trigger,
                events,
                rewards,
                facilitators,
                expected.feeTransactions.map(() => _),
                expected.artifacts.map(() => _),
                getGlobalSnapshotByOrdinal,
                shouldPerformMetagraphSpecificValidations = false,
                Some((_: Signed[CurrencyIncrementalSnapshot]) => expected.artifacts),
                peerHistory,
                historicalDependencyResolution,
                allowSpendBlockAcceptanceMode
              )

          def check(
            result: F[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]
          ): F[CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]] =
            // Rewrite if implementation not provided
            result.map { creationResult =>
              maybeDataApplication match {
                case Some(_) => creationResult
                case None =>
                  creationResult
                    .focus(_.artifact.dataApplication)
                    .replace(expected.dataApplication)
                    .focus(_.artifact.artifacts)
                    .replace(expected.artifacts)

              }
            }.map { creationResult =>
              if (creationResult.artifact.messages.forall(_.isEmpty))
                creationResult.focus(_.artifact.messages).replace(expected.messages)
              else creationResult
            }.map { creationResult =>
              // Legacy replay pins globalSyncView; deterministic-history replay compares it exactly. The error reports the unmodified
              // recreated artifact so any divergence remains fully visible downstream.
              if (matchesExpected(creationResult.artifact, expected))
                creationResult.validNec
              else
                SnapshotDifferentThanExpected(expected, creationResult.artifact).invalidNec
            }

          check(usingHasher(Hasher.forJson[F]))
        }

      def recreateWithModes(
        trigger: ConsensusTrigger,
        modes: List[AllowSpendBlockAcceptanceMode]
      ): F[CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]] =
        modes match {
          case Nil =>
            new IllegalStateException("No allow-spend acceptance mode available for snapshot recreation")
              .raiseError[F, CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]]
          case mode :: remaining =>
            recreateFn(trigger, mode).attempt.flatMap {
              case Right(valid @ Validated.Valid(_)) =>
                Async[F].pure[CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]](valid)
              case Right(invalid @ Validated.Invalid(_)) =>
                if (remaining.nonEmpty) recreateWithModes(trigger, remaining)
                else
                  Async[F].pure[CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]](invalid)
              case Left(error) =>
                if (remaining.nonEmpty) recreateWithModes(trigger, remaining)
                else error.raiseError[F, CurrencySnapshotValidationErrorOr[CurrencySnapshotCreationResult[CurrencySnapshotEvent]]]
            }
        }

      recreateWithModes(TimeTrigger, allowSpendRecreationModes).flatMap { tV =>
        recreateWithModes(EventTrigger, allowSpendRecreationModes).map(_.orElse(tV))
      }
    }

    def validateNotAcceptedEvents(
      creationResult: CurrencySnapshotCreationResult[CurrencySnapshotEvent]
    ): CurrencySnapshotValidationErrorOr[Unit] = {
      def getBlocks(s: Set[CurrencySnapshotEvent]): Set[Signed[Block]] = s.collect { case BlockEvent(block) => block }

      val awaitingBlocks = getBlocks(creationResult.awaitingEvents)
      val rejectedBlocks = getBlocks(creationResult.rejectedEvents)

      Validated.condNec(
        awaitingBlocks.nonEmpty && rejectedBlocks.nonEmpty,
        (),
        SomeBlocksWereNotAccepted(awaitingBlocks, rejectedBlocks)
      )
    }
  }

}

@derive(eqv, show)
sealed trait CurrencySnapshotValidationError

case class SnapshotDifferentThanExpected(expected: CurrencyIncrementalSnapshot, actual: CurrencyIncrementalSnapshot)
    extends CurrencySnapshotValidationError

case class SomeBlocksWereNotAccepted(awaitingBlocks: Set[Signed[Block]], rejectedBlocks: Set[Signed[Block]])
    extends CurrencySnapshotValidationError

case class InvalidSigned(error: SignedValidationError) extends CurrencySnapshotValidationError

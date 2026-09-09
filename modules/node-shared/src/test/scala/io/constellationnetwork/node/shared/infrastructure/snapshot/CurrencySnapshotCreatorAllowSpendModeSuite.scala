package io.constellationnetwork.node.shared.infrastructure.snapshot

import java.util.UUID

import cats.data.NonEmptySet
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSyncView}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotSizeConfig
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.swap.block.{
  AllowSpendBlockAcceptanceContext,
  AllowSpendBlockAcceptanceContextUpdate,
  AllowSpendBlockAcceptanceLogic
}
import io.constellationnetwork.node.shared.infrastructure.consensus.ValidationErrorStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.{
  CurrencySnapshotAcceptanceManager,
  CurrencySnapshotAcceptanceResult
}
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.currencyMessage.CurrencyMessage
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.semver.SnapshotVersion
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.SimpleIOSuite

object CurrencySnapshotCreatorAllowSpendModeSuite extends SimpleIOSuite {

  private case object StopAfterAllowSpendAcceptance extends RuntimeException
  private case class Observed(mode: AllowSpendBlockAcceptanceMode, firstAccepted: Boolean, phantomSecondAccepted: Boolean)

  private val source = Address("DAG011jH7FMDvKpdb7wewrMWwYtkwq56nHquAHdi")
  private val destination = Address("DAG06z64ifT2HzXoHfMexRfrcnpYFEwMqjFiPKze")
  private val onward = Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd")

  private def balance(value: Long): Balance = Balance(NonNegLong.unsafeFrom(value))

  private def proofOf(seed: Int): NonEmptySet[SignatureProof] = {
    val hex = (seed.toString * 128).take(128)
    NonEmptySet.one(SignatureProof(Id(Hex(hex)), Signature(Hex(hex))))
  }

  private def blockOf(from: Address, to: Address, amount: Long, fee: Long, seed: Int): Signed[AllowSpendBlock] = {
    val allowSpend = Signed(
      AllowSpend(
        from,
        to,
        None,
        SwapAmount(PosLong.unsafeFrom(amount)),
        AllowSpendFee(NonNegLong.unsafeFrom(fee)),
        AllowSpendReference.empty,
        EpochProgress(NonNegLong.unsafeFrom(100L)),
        List.empty
      ),
      proofOf(seed)
    )

    Signed(
      AllowSpendBlock(RoundId(UUID.fromString(s"00000000-0000-0000-0000-00000000000$seed")), NonEmptySet.one(allowSpend)),
      proofOf(seed)
    )
  }

  private val firstBlock = blockOf(source, destination, 100L, 1L, 1)
  private val phantomSecondBlock = blockOf(destination, onward, 100L, 0L, 2)

  private def emptyInfo: CurrencySnapshotInfo =
    CurrencySnapshotInfo(
      SortedMap.empty[Address, TransactionReference],
      SortedMap(source -> balance(101L)),
      None,
      None,
      None,
      None,
      None,
      None,
      None
    )

  private def emptyStateProof: CurrencySnapshotStateProof =
    CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None)

  private def parentArtifact: Signed[CurrencyIncrementalSnapshot] =
    Signed(
      CurrencyIncrementalSnapshot(
        SnapshotOrdinal(10L),
        Height.MinValue,
        SubHeight.MinValue,
        Hash.empty,
        SortedSet.empty,
        SortedSet.empty,
        SnapshotTips(SortedSet.empty, SortedSet.empty),
        emptyStateProof,
        EpochProgress.MinValue,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        Some(GlobalSyncView(SnapshotOrdinal(6815490L), Hash.empty, EpochProgress.MinValue))
      ),
      proofOf(9)
    )

  private def recordingAcceptanceManager(
    observed: Ref[IO, Option[Observed]]
  )(implicit securityProvider: SecurityProvider[IO]): CurrencySnapshotAcceptanceManager[IO] =
    new CurrencySnapshotAcceptanceManager[IO] {
      def accept(
        blocksForAcceptance: List[Signed[Block]],
        tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
        allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
        messagesForAcceptance: List[Signed[CurrencyMessage]],
        feeTransactionsForAcceptance: Option[SortedSet[Signed[FeeTransaction]]],
        globalSnapshotSyncsForAcceptance: List[Signed[GlobalSnapshotSync]],
        sharedArtifactsForAcceptance: SortedSet[SharedArtifact],
        lastSnapshotContext: CurrencySnapshotContext,
        snapshotOrdinal: SnapshotOrdinal,
        epochProgress: EpochProgress,
        lastActiveTips: SortedSet[ActiveTip],
        lastDeprecatedTips: SortedSet[DeprecatedTip],
        calculateRewardsFn: SortedSet[Signed[Transaction]] => IO[SortedSet[RewardTransaction]],
        facilitators: Set[PeerId],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => IO[Option[Hashed[GlobalIncrementalSnapshot]]],
        lastGlobalSyncView: Option[GlobalSyncView],
        shouldPerformMetagraphSpecificValidations: Boolean,
        lastArtifactProofs: NonEmptySet[SignatureProof],
        previouslyProcessedGlobalSnapshots: SortedSet[SnapshotOrdinal],
        historicalDependencyResolution: Boolean,
        parentSnapshotVersion: SnapshotVersion,
        allowSpendBlockAcceptanceMode: AllowSpendBlockAcceptanceMode
      )(implicit hasher: Hasher[IO]): IO[CurrencySnapshotAcceptanceResult] = {
        val logic = AllowSpendBlockAcceptanceLogic.make[IO]
        val context = AllowSpendBlockAcceptanceContext.fromStaticData[IO](
          lastSnapshotContext.snapshotInfo.balances,
          Map.empty,
          Amount.empty,
          AllowSpendReference.empty
        )

        for {
          first <- logic
            .acceptBlock(
              firstBlock,
              Map.empty,
              context,
              AllowSpendBlockAcceptanceContextUpdate.empty,
              shouldPerformMetagraphSpecificValidations = false,
              allowSpendBlockAcceptanceMode.creditDestination
            )
            .value
          firstUpdate = first.getOrElse(AllowSpendBlockAcceptanceContextUpdate.empty)
          second <- logic
            .acceptBlock(
              phantomSecondBlock,
              Map.empty,
              context,
              firstUpdate,
              shouldPerformMetagraphSpecificValidations = false,
              allowSpendBlockAcceptanceMode.creditDestination
            )
            .value
          _ <- observed.set(Some(Observed(allowSpendBlockAcceptanceMode, first.isRight, second.isRight)))
          result <- StopAfterAllowSpendAcceptance.raiseError[IO, CurrencySnapshotAcceptanceResult]
        } yield result
      }

      def acceptRewardTxs(
        baseBalances: SortedMap[Address, Balance],
        newUpdatedBalance: Map[Address, Balance],
        rewards: SortedSet[RewardTransaction]
      ): IO[(SortedMap[Address, Balance], SortedSet[RewardTransaction])] =
        (baseBalances, SortedSet.empty[RewardTransaction]).pure[IO]
    }

  test("live creator uses escrow semantics even when the signed parent global view predates activation") {
    SecurityProvider.forAsync[IO].use { implicit securityProvider =>
      JsonSerializer.forAsync[IO].flatMap { implicit jsonSerializer =>
        implicit val hasher: Hasher[IO] = Hasher.forJson[IO]

        for {
          observed <- Ref.of[IO, Option[Observed]](None)
          validationStorage <- ValidationErrorStorage.make[IO, CurrencySnapshotEvent, BlockRejectionReason](
            10,
            _ => List.empty[Hash].pure[IO]
          )
          creator = CurrencySnapshotCreator.make[IO](
            SnapshotOrdinal.MinValue,
            recordingAcceptanceManager(observed),
            None,
            SnapshotSizeConfig(203L, 512000L),
            CurrencyEventsCutter.make[IO](None),
            validationStorage
          )
          _ <- creator
            .createProposalArtifact(
              parentArtifact.ordinal,
              parentArtifact,
              CurrencySnapshotContext(source, emptyInfo),
              hasher,
              EventTrigger,
              Set(AllowSpendBlockEvent(firstBlock), AllowSpendBlockEvent(phantomSecondBlock)),
              None: Option[Rewards[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
              Set.empty,
              None,
              None,
              _ => none[Hashed[GlobalIncrementalSnapshot]].pure[IO],
              shouldPerformMetagraphSpecificValidations = false,
              maybeCustomArtifacts = None,
              peerHistory = None,
              historicalDependencyResolution = false,
              allowSpendBlockAcceptanceMode = AllowSpendBlockAcceptanceMode.live
            )
            .attempt
          result <- observed.get
        } yield expect(result.contains(Observed(AllowSpendBlockAcceptanceMode.Escrow, firstAccepted = true, phantomSecondAccepted = false)))
      }
    }
  }

  test("historical recreation allows legacy only below activation") {
    val activation = SnapshotOrdinal.unsafeApply(100L)
    val below = GlobalSyncView(SnapshotOrdinal.unsafeApply(99L), Hash.empty, EpochProgress.MinValue)
    val at = GlobalSyncView(activation, Hash.empty, EpochProgress.MinValue)
    val above = GlobalSyncView(SnapshotOrdinal.unsafeApply(101L), Hash.empty, EpochProgress.MinValue)

    IO.pure(
      expect.all(
        AllowSpendBlockAcceptanceMode.live == AllowSpendBlockAcceptanceMode.Escrow,
        AllowSpendBlockAcceptanceMode.currencyHistoricalRecreationModes(Some(below), activation) == List(
          AllowSpendBlockAcceptanceMode.Escrow,
          AllowSpendBlockAcceptanceMode.LegacyCreditDestination
        ),
        AllowSpendBlockAcceptanceMode.currencyHistoricalRecreationModes(None, activation) == List(
          AllowSpendBlockAcceptanceMode.Escrow,
          AllowSpendBlockAcceptanceMode.LegacyCreditDestination
        ),
        AllowSpendBlockAcceptanceMode.currencyHistoricalRecreationModes(Some(at), activation) == List(
          AllowSpendBlockAcceptanceMode.Escrow
        ),
        AllowSpendBlockAcceptanceMode.globalHistoricalRecreationModes(below.ordinal, activation) == List(
          AllowSpendBlockAcceptanceMode.Escrow,
          AllowSpendBlockAcceptanceMode.LegacyCreditDestination
        ),
        AllowSpendBlockAcceptanceMode.globalHistoricalRecreationModes(at.ordinal, activation) == List(
          AllowSpendBlockAcceptanceMode.Escrow
        ),
        AllowSpendBlockAcceptanceMode.globalHistoricalRecreationModes(above.ordinal, activation) == List(
          AllowSpendBlockAcceptanceMode.Escrow
        )
      )
    )
  }
}

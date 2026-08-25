package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSyncView}
import io.constellationnetwork.env.AppEnvironment.Mainnet
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.SnapshotSizeConfig
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.consensus.ValidationErrorStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
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
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object CurrencySnapshotCreatorFeeModeSuite extends SimpleIOSuite {

  private case object StopAfterFeeAcceptance extends RuntimeException
  private case class Observed(mode: FeeTransactionAcceptanceMode, accepted: Int, rejected: Int)

  private val payer = Address("DAG011jH7FMDvKpdb7wewrMWwYtkwq56nHquAHdi")
  private val destinations = List(
    Address("DAG06z64ifT2HzXoHfMexRfrcnpYFEwMqjFiPKze"),
    Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd"),
    Address("DAG0CyySf35ftDQDQBnd1bdQ9aPyUdacMghpnCuM"),
    Address("DAG0eQr94qUQSUhmYGNXt6CoBKWu5K6htvRMGC6M")
  )
  private val twoPow62 = 4611686018427387904L

  private def proofOf(seed: Int): NonEmptySet[SignatureProof] = {
    val hex = (seed.toString * 128).take(128)
    NonEmptySet.one(SignatureProof(Id(Hex(hex)), Signature(Hex(hex))))
  }

  private def feeTransactions: SortedSet[Signed[FeeTransaction]] =
    SortedSet.from(destinations.zipWithIndex.map {
      case (destination, index) =>
        Signed(
          FeeTransaction(payer, destination, Amount(NonNegLong.unsafeFrom(twoPow62)), Hash.empty),
          proofOf(index + 1)
        )
    })

  private def emptyInfo: CurrencySnapshotInfo =
    CurrencySnapshotInfo(
      SortedMap.empty[Address, TransactionReference],
      SortedMap(payer -> Balance.empty),
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

  private def recordingAcceptanceManager(observed: Ref[IO, Option[Observed]]): CurrencySnapshotAcceptanceManager[IO] =
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
        shouldValidateCollateral: Boolean,
        lastArtifactProofs: NonEmptySet[SignatureProof],
        feeTransactionAcceptanceMode: FeeTransactionAcceptanceMode
      )(implicit hasher: Hasher[IO]): IO[CurrencySnapshotAcceptanceResult] = {
        val txs = feeTransactionsForAcceptance.getOrElse(SortedSet.empty[Signed[FeeTransaction]])
        val (_, accepted, rejected) = CurrencySnapshotAcceptanceManager.applyFeeTransactions(lastSnapshotContext.snapshotInfo.balances, txs)

        observed.set(Some(Observed(feeTransactionAcceptanceMode, accepted.size, rejected.size))) >>
          StopAfterFeeAcceptance.raiseError[IO, CurrencySnapshotAcceptanceResult]
      }

      def acceptRewardTxs(
        baseBalances: SortedMap[Address, Balance],
        newUpdatedBalance: Map[Address, Balance],
        rewards: SortedSet[RewardTransaction]
      ): IO[(SortedMap[Address, Balance], SortedSet[RewardTransaction])] =
        (baseBalances, SortedSet.empty[RewardTransaction]).pure[IO]
    }

  test("live creator stays strict when the signed parent global view predates activation") {
    JsonSerializer.forSync[IO].flatMap { implicit jsonSerializer =>
      implicit val hasher: Hasher[IO] = Hasher.forJson[IO]

      for {
        observed <- Ref.of[IO, Option[Observed]](None)
        validationStorage <- ValidationErrorStorage.make[IO, CurrencySnapshotEvent, BlockRejectionReason](10, _ => List.empty[Hash].pure[IO])
        creator = CurrencySnapshotCreator.make[IO](
          SnapshotOrdinal.MinValue,
          Mainnet,
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
            CurrencySnapshotContext(payer, emptyInfo),
            hasher,
            EventTrigger,
            Set.empty,
            None: Option[Rewards[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
            Set.empty,
            Some(() => feeTransactions),
            None,
            _ => none[Hashed[GlobalIncrementalSnapshot]].pure[IO],
            shouldValidateCollateral = false,
            None
          )
          .attempt
        result <- observed.get
      } yield expect(result.contains(Observed(FeeTransactionAcceptanceMode.Strict, accepted = 0, rejected = 4)))
    }
  }
}

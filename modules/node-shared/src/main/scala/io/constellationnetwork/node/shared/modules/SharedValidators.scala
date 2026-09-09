package io.constellationnetwork.node.shared.modules

import cats.data.NonEmptySet
import cats.effect.Async

import scala.collection.immutable.SortedMap

import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.{AddressesConfig, DelegatedStakingConfig, PriceOracleConfig}
import io.constellationnetwork.node.shared.domain.block.processing.BlockValidator
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralValidator
import io.constellationnetwork.node.shared.domain.priceOracle.PricingUpdateValidator
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig, StateChannelValidator}
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockValidator
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendChainValidator, AllowSpendValidator, SpendActionValidator}
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockValidator
import io.constellationnetwork.node.shared.domain.tokenlock.{TokenLockChainValidator, TokenLockValidator}
import io.constellationnetwork.node.shared.domain.transaction.{FeeTransactionValidator, TransactionChainValidator, TransactionValidator}
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockValidator
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorValidator
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencyMessageValidator, GlobalSnapshotSyncValidator}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.SignedValidator
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.types.numeric.PosLong

object SharedValidators {

  def make[F[_]: Async: JsonSerializer: SecurityProvider: Hasher](
    environment: AppEnvironment,
    addressesCfg: AddressesConfig,
    l0Seedlist: Option[Set[SeedlistEntry]],
    seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    feeConfigs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig],
    maxBinarySizeInBytes: PosLong,
    txHasher: Hasher[F],
    delegatedStaking: DelegatedStakingConfig,
    priceOracleConfig: PriceOracleConfig,
    maybeMptStore: Option[MptStore[F, GlobalStateKey]] = None
  ): SharedValidators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F](txHasher)
    val feeTransactionValidator = FeeTransactionValidator.make[F](signedValidator)
    val transactionValidator = TransactionValidator.make[F](addressesCfg, signedValidator, txHasher)
    val blockValidator = BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator, txHasher)
    val currencyTransactionChainValidator = TransactionChainValidator.make[F](txHasher)
    val currencyTransactionValidator = TransactionValidator.make[F](addressesCfg, signedValidator, txHasher)
    val currencyBlockValidator = BlockValidator
      .make[F](signedValidator, currencyTransactionChainValidator, currencyTransactionValidator, txHasher)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)
    val feeCalculator = FeeCalculator.make(feeConfigs)
    val stateChannelValidator =
      StateChannelValidator.make[F](signedValidator, l0Seedlist, stateChannelAllowanceLists, maxBinarySizeInBytes, feeCalculator)
    val currencyMessageValidator = CurrencyMessageValidator.make[F](environment, signedValidator, stateChannelAllowanceLists, seedlist)
    val globalSnapshotSyncValidator = GlobalSnapshotSyncValidator.make[F](signedValidator, seedlist, stateChannelAllowanceLists)
    val allowSpendChainValidator = AllowSpendChainValidator.make[F]
    val allowSpendValidator = AllowSpendValidator.make[F](addressesCfg, signedValidator)
    val allowSpendBlockValidator = AllowSpendBlockValidator.make[F](signedValidator, allowSpendChainValidator, allowSpendValidator)

    val tokenLockValidator = TokenLockValidator.make[F](addressesCfg, signedValidator)
    val tokenLockChainValidator = TokenLockChainValidator.make[F]
    val tokenLockBlockValidator = TokenLockBlockValidator.make[F](signedValidator, tokenLockChainValidator, tokenLockValidator)

    val updateNodeParametersValidator = UpdateNodeParametersValidator.make(
      signedValidator,
      delegatedStaking.minRewardFraction,
      delegatedStaking.maxRewardFraction,
      delegatedStaking.maxMetadataFieldsChars,
      l0Seedlist
    )
    val updateDelegatedStakeValidator = maybeMptStore match {
      case Some(mptStore) => UpdateDelegatedStakeValidator.make[F](signedValidator, l0Seedlist, mptStore)
      case None           => UpdateDelegatedStakeValidator.make[F](signedValidator, l0Seedlist)
    }
    val updateNodeCollateralValidator = maybeMptStore match {
      case Some(mptStore) => UpdateNodeCollateralValidator.make[F](signedValidator, l0Seedlist, mptStore)
      case None           => UpdateNodeCollateralValidator.rejectAll[F]
    }

    val spendActionValidator = SpendActionValidator.make[F]

    val pricingUpdateValidator =
      PricingUpdateValidator.make[F](priceOracleConfig.allowedMetagraphIds, priceOracleConfig.minEpochsBetweenUpdates)

    new SharedValidators[F](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      feeTransactionValidator,
      currencyTransactionChainValidator,
      currencyTransactionValidator,
      blockValidator,
      currencyBlockValidator,
      rumorValidator,
      stateChannelValidator,
      currencyMessageValidator,
      globalSnapshotSyncValidator,
      tokenLockBlockValidator,
      allowSpendBlockValidator,
      allowSpendValidator,
      tokenLockValidator,
      updateNodeParametersValidator,
      spendActionValidator,
      updateDelegatedStakeValidator,
      updateNodeCollateralValidator,
      pricingUpdateValidator
    ) {}
  }
}

sealed abstract class SharedValidators[F[_]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F],
  val transactionValidator: TransactionValidator[F],
  val feeTransactionValidator: FeeTransactionValidator[F],
  val currencyTransactionChainValidator: TransactionChainValidator[F],
  val currencyTransactionValidator: TransactionValidator[F],
  val blockValidator: BlockValidator[F],
  val currencyBlockValidator: BlockValidator[F],
  val rumorValidator: RumorValidator[F],
  val stateChannelValidator: StateChannelValidator[F],
  val currencyMessageValidator: CurrencyMessageValidator[F],
  val globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F],
  val tokenLockBlockValidator: TokenLockBlockValidator[F],
  val allowSpendBlockValidator: AllowSpendBlockValidator[F],
  val allowSpendValidator: AllowSpendValidator[F],
  val tokenLockValidator: TokenLockValidator[F],
  val updateNodeParametersValidator: UpdateNodeParametersValidator[F],
  val spendActionValidator: SpendActionValidator[F],
  val updateDelegatedStakeValidator: UpdateDelegatedStakeValidator[F],
  val updateNodeCollateralValidator: UpdateNodeCollateralValidator[F],
  val pricingUpdateValidator: PricingUpdateValidator[F]
)

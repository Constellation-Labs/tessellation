package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeAcceptanceManager
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersAcceptanceManager
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralAcceptanceManager
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.domain.swap.block.{
  AllowSpendBlockAcceptanceLogic,
  AllowSpendBlockAcceptanceManager,
  AllowSpendBlockValidator
}
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendChainValidator, AllowSpendValidator}
import io.constellationnetwork.node.shared.domain.tokenlock.block.{
  TokenLockBlockAcceptanceLogic,
  TokenLockBlockAcceptanceManager,
  TokenLockBlockValidator
}
import io.constellationnetwork.node.shared.domain.tokenlock.{TokenLockChainValidator, TokenLockValidator}
import io.constellationnetwork.node.shared.domain.transaction.{TransactionChainValidator, TransactionValidator}
import io.constellationnetwork.node.shared.infrastructure.block.processing.{BlockAcceptanceLogic, BlockAcceptanceManager, BlockValidator}
import io.constellationnetwork.node.shared.infrastructure.consensus.CurrencySnapshotEventValidationErrorStorage
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.modules.SharedValidators
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.node.RewardFraction
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.{Transaction, TransactionReference}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.syntax.sortedCollection._
import io.constellationnetwork.tools.TransactionGenerator._
import io.constellationnetwork.tools.{DAGBlockGenerator, TransactionGenerator}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers

object GlobalSnapshotTraverseSuite extends MutableIOSuite with Checkers {
  val TestValidationErrorStorageMaxSize: PosInt = PosInt(16)

  type GenKeyPairFn = () => KeyPair

  type Res = (KryoSerializer[IO], Hasher[IO], JsonSerializer[IO], SecurityProvider[IO], Metrics[IO], Random[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
    metrics <- Metrics.forAsync[IO](Seq.empty)
    random <- Random.scalaUtilRandom[IO].asResource
  } yield (ks, h, j, sp, metrics, random)

  val balances: Map[Address, Balance] = Map(Address("DAG8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC") -> Balance(NonNegLong(10L)))

  def mkSnapshots(dags: List[List[BlockAsActiveTip]], initBalances: Map[Address, Balance])(
    implicit H: Hasher[IO],
    S: SecurityProvider[IO],
    K: KryoSerializer[IO]
  ): IO[(Hashed[GlobalSnapshot], NonEmptyList[Hashed[GlobalIncrementalSnapshot]])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed
        .forAsyncHasher[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(initBalances, EpochProgress.MinValue), keyPair)
        .flatMap(_.toHashed)
        .flatMap { genesis =>
          GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](genesis).flatMap { incremental =>
            mkSnapshot(genesis.hash, incremental, genesis.info, keyPair, SortedSet.empty, Hasher.forKryo[IO]).flatMap {
              snapshotWithContext =>
                dags
                  .foldLeftM(NonEmptyList.of(snapshotWithContext)) {
                    case (snapshots, blocksChunk) =>
                      mkSnapshot(
                        snapshots.head._1.hash,
                        snapshots.head._1.signed.value,
                        snapshots.head._2,
                        keyPair,
                        blocksChunk.toSortedSet,
                        Hasher.forKryo[IO]
                      )
                        .map(snapshots.prepend)
                  }
                  .map(incrementals => (genesis, incrementals.reverse.map(_._1)))
            }
          }
        }
    }

  def mkSnapshot(
    lastHash: Hash,
    lastSnapshot: GlobalIncrementalSnapshot,
    lastInfo: GlobalSnapshotInfo,
    keyPair: KeyPair,
    blocks: SortedSet[BlockAsActiveTip],
    txHasher: Hasher[IO]
  )(
    implicit S: SecurityProvider[IO],
    H: Hasher[IO]
  ): IO[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] =
    for {
      activeTips <- lastSnapshot.activeTips
      txs = blocks.flatMap(_.block.value.transactions.toSortedSet)
      lastTxRefs <- txs
        .groupBy(_.source)
        .toList
        .flatMap { case (address, ts) => ts.maxByOption(_.ordinal.value.value).map(address -> _) }
        .traverse {
          case (address, t) =>
            TransactionReference.of(t)(Async[IO], txHasher).map(address -> _)
        }
        .map(SortedMap.from(_))
        .map(lastInfo.lastTxRefs ++ _)
        .map { src =>
          val dst = txs.map(_.destination).toSet.filterNot(src.contains).map(_ -> TransactionReference.empty)
          src ++ dst
        }
      balances = SortedMap.from(
        txs
          .map(_.value)
          .foldLeft(lastInfo.balances.view.mapValues(_.value.value).toMap) {
            case (aggBalances, tx) =>
              val srcBalance = aggBalances.getOrElse(tx.source, 0L) - (tx.amount.value.value + tx.fee.value.value)
              val dstBalance = aggBalances.getOrElse(tx.destination, 0L) + tx.amount.value.value

              aggBalances ++ Map(tx.source -> srcBalance, tx.destination -> dstBalance)
          }
          .view
          .mapValues(l => Balance(NonNegLong.unsafeFrom(l)))
      )
      newSnapshotInfo = lastInfo.copy(
        lastTxRefs = lastTxRefs,
        balances = balances
      )
      newSnapshotInfoStateProof <- newSnapshotInfo.stateProof[IO](lastSnapshot.ordinal.next)
      snapshot = GlobalIncrementalSnapshot(
        lastSnapshot.ordinal.next,
        Height.MinValue,
        SubHeight.MinValue,
        lastHash,
        blocks.toSortedSet,
        SortedMap.empty,
        SortedSet.empty,
        None,
        lastSnapshot.epochProgress,
        NonEmptyList.of(PeerId(Hex("peer1"))),
        lastSnapshot.tips.copy(remainedActive = activeTips),
        newSnapshotInfoStateProof,
        Some(SortedSet.empty),
        Some(SortedSet.empty),
        Some(SortedMap.empty),
        None,
        Some(SortedSet.empty),
        Some(SortedMap.empty),
        Some(SortedMap.empty),
        Some(SortedMap.empty),
        Some(SortedMap.empty)
      )
      signed <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](snapshot, keyPair)
      hashed <- signed.toHashed
    } yield (hashed, newSnapshotInfo)

  type DAGS = (List[Address], Long, SortedMap[Address, Signed[Transaction]], List[List[BlockAsActiveTip]])

  def mkBlocks(
    feeValue: NonNegLong,
    numberOfAddresses: Int,
    txnsChunksRanges: List[(Int, Int)],
    blocksChunksRanges: List[(Int, Int)],
    currentHasher: Hasher[IO],
    txHasher: Hasher[IO]
  )(
    implicit S: SecurityProvider[IO],
    K: KryoSerializer[IO],
    R: Random[IO]
  ): IO[DAGS] = for {
    keyPairs <- (1 to numberOfAddresses).toList.traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    addressParams = keyPairs.map(keyPair => AddressParams(keyPair))
    addresses = keyPairs.map(_.getPublic.toAddress)
    txnsSize = if (txnsChunksRanges.nonEmpty) txnsChunksRanges.map(_._2).max.toLong else 0
    txns <- {
      implicit val hasher = txHasher
      TransactionGenerator
        .infiniteTransactionStream[IO](PosInt.unsafeFrom(1), feeValue, NonEmptyList.fromListUnsafe(addressParams))
        .take(txnsSize)
        .compile
        .toList
    }
    lastTxns = txns.groupBy(_.source).view.mapValues(_.last).toMap.toSortedMap
    transactionsChain = txnsChunksRanges
      .foldLeft[List[List[Signed[Transaction]]]](Nil) { case (acc, (start, end)) => txns.slice(start, end) :: acc }
      .map(txns => NonEmptySet.fromSetUnsafe(SortedSet.from(txns)))
      .reverse
    blockSigningKeyPairs <- NonEmptyList.of("", "", "").traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    dags <- {
      implicit val hasher = currentHasher
      DAGBlockGenerator.createDAGs(transactionsChain, initialReferences(), blockSigningKeyPairs).compile.toList
    }
    chaunkedDags = blocksChunksRanges
      .foldLeft[List[List[BlockAsActiveTip]]](Nil) { case (acc, (start, end)) => dags.slice(start, end) :: acc }
      .reverse
  } yield (addresses, txnsSize, lastTxns, chaunkedDags)

  def gst(
    globalSnapshot: Hashed[GlobalSnapshot],
    incrementalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    rollbackHash: Hash
  )(implicit J: JsonSerializer[IO], H: Hasher[IO], S: SecurityProvider[IO], K: KryoSerializer[IO]) = {
    def loadGlobalSnapshot(hash: Hash): IO[Option[Signed[GlobalSnapshot]]] =
      hash match {
        case h if h === globalSnapshot.hash => Some(globalSnapshot.signed).pure[IO]
        case _                              => None.pure[IO]
      }
    def loadGlobalIncrementalSnapshot(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] =
      hash match {
        case h if h =!= globalSnapshot.hash =>
          Some(incrementalSnapshots.map(snapshot => (snapshot.hash, snapshot)).toMap.get(hash).get.signed).pure[IO]
        case _ => None.pure[IO]
      }

    def loadInfo: SnapshotOrdinal => IO[Option[GlobalSnapshotInfo]] = _ => None.pure[F]

    val txHasher = Hasher.forKryo[IO]

    val addressesConfig = AddressesConfig(Set())

    val signedValidator = SignedValidator.make[IO]
    val blockValidator =
      BlockValidator.make[IO](
        signedValidator,
        TransactionChainValidator.make[IO](Hasher.forKryo[IO]),
        TransactionValidator.make[IO](addressesConfig, signedValidator, txHasher),
        txHasher
      )
    val blockAcceptanceManager = BlockAcceptanceManager.make(BlockAcceptanceLogic.make[IO](txHasher), blockValidator, txHasher)

    val allowSpendBlockValidator =
      AllowSpendBlockValidator.make[IO](signedValidator, AllowSpendChainValidator.make[IO], AllowSpendValidator.make[IO](signedValidator))
    val allowSpendBlockAcceptanceManager =
      AllowSpendBlockAcceptanceManager.make(AllowSpendBlockAcceptanceLogic.make[IO], allowSpendBlockValidator)

    val tokenLockBlockValidator =
      TokenLockBlockValidator.make[IO](signedValidator, TokenLockChainValidator.make[IO], TokenLockValidator.make[IO](signedValidator))
    val tokenLockBlockAcceptanceManager =
      TokenLockBlockAcceptanceManager.make(TokenLockBlockAcceptanceLogic.make[IO], tokenLockBlockValidator)

    val feeCalculator = FeeCalculator.make(SortedMap.empty)
    val validators =
      SharedValidators
        .make[IO](
          addressesConfig,
          None,
          None,
          Some(Map.empty[Address, NonEmptySet[PeerId]]),
          SortedMap.empty,
          Long.MaxValue,
          txHasher,
          DelegatedStakingConfig(
            RewardFraction(5_000_000),
            RewardFraction(10_000_000),
            PosInt(140),
            PosInt(10),
            PosLong((5000 * 1e8).toLong),
            Map(Dev -> EpochProgress(NonNegLong(7338977L)))
          )
        )

    val currencyEventsCutter = CurrencyEventsCutter.make[IO](None)

    implicit val hs = HasherSelector.forSyncAlwaysCurrent(H)

    for {
      validationErrorStorage <- CurrencySnapshotEventValidationErrorStorage.make(TestValidationErrorStorageMaxSize)
      currencySnapshotAcceptanceManager <- CurrencySnapshotAcceptanceManager.make(
        SnapshotOrdinal.MinValue,
        LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(10), PosInt(10)),
        BlockAcceptanceManager.make[IO](validators.currencyBlockValidator, txHasher),
        TokenLockBlockAcceptanceManager.make[IO](validators.tokenLockBlockValidator),
        AllowSpendBlockAcceptanceManager.make[IO](validators.allowSpendBlockValidator),
        Amount(0L),
        validators.currencyMessageValidator,
        validators.feeTransactionValidator,
        validators.globalSnapshotSyncValidator
      )
      currencySnapshotCreator =
        CurrencySnapshotCreator
          .make[IO](
            SnapshotOrdinal.MinValue,
            currencySnapshotAcceptanceManager,
            None,
            SnapshotSizeConfig(Long.MaxValue, Long.MaxValue),
            currencyEventsCutter,
            validationErrorStorage
          )
      currencySnapshotValidator = CurrencySnapshotValidator
        .make[IO](SnapshotOrdinal.MinValue, currencySnapshotCreator, validators.signedValidator, None, None)

      currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(currencySnapshotValidator)
      stateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager.make[IO](None, NonNegLong(10L))
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync
      feeCalculator = FeeCalculator.make(SortedMap.empty)
      stateChannelProcessor = GlobalSnapshotStateChannelEventsProcessor
        .make[IO](
          validators.stateChannelValidator,
          stateChannelManager,
          currencySnapshotContextFns,
          jsonBrotliBinarySerializer,
          feeCalculator
        )
      updateNodeParametersAcceptanceManager = UpdateNodeParametersAcceptanceManager.make(validators.updateNodeParametersValidator)
      updateDelegatedStakeAcceptanceManager = UpdateDelegatedStakeAcceptanceManager.make(
        validators.updateDelegatedStakeValidator
      )
      updateNodeCollateralAcceptanceManager = UpdateNodeCollateralAcceptanceManager.make(
        validators.updateNodeCollateralValidator
      )
      snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager
        .make[IO](
          SnapshotOrdinal.MinValue,
          blockAcceptanceManager,
          allowSpendBlockAcceptanceManager,
          tokenLockBlockAcceptanceManager,
          stateChannelProcessor,
          updateNodeParametersAcceptanceManager,
          updateDelegatedStakeAcceptanceManager,
          updateNodeCollateralAcceptanceManager,
          validators.spendActionValidator,
          Amount.empty,
          EpochProgress(NonNegLong(136080L))
        )
      snapshotContextFunctions = GlobalSnapshotContextFunctions.make[IO](
        snapshotAcceptanceManager,
        updateDelegatedStakeAcceptanceManager,
        EpochProgress(NonNegLong.unsafeFrom(1L)),
        SnapshotOrdinal.MinValue
      )
    } yield
      GlobalSnapshotTraverse
        .make[IO](
          loadGlobalIncrementalSnapshot,
          loadGlobalSnapshot,
          loadInfo,
          snapshotContextFunctions,
          rollbackHash,
          List.empty.pure[IO],
          _ => None.pure[IO]
        )
  }

  test("can compute state for given incremental global snapshot") { res =>
    implicit val (ks, h, j, sp, _, _) = res

    for {
      snapshots <- mkSnapshots(List.empty, balances)
      traverser <- gst(snapshots._1, snapshots._2.toList, snapshots._2.head.hash)
      state <- traverser.loadChain()
    } yield
      expect.eql(
        GlobalSnapshotInfo(
          SortedMap.empty,
          SortedMap.empty,
          SortedMap.from(balances),
          SortedMap.empty,
          SortedMap.empty,
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty)
        ),
        state._1
      )
  }

  test("computed state contains last refs and preserve total amount of balances when no fees or rewards ") {
    case (ks, h, j, sp, _, random) =>
      implicit val (a, b, c, d) = (ks, j, sp, random)

      forall(dagBlockChainGen(currentHasher = h)) { output: IO[DAGS] =>
        for {
          (addresses, _, lastTxns, chunkedDags) <- output
          (global, incrementals) <- {
            implicit val hasher = h
            mkSnapshots(
              chunkedDags,
              addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap
            )
          }
          traverser <- {
            implicit val hasher = h
            gst(global, incrementals.toList, incrementals.last.hash)
          }
          (info, _) <- traverser.loadChain()
          totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
          lastTxRefs <- {
            implicit val hasher = Hasher.forKryo[IO]

            lastTxns.traverse(TransactionReference.of(_))
          }
        } yield expect.eql((info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L))), (lastTxRefs, totalBalance))

      }
  }

  test("computed state contains last refs and include fees in total amount of balances") {
    case (ks, h, j, sp, _, random) =>
      implicit val (a, b, c, d) = (ks, j, sp, random)

      forall(dagBlockChainGen(1L, currentHasher = h)) { output: IO[DAGS] =>
        for {
          (addresses, txnsSize, lastTxns, chunkedDags) <- output
          (global, incrementals) <- {
            implicit val hasher = h
            mkSnapshots(
              chunkedDags,
              addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap
            )
          }
          traverser <- {
            implicit val hasher = h
            gst(global, incrementals.toList, incrementals.last.hash)
          }
          (info, _) <- traverser.loadChain()
          totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
          lastTxRefs <- {
            implicit val hasher = Hasher.forKryo[IO]
            lastTxns.traverse(TransactionReference.of(_))
          }
        } yield
          expect.eql((info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L - txnsSize * 1L))), (lastTxRefs, totalBalance))

      }
  }

  private def initialReferences() =
    NonEmptyList.fromListUnsafe(
      List
        .range(0, 4)
        .map { i =>
          BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i)))
        }
    )

  private def dagBlockChainGen(
    feeValue: NonNegLong = 0L,
    currentHasher: Hasher[IO]
  )(implicit r: Random[IO], sc: SecurityProvider[IO], k: KryoSerializer[IO]): Gen[IO[DAGS]] = {
    val txHasher = Hasher.forKryo[IO]

    for {
      numberOfAddresses <- Gen.choose(2, 5)
      txnsChunksRanges <- Gen
        .listOf(Gen.choose(0, 50))
        .map(l => (0 :: l).distinct.sorted)
        .map(list => list.zip(list.tail))
      blocksChunksRanges <- Gen
        .const((0 to txnsChunksRanges.size).toList)
        .map(l => (0 :: l).distinct.sorted)
        .map(list => list.zip(list.tail))
    } yield mkBlocks(feeValue, numberOfAddresses, txnsChunksRanges, blocksChunksRanges, currentHasher, txHasher)
  }

}

package io.constellationnetwork.dag.l0.domain.statechannel

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.syntax.validated._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.dag.l0.domain.cell.L0Cell
import io.constellationnetwork.dag.l0.domain.delegatedStake.DelegatedStakeOutput
import io.constellationnetwork.dag.l0.domain.nodeCollateral.NodeCollateralOutput
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.statechannel.{SnapshotFeesInfo, StateChannelValidator}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object StateChannelServiceSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO], MptStore[IO, GlobalStateKey])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
    mptProducer <- InMemoryMerklePatriciaProducer.make[IO]().asResource
    mptStore <- MptStore.make[IO, GlobalStateKey](mptProducer, GlobalStateKey.toHex[IO]).asResource
  } yield (h, sp, mptStore)

  test("state channel output processed successfully") { res =>
    implicit val (h, sp, mptStore) = res

    for {
      output <- mkStateChannelOutput()
      service <- mkService(mptStore)
      snapshotAndInfo = mkEmptyGlobalSnapshotAndState()
      result <- service.process(output, snapshotAndInfo)
    } yield expect.same(Right(()), result)

  }

  test("state channel output failed on validation") { res =>
    implicit val (h, sp, mptStore) = res

    for {
      output <- mkStateChannelOutput()
      expected = StateChannelValidator.NotSignedExclusivelyByStateChannelOwner
      service <- mkService(mptStore, Some(expected))
      snapshotAndInfo = mkEmptyGlobalSnapshotAndState()
      result <- service.process(output, snapshotAndInfo)
    } yield expect.same(Left(NonEmptyList.of(expected)), result)

  }

  def mkService(mptStore: MptStore[IO, GlobalStateKey], failed: Option[StateChannelValidator.StateChannelValidationError] = None) = {
    val validator = new StateChannelValidator[IO] {
      def validate(
        output: StateChannelOutput,
        globalOrdinal: SnapshotOrdinal,
        snapshotFeesInfo: SnapshotFeesInfo
      )(implicit hasher: Hasher[IO]) =
        IO.pure(failed.fold[StateChannelValidator.StateChannelValidationErrorOr[StateChannelOutput]](output.validNec)(_.invalidNec))

      def validateHistorical(output: StateChannelOutput, globalOrdinal: SnapshotOrdinal, snapshotFeesInfo: SnapshotFeesInfo)(
        implicit hasher: Hasher[IO]
      ) =
        validate(output, globalOrdinal, snapshotFeesInfo)
    }

    for {
      dagQueue <- Queue.unbounded[IO, Signed[Block]]
      scQueue <- Queue.unbounded[IO, StateChannelOutput]
      unpQueue <- Queue.unbounded[IO, Signed[UpdateNodeParameters]]
      dsQueue <- Queue.unbounded[IO, DelegatedStakeOutput]
      ncQueue <- Queue.unbounded[IO, NodeCollateralOutput]
    } yield StateChannelService.make[IO](L0Cell.mkL0Cell[IO](dagQueue, scQueue, unpQueue, dsQueue, ncQueue), validator, mptStore)
  }

  def mkStateChannelOutput()(implicit S: SecurityProvider[IO], H: Hasher[IO]) = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)
    signedSC <- forAsyncHasher(binary, keyPair)

  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

  def mkEmptyGlobalSnapshotAndState(): (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo) = {
    val snapshot =
      Signed(
        GlobalIncrementalSnapshot(
          SnapshotOrdinal(NonNegLong(1L)),
          Height.MinValue,
          SubHeight.MinValue,
          Hash.empty,
          SortedSet.empty,
          SortedMap.empty,
          SortedSet.empty,
          None,
          EpochProgress.MinValue,
          NonEmptyList.of(PeerId(Hex(""))),
          SnapshotTips(SortedSet.empty, SortedSet.empty),
          stateProof = GlobalSnapshotStateProof(
            lastStateChannelSnapshotHashesProof = Hash.empty,
            lastTxRefsProof = Hash.empty,
            balancesProof = Hash.empty,
            lastCurrencySnapshotsProof = None,
            activeAllowSpends = None,
            activeTokenLocks = None,
            tokenLockBalances = None,
            lastAllowSpendRefs = None,
            lastTokenLockRefs = None,
            updateNodeParameters = None,
            activeDelegatedStakes = None,
            delegatedStakesWithdrawals = None,
            activeNodeCollaterals = None,
            nodeCollateralWithdrawals = None,
            priceState = None,
            lastGlobalSnapshotsWithCurrency = None,
            mptRoot = None,
            retiredAllowSpendRefs = None
          ),
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None
        ),
        NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(ID.Id(Hex("")), Signature(Hex("")))))
      )
    val info = GlobalSnapshotInfo.empty

    (snapshot, info)
  }

}

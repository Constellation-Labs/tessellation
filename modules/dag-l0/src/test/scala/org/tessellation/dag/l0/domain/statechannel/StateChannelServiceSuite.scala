package org.tessellation.dag.l0.domain.statechannel

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.syntax.validated._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.dag.l0.domain.cell.L0Cell
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator
import org.tessellation.schema._
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncHasher
import org.tessellation.security.signature.signature.{Signature, SignatureProof}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object StateChannelServiceSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (h, sp)

  test("state channel output processed successfully") { res =>
    implicit val (h, sp) = res

    for {
      output <- mkStateChannelOutput()
      service <- mkService()
      snapshotAndInfo = mkEmptyGlobalSnapshotAndState()
      result <- service.process(output, snapshotAndInfo)
    } yield expect.same(Right(()), result)

  }

  test("state channel output failed on validation") { res =>
    implicit val (h, sp) = res

    for {
      output <- mkStateChannelOutput()
      expected = StateChannelValidator.NotSignedExclusivelyByStateChannelOwner
      service <- mkService(Some(expected))
      snapshotAndInfo = mkEmptyGlobalSnapshotAndState()
      result <- service.process(output, snapshotAndInfo)
    } yield expect.same(Left(NonEmptyList.of(expected)), result)

  }

  def mkService(failed: Option[StateChannelValidator.StateChannelValidationError] = None) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput, globalOrdinal: SnapshotOrdinal, staked: Balance)(implicit hasher: Hasher[IO]) =
        IO.pure(failed.fold[StateChannelValidator.StateChannelValidationErrorOr[StateChannelOutput]](output.validNec)(_.invalidNec))

      def validateHistorical(output: StateChannelOutput, globalOrdinal: SnapshotOrdinal, staked: Balance)(implicit hasher: Hasher[IO]) =
        validate(output, globalOrdinal, staked)
    }

    for {
      dagQueue <- Queue.unbounded[IO, Signed[Block]]
      scQueue <- Queue.unbounded[IO, StateChannelOutput]
    } yield StateChannelService.make[IO](L0Cell.mkL0Cell[IO](dagQueue, scQueue), validator)
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
          EpochProgress.MinValue,
          NonEmptyList.of(PeerId(Hex(""))),
          SnapshotTips(SortedSet.empty, SortedSet.empty),
          stateProof = GlobalSnapshotStateProof(Hash.empty, Hash.empty, Hash.empty, None)
        ),
        NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(ID.Id(Hex("")), Signature(Hex("")))))
      )
    val info = GlobalSnapshotInfo.empty

    (snapshot, info)
  }

}

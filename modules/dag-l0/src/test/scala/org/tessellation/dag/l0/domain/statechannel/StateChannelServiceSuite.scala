package org.tessellation.dag.l0.domain.statechannel

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.syntax.validated._

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.dag.l0.domain.cell.L0Cell
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator
import org.tessellation.schema.Block
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncHasher
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
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
      result <- service.process(output)
    } yield expect.same(Right(()), result)

  }

  test("state channel output failed on validation") { res =>
    implicit val (h, sp) = res

    for {
      output <- mkStateChannelOutput()
      expected = StateChannelValidator.NotSignedExclusivelyByStateChannelOwner
      service <- mkService(Some(expected))
      result <- service.process(output)
    } yield expect.same(Left(NonEmptyList.of(expected)), result)

  }

  def mkService(failed: Option[StateChannelValidator.StateChannelValidationError] = None) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput)(implicit hasher: Hasher[IO]) =
        IO.pure(failed.fold[StateChannelValidator.StateChannelValidationErrorOr[StateChannelOutput]](output.validNec)(_.invalidNec))

      def validateHistorical(output: StateChannelOutput)(implicit hasher: Hasher[IO]) = validate(output)
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

}

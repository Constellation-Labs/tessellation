package org.tessellation.domain.statechannel

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.syntax.validated._

import org.tessellation.domain.cell.L0Cell
import org.tessellation.domain.statechannel.StateChannelValidator.StateChannelValidationErrorOr
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.block.DAGBlock
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object StateChannelServiceSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, StateChannelServiceSuite.Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  test("state channel output processed successfully") { res =>
    implicit val (kryo, sp) = res

    for {
      output <- mkStateChannelOutput()
      service <- mkService()
      result <- service.process(output)
    } yield expect.same(Right(()), result)

  }

  test("state channel output failed on validation") { res =>
    implicit val (kryo, sp) = res

    for {
      output <- mkStateChannelOutput()
      expected = StateChannelValidator.NotSignedExclusivelyByStateChannelOwner
      service <- mkService(Some(expected))
      result <- service.process(output)
    } yield expect.same(Left(NonEmptyList.of(expected)), result)

  }

  def mkService(failed: Option[StateChannelValidator.StateChannelValidationError] = None) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput) =
        IO.pure(failed.fold[StateChannelValidationErrorOr[StateChannelOutput]](output.validNec)(_.invalidNec))
    }

    for {
      dagQueue <- Queue.unbounded[IO, Signed[DAGBlock]]
      scQueue <- Queue.unbounded[IO, StateChannelOutput]
    } yield StateChannelService.make[IO](L0Cell.mkL0Cell[IO](dagQueue, scQueue), validator)
  }

  def mkStateChannelOutput()(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes)
    signedSC <- forAsyncKryo(binary, keyPair)

  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}

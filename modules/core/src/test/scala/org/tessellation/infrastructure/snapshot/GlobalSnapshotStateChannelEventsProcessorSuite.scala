package org.tessellation.infrastructure.snapshot

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.validated._

import scala.collection.immutable.SortedMap

import org.tessellation.domain.statechannel.StateChannelValidator
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.GlobalSnapshotInfo
import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import weaver.MutableIOSuite

object GlobalSnapshotStateChannelEventsProcessorSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotStateChannelEventsProcessorSuite.Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  def mkProcessor(failed: Option[(Address, StateChannelValidator.StateChannelValidationError)] = None)(implicit K: KryoSerializer[IO]) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput) =
        IO.pure(failed.filter(f => f._1 == output.address).map(_._2.invalidNec).getOrElse(output.validNec))
    }
    GlobalSnapshotStateChannelEventsProcessor.make[IO](validator)
  }

  test("return new sc event") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output <- mkStateChannelOutput(keyPair)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor()
      expected = (SortedMap((address, NonEmptyList.one(output.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output :: Nil)
    } yield expect.same(expected, result)

  }

  test("return two dependent sc events") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output1Hash <- output1.snapshot.hashF
      output2 <- mkStateChannelOutput(keyPair, Some(output1Hash))
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor()
      expected = (SortedMap((address, NonEmptyList.of(output2.snapshot, output1.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return sc event when reference to last state channel snapshot hash is correct") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output1Hash <- output1.snapshot.hashF
      output2 <- mkStateChannelOutput(keyPair, Some(output1Hash))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap((address, output1Hash)))
      service = mkProcessor()
      expected = (SortedMap((address, NonEmptyList.of(output2.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return no sc events when reference to last state channel snapshot hash is incorrect") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output2 <- mkStateChannelOutput(keyPair, Some(Hash.fromBytes("incorrect".getBytes())))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap((address, Hash.fromBytes(output1.snapshot.content))))
      service = mkProcessor()
      expected = (SortedMap.empty[Address, NonEmptyList[StateChannelSnapshotBinary]], Set.empty)
      result <- service.process(snapshotInfo, output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return sc events for different addresses") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair1.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair1)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic().toAddress
      output2 <- mkStateChannelOutput(keyPair2)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor()
      expected = (
        SortedMap((address1, NonEmptyList.of(output1.snapshot)), (address2, NonEmptyList.of(output2.snapshot))),
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return only valid sc events") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair1.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair1)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic().toAddress
      output2 <- mkStateChannelOutput(keyPair2)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor(Some(address1 -> StateChannelValidator.NotSignedExclusivelyByStateChannelOwner))
      expected = (
        SortedMap((address2, NonEmptyList.of(output2.snapshot))),
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  def mkStateChannelOutput(keyPair: KeyPair, hash: Option[Hash] = None)(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) = for {
    content <- Random.scalaUtilRandom[IO].flatMap(_.nextString(10))
    binary <- StateChannelSnapshotBinary(hash.getOrElse(Hash.empty), content.getBytes).pure[IO]
    signedSC <- forAsyncKryo(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

  def mkGlobalSnapshotInfo(lastStateChannelSnapshotHashes: SortedMap[Address, Hash] = SortedMap.empty) =
    GlobalSnapshotInfo(lastStateChannelSnapshotHashes, SortedMap.empty, SortedMap.empty)

}

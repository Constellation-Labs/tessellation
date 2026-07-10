package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import cats.effect.{IO, Resource}

import io.constellationnetwork.dag.l0.domain.snapshot.recovery.RecoveryCheckpoint.{InvalidCheckpointSignatures, NetworkMismatch}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.{Signed, SignedValidator}

import weaver.MutableIOSuite

object RecoveryCheckpointSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    JsonSerializer.forAsync[IO].asResource.flatMap { implicit json =>
      val h = Hasher.forJson[IO]
      SecurityProvider.forAsync[IO].map((h, _))
    }

  private val network = "Testnet"
  private val ordinal = SnapshotOrdinal.unsafeApply(1000L)
  private val snapshotHash = Hash("a" * 64)
  private val forkHash = Hash("b" * 64)
  private def checkpoint(net: String = network): RecoveryCheckpoint = RecoveryCheckpoint(net, ordinal, snapshotHash)

  test("mismatchAt: no checkpoint configured is never a mismatch") { _ =>
    IO.pure(expect(RecoveryCheckpoint.mismatchAt(None, ordinal, forkHash).isEmpty, "None checkpoint must not flag a fork"))
  }

  test("mismatchAt: a different ordinal is never a mismatch") { _ =>
    val other = SnapshotOrdinal.unsafeApply(999L)
    IO.pure(
      expect(RecoveryCheckpoint.mismatchAt(Some(checkpoint()), other, forkHash).isEmpty, "non-checkpoint ordinal must not flag a fork")
    )
  }

  test("mismatchAt: the matching hash at the checkpoint ordinal is not a mismatch") { _ =>
    IO.pure(expect(RecoveryCheckpoint.mismatchAt(Some(checkpoint()), ordinal, snapshotHash).isEmpty, "canonical hash must pass"))
  }

  test("mismatchAt: a different hash at the checkpoint ordinal is a fork, returning (expected, got)") { _ =>
    IO.pure(
      expect(
        RecoveryCheckpoint.mismatchAt(Some(checkpoint()), ordinal, forkHash).contains((snapshotHash, forkHash)),
        "fork hash at the checkpoint ordinal must return (expected, got)"
      )
    )
  }

  // Sign `value` with the first key, then accumulate the remaining keys' signatures.
  private def signWith[A: io.circe.Encoder](
    value: A,
    keys: List[java.security.KeyPair]
  )(implicit h: Hasher[IO], sp: SecurityProvider[IO]): IO[Signed[A]] =
    keys match {
      case head :: tail => tail.foldLeft(forAsyncHasher(value, head))((acc, kp) => acc.flatMap(_.signAlsoWith(kp)))
      case Nil          => IO.raiseError(new IllegalArgumentException("need at least one signer"))
    }

  test("verify succeeds when signed by a seedlist majority with the expected network") { res =>
    implicit val (h, sp) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      seedlist = keys.map(kp => PeerId.fromPublic(kp.getPublic)).toSet
      signed <- signWith(checkpoint(), keys.take(2)) // 2-of-3 == majority(3)
      result <- RecoveryCheckpoint.verify(SignedValidator.make[IO], seedlist, network, signed)
    } yield expect(result == Right(checkpoint()), s"expected Right(checkpoint), got $result")
  }

  test("verify rejects a checkpoint signed for a different network") { res =>
    implicit val (h, sp) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      seedlist = keys.map(kp => PeerId.fromPublic(kp.getPublic)).toSet
      signed <- signWith(checkpoint(net = "Mainnet"), keys.take(2))
      result <- RecoveryCheckpoint.verify(SignedValidator.make[IO], seedlist, network, signed)
    } yield
      expect(
        result == Left(NetworkMismatch(network, "Mainnet")),
        s"expected NetworkMismatch, got $result"
      )
  }

  test("verify rejects a checkpoint below the seedlist majority") { res =>
    implicit val (h, sp) = res
    for {
      keys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      seedlist = keys.map(kp => PeerId.fromPublic(kp.getPublic)).toSet
      signed <- signWith(checkpoint(), keys.take(1)) // 1-of-3 < majority(3)=2
      result <- RecoveryCheckpoint.verify(SignedValidator.make[IO], seedlist, network, signed)
    } yield
      result match {
        case Left(e: InvalidCheckpointSignatures) =>
          expect(e.reason.contains("NotEnoughSeedlistSignatures"), s"reason should name the threshold failure, got: ${e.reason}")
        case other => failure(s"expected InvalidCheckpointSignatures, got $other")
      }
  }

  test("verify rejects a checkpoint with a signer outside the seedlist") { res =>
    implicit val (h, sp) = res
    for {
      seedKeys <- List.fill(3)(KeyPairGenerator.makeKeyPair[IO]).sequence
      outsider <- KeyPairGenerator.makeKeyPair[IO]
      seedlist = seedKeys.map(kp => PeerId.fromPublic(kp.getPublic)).toSet
      // 2 in-seedlist signers (>= majority) plus 1 outsider: majority passes, membership must fail.
      signed <- signWith(checkpoint(), seedKeys.take(2) :+ outsider)
      result <- RecoveryCheckpoint.verify(SignedValidator.make[IO], seedlist, network, signed)
    } yield
      result match {
        case Left(e: InvalidCheckpointSignatures) =>
          expect(e.reason.contains("SignersNotInSeedlist"), s"reason should name the membership failure, got: ${e.reason}")
        case other => failure(s"expected InvalidCheckpointSignatures, got $other")
      }
  }
}

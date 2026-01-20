package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.GlobalStateProofPatternsSuite.F
import io.constellationnetwork.security.mpt.prover.MerklePatriciaRangeProver
import io.constellationnetwork.security.mpt.prover.attestation.{MerklePatriciaRangeProof, RangeExclusionBoundaries}
import io.constellationnetwork.security.mpt.verifier.MerklePatriciaRangeVerifier
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.{MutableIOSuite, SimpleIOSuite}

object MerklePatriciaRangeVerifierSuite extends MutableIOSuite {

  type Res = HasherSelector[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit kryo =>
      JsonSerializer.forAsync[IO].asResource.map { implicit json =>
        HasherSelector.forSync[IO](
          Hasher.forJson[IO],
          Hasher.forKryo[IO],
          hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
        )
      }
    }

  test("valid range proof with entries verifies successfully") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "3000"
      val end = "7000"

      for {
        entries <- List("1000", "2000", "4000", "5000", "6000", "8000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaRangeProver.make[IO](trie)

        proof <- prover.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)

        trieRoot <- MerklePatriciaTrie.getRootHash[F](trie)
        verifier = MerklePatriciaRangeVerifier.make[IO](trieRoot.value)
        result <- verifier.confirmRange(proof)
      } yield expect(result.isRight)
    }
  }

  test("empty range with boundaries verifies gap is proven") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "5000"
      val end = "5500"

      for {
        entries <- List("1000", "2000", "6000", "7000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaRangeProver.make[IO](trie)

        proof <- prover.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)

        trieRoot <- MerklePatriciaTrie.getRootHash[F](trie)

        verifier = MerklePatriciaRangeVerifier.make[IO](trieRoot.value)
        result <- verifier.confirmRange(proof)
      } yield
        expect.all(
          result.isRight,
          proof.inclusionProofs.isEmpty,
          proof.exclusionBoundaries.isDefined
        )
    }
  }

  test("boundary validation enforces constraints") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "5000"
      val end = "7000"

      for {
        entries <- List("3000", "4000", "5500", "6000", "8000", "9000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaRangeProver.make[IO](trie)

        proof <- prover.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)

        trieRoot <- MerklePatriciaTrie.getRootHash[F](trie)

        verifier = MerklePatriciaRangeVerifier.make[IO](trieRoot.value)
        result <- verifier.confirmRange(proof)
      } yield expect(result.isRight)
    }
  }

  test("missing left boundary when start is at minimum") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- List("2000", "3000", "4000", "5000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaRangeProver.make[IO](trie)

        minKey = entries.map(_._1).min(Ordering.by[Hex, String](_.value))
        proof <- prover.attestRange(minKey, Hex("3500".padTo(64, '0'))).flatMap(IO.fromEither)

        trieRoot <- MerklePatriciaTrie.getRootHash[F](trie)

        verifier = MerklePatriciaRangeVerifier.make[IO](trieRoot.value)
        result <- verifier.confirmRange(proof)
      } yield
        expect.all(
          result.isRight,
          proof.exclusionBoundaries.flatMap(_.leftBoundary).isEmpty
        )
    }
  }

  test("missing right boundary when end is at maximum") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- List("2000", "3000", "4000", "5000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaRangeProver.make[IO](trie)

        maxKey = entries.map(_._1).max(Ordering.by[Hex, String](_.value))
        proof <- prover.attestRange(Hex("3500".padTo(64, '0')), maxKey).flatMap(IO.fromEither)

        trieRoot <- MerklePatriciaTrie.getRootHash[F](trie)

        verifier = MerklePatriciaRangeVerifier.make[IO](trieRoot.value)
        result <- verifier.confirmRange(proof)
      } yield
        expect.all(
          result.isRight,
          proof.exclusionBoundaries.flatMap(_.rightBoundary).isEmpty
        )
    }
  }

  test("unsorted paths fail verification") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "3000"
      val end = "7000"

      for {
        entries <- List("4000", "5000", "6000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }
        trie <- MerklePatriciaTrie.make[IO, String](entries.toMap)
        prover = MerklePatriciaRangeProver.make[IO](trie)

        proof <- prover.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)

        reversedProofs = proof.inclusionProofs.reverse
        tamperedProof = proof.copy(inclusionProofs = reversedProofs)

        trieRoot <- MerklePatriciaTrie.getRootHash[F](trie)

        verifier = MerklePatriciaRangeVerifier.make[IO](trieRoot.value)
        result <- verifier.confirmRange(tamperedProof)
      } yield expect(result.isLeft)
    }
  }

  test("paths outside range fail verification") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "5000"
      val end = "7000"

      for {
        entries <- List("3000", "4000", "5500", "6000", "8000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaRangeProver.make[IO](trie)

        validProof <- prover.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)
        wideProof <- prover.attestRange(Hex("3000".padTo(64, '0')), Hex("8000".padTo(64, '0'))).flatMap(IO.fromEither)

        tamperedProof = validProof.copy(inclusionProofs = wideProof.inclusionProofs)

        trieRoot <- MerklePatriciaTrie.getRootHash[F](trie)

        verifier = MerklePatriciaRangeVerifier.make[IO](trieRoot.value)
        result <- verifier.confirmRange(tamperedProof)
      } yield expect(result.isLeft)
    }
  }
}

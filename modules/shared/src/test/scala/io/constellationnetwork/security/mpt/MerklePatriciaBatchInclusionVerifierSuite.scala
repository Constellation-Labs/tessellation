package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.prover.attestation.MerklePatriciaBatchInclusionProof
import io.constellationnetwork.security.mpt.prover.{MerklePatriciaBatchInclusionProver, MerklePatriciaSingleInclusionProver}
import io.constellationnetwork.security.mpt.verifier.MerklePatriciaBatchInclusionVerifier
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.{MutableIOSuite, SimpleIOSuite}

object MerklePatriciaBatchInclusionVerifierSuite extends MutableIOSuite {

  type Res = (HasherSelector[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit kryo =>
      JsonSerializer.forAsync[IO].asResource.map { implicit json =>
        (
          HasherSelector.forSync[IO](
            Hasher.forJson[IO],
            Hasher.forKryo[IO],
            hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
          ),
          json
        )
      }
    }

  test("valid batch proof verification succeeds") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 20).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        batchProver = MerklePatriciaBatchInclusionProver.make[IO](trie)

        paths = entries.take(5).map(_._1)
        proof <- batchProver.attestPaths(paths).flatMap(IO.fromEither)

        verifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
        result <- verifier.confirm(proof)
      } yield expect(result.isRight)
    }
  }

  test("partial path validity fails entire batch") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        batchProver = MerklePatriciaBatchInclusionProver.make[IO](trie)

        validPaths = entries.take(3).map(_._1)
        invalidPath = Hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")
        mixedPaths = validPaths :+ invalidPath

        singleProver = MerklePatriciaSingleInclusionProver.make[IO](trie)
        validProofs <- validPaths.traverse(path => singleProver.attestPath(path).flatMap(IO.fromEither))

        fakeProof = MerklePatriciaBatchInclusionProof(
          mixedPaths.sorted(Ordering.by[Hex, String](_.value)),
          validProofs.flatMap(_.witness).distinct
        )

        verifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
        result <- verifier.confirm(fakeProof)
      } yield expect(result.isLeft)
    }
  }

  test("incomplete witness fails verification") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        batchProver = MerklePatriciaBatchInclusionProver.make[IO](trie)

        paths = entries.take(5).map(_._1)
        proof <- batchProver.attestPaths(paths).flatMap(IO.fromEither)

        incompleteProof = proof.copy(witness = proof.witness.take(proof.witness.size / 2))

        verifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
        result <- verifier.confirm(incompleteProof)
      } yield expect(result.isLeft)
    }
  }

  test("empty batch fails verification") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)

        emptyProof = MerklePatriciaBatchInclusionProof(List.empty, List.empty)

        verifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
        result <- verifier.confirm(emptyProof)
      } yield expect(result.isLeft)
    }
  }

  test("deduplicated witness verifies correctly") { implicit res =>
    implicit val (hs, js) = res
    hs.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 20).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        batchProver = MerklePatriciaBatchInclusionProver.make[IO](trie)

        paths = entries.map(_._1)
        proof <- batchProver.attestPaths(paths).flatMap(IO.fromEither)

        verifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
        result <- verifier.confirm(proof)
      } yield
        expect.all(
          result.isRight,
          proof.witness.distinct.size == proof.witness.size
        )
    }
  }

  test("large batch verification") { implicit res =>
    implicit val (hs, js) = res
    val numEntries = 200

    hs.withCurrent { implicit hasher =>
      for {
        entries <- (1 to numEntries).toList.traverse { i =>
          hasher.hash(s"entry_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        batchProver = MerklePatriciaBatchInclusionProver.make[IO](trie)

        randomIndices = scala.util.Random.shuffle((0 until numEntries).toList).take(100)
        paths = randomIndices.map(idx => entries(idx)._1)

        proof <- batchProver.attestPaths(paths).flatMap(IO.fromEither)

        verifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
        result <- verifier.confirm(proof)
      } yield
        expect.all(
          result.isRight,
          proof.paths.size == 100
        )
    }
  }
}

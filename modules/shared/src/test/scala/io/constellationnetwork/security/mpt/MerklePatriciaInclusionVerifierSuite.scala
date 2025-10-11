package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver
import io.constellationnetwork.security.mpt.verifier.MerklePatriciaInclusionVerifier
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.{MutableIOSuite, SimpleIOSuite}

object MerklePatriciaInclusionVerifierSuite extends MutableIOSuite {

  type Res = HasherSelector[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit kryo =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        HasherSelector.forSync[IO](
          Hasher.forJson[IO],
          Hasher.forKryo[IO],
          hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
        )
      }
    }

  test("valid proof verification succeeds") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaSingleInclusionProver.make[IO](trie)

        targetPath = entries.head._1
        proof <- prover.attestPath(targetPath).flatMap(IO.fromEither)

        verifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootNode.digest)
        result  <- verifier.confirm(proof)
      } yield expect(result.isRight)
    }
  }

  test("invalid root hash fails verification") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaSingleInclusionProver.make[IO](trie)

        targetPath = entries.head._1
        proof <- prover.attestPath(targetPath).flatMap(IO.fromEither)

        wrongRoot  <- hasher.hash("wrong_root")
        verifier    = MerklePatriciaInclusionVerifier.make[IO](wrongRoot)
        result     <- verifier.confirm(proof)
      } yield expect(result.isLeft)
    }
  }

  test("tampered witness fails verification") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaSingleInclusionProver.make[IO](trie)

        targetPath = entries.head._1
        proof <- prover.attestPath(targetPath).flatMap(IO.fromEither)

        tamperedProof = proof.copy(witness = proof.witness.drop(1))

        verifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootNode.digest)
        result  <- verifier.confirm(tamperedProof)
      } yield expect(result.isLeft)
    }
  }

  test("tampered path fails verification") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaSingleInclusionProver.make[IO](trie)

        targetPath = entries.head._1
        proof <- prover.attestPath(targetPath).flatMap(IO.fromEither)

        wrongPath     = entries(1)._1
        tamperedProof = proof.copy(path = wrongPath)

        verifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootNode.digest)
        result  <- verifier.confirm(tamperedProof)
      } yield expect(result.isLeft)
    }
  }

  test("large trie verification") { implicit res =>
    val numEntries = 1000

    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to numEntries).toList.traverse { i =>
          hasher.hash(s"entry_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaSingleInclusionProver.make[IO](trie)

        randomIndices = scala.util.Random.shuffle((0 until numEntries).toList).take(50)

        proofs <- randomIndices.traverse { idx =>
          val (hex, _) = entries(idx)
          prover.attestPath(hex).flatMap(IO.fromEither)
        }

        verifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootNode.digest)
        results <- proofs.traverse(proof => verifier.confirm(proof))
      } yield expect.all(
        results.forall(_.isRight)
      )
    }
  }

  test("all witness types verify correctly") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 50).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaSingleInclusionProver.make[IO](trie)

        proofs <- entries.map(_._1).traverse { path =>
          prover.attestPath(path).flatMap(IO.fromEither)
        }

        verifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootNode.digest)
        results <- proofs.traverse(proof => verifier.confirm(proof))
      } yield expect.all(
        results.forall(_.isRight),
        proofs.exists(p => p.witness.exists(_.isInstanceOf[MerklePatriciaCommitment.Leaf])),
        proofs.exists(p => p.witness.exists(_.isInstanceOf[MerklePatriciaCommitment.Branch]))
      )
    }
  }
}
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
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object MerklePatriciaInclusionProverSuite extends MutableIOSuite with Checkers {

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

  test("prover can produce an inclusion proof for a path in the trie") { implicit res =>
    forall(Gen.listOfN(32, Gen.long).flatMap { list =>
      Gen.choose(0, list.size - 1).map(index => (list, index))
    }) {
      case (list, randomIndex) =>
        res.withCurrent { implicit hasher =>
          for {
            leafPairs <- list.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el))
            trie <- MerklePatriciaTrie.make(leafPairs.toMap)
            prover = MerklePatriciaSingleInclusionProver.make[IO](trie)
            proof <- prover.attestPath(leafPairs(randomIndex)._1).flatMap(IO.fromEither)
          } yield expect(proof.witness.nonEmpty)
        }
    }
  }

  test("prover fails to produce an inclusion proof for a path not in the trie") { implicit res =>
    forall(Gen.listOfN(32, Gen.long)) { list =>
      res.withCurrent { implicit hasher =>
        for {
          leafMap <- list.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el)).map(_.toMap)
          trie <- MerklePatriciaTrie.make(leafMap)
          prover = MerklePatriciaSingleInclusionProver.make[IO](trie)
          proofEither <- prover.attestPath(Hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"))
        } yield expect(proofEither.isLeft)
      }
    }
  }

  test("prover produces valid proof for single-leaf trie") { implicit res =>
    res.withCurrent { implicit hasher =>
      val value = "single-leaf"
      for {
        hash <- hasher.hash(value)
        trie <- MerklePatriciaTrie.make[IO, String](Map(Hex(hash.value) -> value))
        prover = MerklePatriciaSingleInclusionProver.make[IO](trie)
        proof <- prover.attestPath(Hex(hash.value)).flatMap(IO.fromEither)
      } yield
        expect.all(
          proof.path == Hex(hash.value),
          proof.witness.size == 1
        )
    }
  }

  test("prover handles large tries efficiently") { implicit res =>
    val numEntries = 1000

    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to numEntries).toList.traverse { i =>
          hasher.hash(s"entry_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaSingleInclusionProver.make[IO](trie)

        randomIndices = scala.util.Random.shuffle((0 until numEntries).toList).take(50)

        proofs <- randomIndices.traverse { idx =>
          val (hex, _) = entries(idx)
          prover.attestPath(hex)
        }
      } yield
        expect.all(
          proofs.forall(_.isRight),
          proofs.collect { case Right(p) => p }.forall(_.witness.nonEmpty)
        )
    }
  }

  test("prover produces consistent proofs for the same path") { implicit res =>
    forall(Gen.listOfN(10, Gen.long).flatMap { list =>
      Gen.choose(0, list.size - 1).map(index => (list, index))
    }) {
      case (list, idx) =>
        res.withCurrent { implicit hasher =>
          for {
            leafPairs <- list.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el))
            trie <- MerklePatriciaTrie.make(leafPairs.toMap)
            prover = MerklePatriciaSingleInclusionProver.make[IO](trie)
            proof1 <- prover.attestPath(leafPairs(idx)._1).flatMap(IO.fromEither)
            proof2 <- prover.attestPath(leafPairs(idx)._1).flatMap(IO.fromEither)
          } yield
            expect.all(
              proof1 == proof2,
              proof1.witness == proof2.witness
            )
        }
    }
  }
}

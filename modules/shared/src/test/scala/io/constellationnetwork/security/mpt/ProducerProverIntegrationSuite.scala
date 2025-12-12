package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.producer.{InMemoryMerklePatriciaProducer, StatelessMerklePatriciaProducer}
import io.constellationnetwork.security.mpt.prover.{
  MerklePatriciaBatchInclusionProver,
  MerklePatriciaPrefixProver,
  MerklePatriciaRangeProver
}
import io.constellationnetwork.security.mpt.verifier.{
  MerklePatriciaBatchInclusionVerifier,
  MerklePatriciaInclusionVerifier,
  MerklePatriciaRangeVerifier
}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object ProducerProverIntegrationSuite extends MutableIOSuite {

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

  test("create -> prove -> verify flow") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 20).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        trie <- producer.create(entries.toMap)

        prover <- producer.getProver(trie)
        targetPath = entries.head._1
        proof <- prover.attestPath(targetPath).flatMap(IO.fromEither)

        verifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootNode.digest)
        result <- verifier.confirm(proof)
      } yield expect(result.isRight)
    }
  }

  test("insert -> batch prove -> verify flow") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        initialEntries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"initial_$i").map(hash => Hex(hash.value) -> s"initial_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        initialTrie <- producer.create(initialEntries.toMap)

        newEntries <- (11 to 20).toList.traverse { i =>
          hasher.hash(s"new_$i").map(hash => Hex(hash.value) -> s"new_$i")
        }

        updatedTrie <- producer.insert(initialTrie, newEntries.toMap).flatMap(IO.fromEither)

        batchProver = MerklePatriciaBatchInclusionProver.make[IO](updatedTrie)
        paths = (initialEntries ++ newEntries).take(10).map(_._1)
        proof <- batchProver.attestPaths(paths).flatMap(IO.fromEither)

        verifier = MerklePatriciaBatchInclusionVerifier.make[IO](updatedTrie.rootNode.digest)
        result <- verifier.confirm(proof)
      } yield expect(result.isRight)
    }
  }

  test("prefix query integration") { implicit res =>
    res.withCurrent { implicit hasher =>
      val commonPrefix = "abcd"

      for {
        prefixedEntries <- (1 to 10).toList.traverse { i =>
          val suffix = f"$i%02d" + "0" * 58
          val key = Hex(commonPrefix + suffix)
          hasher.hash(s"prefixed_$i").map(_ => key -> s"prefixed_$i")
        }
        otherEntries <- (1 to 5).toList.traverse { i =>
          hasher.hash(s"other_$i").map(hash => Hex(hash.value) -> s"other_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        trie <- producer.create((prefixedEntries ++ otherEntries).toMap)

        prefixProver = MerklePatriciaPrefixProver.make[IO](trie)
        proof <- prefixProver.attestPrefix(Hex(commonPrefix)).flatMap(IO.fromEither)

        batchVerifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
        result <- batchVerifier.confirm(proof)
      } yield
        expect.all(
          result.isRight,
          proof.paths.size == 10
        )
    }
  }

  test("range query integration") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "3000"
      val end = "7000"

      for {
        entries <- List("1000", "2000", "4000", "5000", "6000", "8000", "9000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        trie <- producer.create(entries.toMap)

        rangeProver = MerklePatriciaRangeProver.make[IO](trie)
        proof <- rangeProver.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)

        verifier = MerklePatriciaRangeVerifier.make[IO](trie.rootNode.digest)
        result <- verifier.confirmRange(proof)
      } yield
        expect.all(
          result.isRight,
          proof.inclusionProofs.size == 3
        )
    }
  }

  test("stateful producer with all prover types") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        entries <- (1 to 50).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        _ <- entries.traverse { case (key, value) => producer.insert(Map(key -> value)) }
        trie <- producer.build.flatMap(IO.fromEither)

        inclusionProver <- producer.getProver
        targetPath = entries.head._1
        inclusionProof <- inclusionProver.attestPath(targetPath).flatMap(IO.fromEither)

        batchProver = MerklePatriciaBatchInclusionProver.make[IO](trie)
        batchPaths = entries.take(10).map(_._1)
        batchProof <- batchProver.attestPaths(batchPaths).flatMap(IO.fromEither)

        inclusionVerifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootNode.digest)
        inclusionResult <- inclusionVerifier.confirm(inclusionProof)

        batchVerifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
        batchResult <- batchVerifier.confirm(batchProof)
      } yield
        expect.all(
          inclusionResult.isRight,
          batchResult.isRight
        )
    }
  }
}

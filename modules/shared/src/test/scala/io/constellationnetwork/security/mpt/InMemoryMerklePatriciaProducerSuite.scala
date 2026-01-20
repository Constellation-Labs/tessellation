package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.GlobalStateProofPatternsSuite.F
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object InMemoryMerklePatriciaProducerSuite extends MutableIOSuite {

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

  test("stateful insert operations change state") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        key1 <- hasher.hash("key1").map(hash => Hex(hash.value))
        _ <- producer.insert(Map(key1 -> "value1"))
        entries <- producer.entries

        key2 <- hasher.hash("key2").map(hash => Hex(hash.value))
        _ <- producer.insert(Map(key2 -> "value2"))
        updatedEntries <- producer.entries
      } yield
        expect.all(
          entries.size == 1,
          updatedEntries.size == 2
        )
    }
  }

  test("build trie from accumulated entries") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        _ <- entries.traverse { case (key, value) => producer.insert(Map(key -> value)) }

        trieEither <- producer.build
        trie <- IO.fromEither(trieEither)
        trieRoot <- MerklePatriciaTrie.getRootHash[F](trie)
      } yield expect(trieRoot.value.getBytes.nonEmpty)
    }
  }

  test("update existing key replaces old value") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        key <- hasher.hash("key").map(hash => Hex(hash.value))
        _ <- producer.insert(Map(key -> "initial_value"))
        initialTrie <- producer.build.flatMap(IO.fromEither)

        _ <- producer.update(key, "updated_value")
        updatedTrie <- producer.build.flatMap(IO.fromEither)
        initialTrieRoot <- MerklePatriciaTrie.getRootHash[F](initialTrie)
        updatedTrieRoot <- MerklePatriciaTrie.getRootHash[F](updatedTrie)
      } yield expect(initialTrieRoot.value != updatedTrieRoot.value)
    }
  }

  test("clear state resets to empty") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        entries <- (1 to 5).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        _ <- entries.traverse { case (key, value) => producer.insert(Map(key -> value)) }
        entriesBeforeClear <- producer.entries

        _ <- producer.clear
        entriesAfterClear <- producer.entries
      } yield
        expect.all(
          entriesBeforeClear.size == 5,
          entriesAfterClear.isEmpty
        )
    }
  }

  test("get prover before build triggers build") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        _ <- entries.traverse { case (key, value) => producer.insert(Map(key -> value)) }

        prover <- producer.getProver

        targetPath = entries.head._1
        proof <- prover.attestPath(targetPath)
      } yield expect(proof.isRight)
    }
  }

  test("get prover after build uses cached trie") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        _ <- entries.traverse { case (key, value) => producer.insert(Map(key -> value)) }
        trie1 <- producer.build.flatMap(IO.fromEither)
        trie2 <- producer.build.flatMap(IO.fromEither)
        trie1Root <- MerklePatriciaTrie.getRootHash[F](trie1)
        trie2Root <- MerklePatriciaTrie.getRootHash[F](trie2)
      } yield expect(trie1Root.value == trie2Root.value)
    }
  }

  test("remove operations update state correctly") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        _ <- entries.traverse { case (key, value) => producer.insert(Map(key -> value)) }
        entriesBeforeRemove <- producer.entries

        keysToRemove = entries.take(3).map(_._1)
        _ <- producer.remove(keysToRemove)
        entriesAfterRemove <- producer.entries
      } yield
        expect.all(
          entriesBeforeRemove.size == 10,
          entriesAfterRemove.size == 7
        )
    }
  }

  test("multiple updates in sequence maintain consistency") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        producer <- InMemoryMerklePatriciaProducer.make[IO]()

        key1 <- hasher.hash("key1").map(hash => Hex(hash.value))
        key2 <- hasher.hash("key2").map(hash => Hex(hash.value))
        key3 <- hasher.hash("key3").map(hash => Hex(hash.value))

        _ <- producer.insert(Map(key1 -> "value1"))
        trie1 <- producer.build.flatMap(IO.fromEither)

        _ <- producer.insert(Map(key2 -> "value2"))
        trie2 <- producer.build.flatMap(IO.fromEither)

        _ <- producer.update(key1, "updated_value1")
        trie3 <- producer.build.flatMap(IO.fromEither)

        _ <- producer.remove(List(key2))
        trie4 <- producer.build.flatMap(IO.fromEither)

        finalEntries <- producer.entries
        trie1Root <- MerklePatriciaTrie.getRootHash[F](trie1)
        trie2Root <- MerklePatriciaTrie.getRootHash[F](trie2)
        trie3Root <- MerklePatriciaTrie.getRootHash[F](trie3)
        trie4Root <- MerklePatriciaTrie.getRootHash[F](trie4)
      } yield
        expect.all(
          trie1Root.value != trie2Root.value,
          trie2Root.value != trie3Root.value,
          trie3Root.value != trie4Root.value,
          finalEntries.size == 1
        )
    }
  }
}

package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.generators.addressGen
import io.constellationnetwork.schema.mpt.PartitionNamespace._
import io.constellationnetwork.schema.mpt.{GlobalStateFieldId, GlobalStateKey}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.prover._
import io.constellationnetwork.security.mpt.verifier.{
  MerklePatriciaBatchInclusionVerifier,
  MerklePatriciaInclusionVerifier,
  MerklePatriciaRangeVerifier
}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.syntax._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalStateProofPatternsSuite extends MutableIOSuite with Checkers {

  type Res = HasherSelector[IO]

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(kryo: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].toResource
    } yield
      HasherSelector.forSync[IO](
        Hasher.forJson[IO],
        Hasher.forKryo[IO],
        hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
      )

  test("Pattern 1: Single Balance Check") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
        val key = GlobalStateKey(
          HypergraphNamespace,
          GlobalStateFieldId.Balances,
          EmptyNamespace,
          AddressNamespace(address)
        )

        for {
          path <- GlobalStateKey.toHex[IO](key)

          keyValuePair = path -> Balance(NonNegLong.unsafeFrom(5000L)).asJson
          trie <- MerklePatriciaTrie.make(Map(keyValuePair))

          prover = MerklePatriciaSingleInclusionProver.make[IO](trie)
          proof <- prover.attestPath(path).flatMap(IO.fromEither)

          verifier = MerklePatriciaInclusionVerifier.make[IO](trie.rootNode.digest)
          result <- verifier.confirm(proof)
        } yield
          expect.all(
            result.isRight,
            proof.path == path
          )
      }
    }
  }

  test("Pattern 2: Multiple Balance Check") { implicit res =>
    forall(Gen.listOfN(5, addressGen)) { addresses =>
      res.withCurrent { implicit hasher =>
        val keys = addresses.map(addr =>
          GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.Balances,
            EmptyNamespace,
            AddressNamespace(addr)
          )
        )

        for {
          paths <- keys.traverse(GlobalStateKey.toHex[IO])

          keyValuePairs <- paths.zipWithIndex.traverse {
            case (path, idx) => Balance(NonNegLong.unsafeFrom((idx + 1) * 1000L)).asJson.pure[IO].map(path -> _)
          }
          trie <- MerklePatriciaTrie.make(keyValuePairs.toMap)

          prover = MerklePatriciaBatchInclusionProver.make[IO](trie)
          proof <- prover.attestPaths(paths).flatMap(IO.fromEither)

          verifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
          result <- verifier.confirm(proof)
        } yield
          expect.all(
            result.isRight,
            proof.paths.size == 5,
            proof.paths.toSet == paths.toSet
          )
      }
    }
  }

  test("Pattern 3: All Balances for Metagraph (prefix proof)") { implicit res =>
    forall(Gen.zip(addressGen, addressGen, Gen.listOfN(10, addressGen))) {
      case (metagraphId, otherMetagraphId, addresses) =>
        res.withCurrent { implicit hasher =>
          val metagraphKey = GlobalStateKey(
            MetagraphNamespace(metagraphId),
            GlobalStateFieldId.LastCurrencySnapshots,
            EmptyNamespace,
            EmptyNamespace
          )
          val prefixKey = GlobalStateKey(
            MetagraphNamespace(metagraphId),
            GlobalStateFieldId.LastCurrencySnapshots,
            EmptyNamespace,
            EmptyNamespace
          )

          val otherMetagraphKey = GlobalStateKey(
            MetagraphNamespace(otherMetagraphId),
            GlobalStateFieldId.LastCurrencySnapshots,
            EmptyNamespace,
            EmptyNamespace
          )

          for {
            metagraphPath <- GlobalStateKey.toHex[IO](metagraphKey)
            otherPath <- GlobalStateKey.toHex[IO](otherMetagraphKey)

            metagraphPair = metagraphPath -> Balance(NonNegLong.unsafeFrom(1000L)).asJson
            otherPair = otherPath -> Balance(NonNegLong.unsafeFrom(500L)).asJson

            trie <- MerklePatriciaTrie.make(Map(metagraphPair, otherPair))

            prefix <- GlobalStateKey.toHex[IO](prefixKey)

            prover = MerklePatriciaPrefixProver.make[IO](trie)
            proof <- prover.attestPrefix(prefix).flatMap(IO.fromEither)

            verifier = MerklePatriciaBatchInclusionVerifier.make[IO](trie.rootNode.digest)
            result <- verifier.confirm(proof)
          } yield
            expect.all(
              result.isRight,
              proof.paths.forall(_.value.startsWith(prefix.value))
            )
        }
    }
  }

  test("Pattern 4: Paginated Balance Query") { implicit res =>
    forall(Gen.listOfN(20, addressGen)) { addresses =>
      res.withCurrent { implicit hasher =>
        for {
          keys <- addresses.traverse { addr =>
            GlobalStateKey.toHex[IO](
              GlobalStateKey(
                HypergraphNamespace,
                GlobalStateFieldId.Balances,
                EmptyNamespace,
                AddressNamespace(addr)
              )
            )
          }
          keyValuePairs <- keys.zipWithIndex.traverse {
            case (key, idx) => Balance(NonNegLong.unsafeFrom((idx + 1) * 1000L)).asJson.pure[IO].map(key -> _)
          }

          trie <- MerklePatriciaTrie.make(keyValuePairs.toMap)
          prover = MerklePatriciaRangeProver.make[IO](trie)

          sortedKeys = keys.sorted(Ordering.by[Hex, String](_.value))
          startPath = sortedKeys(5)
          endPath = sortedKeys(14)

          proof <- prover.attestRange(startPath, endPath).flatMap(IO.fromEither)

          verifier = MerklePatriciaRangeVerifier.make[IO](trie.rootNode.digest)
          result <- verifier.confirmRange(proof)
        } yield
          expect.all(
            result.isRight,
            proof.inclusionProofs.size == 10,
            proof.inclusionProofs.map(_.path).forall(p => p.value >= startPath.value && p.value <= endPath.value)
          )
      }
    }
  }
}

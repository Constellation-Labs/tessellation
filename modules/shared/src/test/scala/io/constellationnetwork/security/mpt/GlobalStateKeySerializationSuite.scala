package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.generators.addressGen
import io.constellationnetwork.schema.mpt.PartitionNamespace._
import io.constellationnetwork.schema.mpt.{GlobalStateFieldId, GlobalStateKey}
import io.constellationnetwork.security._
import io.constellationnetwork.shared.sharedKryoRegistrar

import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalStateKeySerializationSuite extends MutableIOSuite with Checkers {

  type Res = HasherSelector[IO]

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(kryo: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].toResource
    } yield
      HasherSelector.forSync[IO](
        Hasher.forJson[IO],
        Hasher.forKryo[IO],
        hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
      )

  test("toHex produces valid hex string for hypergraph key with user address") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
        val key = GlobalStateKey(
          HypergraphNamespace,
          GlobalStateFieldId.Balances,
          EmptyNamespace,
          AddressNamespace(address)
        )

        GlobalStateKey.toHex[IO](key).map { hex =>
          expect.all(
            hex.value.length == 78,
            hex.value.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
          )
        }
      }
    }
  }

  test("toHex produces valid hex string for metagraph key") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (metagraphId, address) =>
        res.withCurrent { implicit hasher =>
          val key = GlobalStateKey(
            MetagraphNamespace(metagraphId),
            GlobalStateFieldId.Balances,
            EmptyNamespace,
            AddressNamespace(address)
          )

          GlobalStateKey.toHex[IO](key).map { hex =>
            expect.all(
              hex.value.length == 142,
              hex.value.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
            )
          }
        }
    }
  }

  test("toHex is deterministic for same input") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
        val key = GlobalStateKey(
          HypergraphNamespace,
          GlobalStateFieldId.Balances,
          EmptyNamespace,
          AddressNamespace(address)
        )

        for {
          hex1 <- GlobalStateKey.toHex[IO](key)
          hex2 <- GlobalStateKey.toHex[IO](key)
        } yield expect(hex1 == hex2)
      }
    }
  }

  test("toHex produces different outputs for different addresses") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (address1, address2) =>
        res.withCurrent { implicit hasher =>
          val key1 = GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.Balances,
            EmptyNamespace,
            AddressNamespace(address1)
          )
          val key2 = GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.Balances,
            EmptyNamespace,
            AddressNamespace(address2)
          )

          for {
            hex1 <- GlobalStateKey.toHex[IO](key1)
            hex2 <- GlobalStateKey.toHex[IO](key2)
          } yield
            if (address1 == address2) expect(hex1 == hex2)
            else expect(hex1 != hex2)
        }
    }
  }

  test("prefix key produces shorter output than full key") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (metagraphId, address) =>
        res.withCurrent { implicit hasher =>
          val prefixKey = GlobalStateKey(
            MetagraphNamespace(metagraphId),
            GlobalStateFieldId.LastCurrencySnapshots,
            EmptyNamespace,
            EmptyNamespace
          )
          val fullKey = GlobalStateKey(
            MetagraphNamespace(metagraphId),
            GlobalStateFieldId.Balances,
            EmptyNamespace,
            AddressNamespace(address)
          )

          for {
            prefixHex <- GlobalStateKey.toHex[IO](prefixKey)
            fullHex <- GlobalStateKey.toHex[IO](fullKey)
          } yield
            expect.all(
              prefixHex.value.length == 78,
              fullHex.value.length == 142
            )
        }
    }
  }

  test("keys with same network namespace share prefix") { implicit res =>
    forall(Gen.zip(addressGen, Gen.listOfN(3, addressGen))) {
      case (metagraphId, addresses) =>
        res.withCurrent { implicit hasher =>
          val keys = addresses.map(addr =>
            GlobalStateKey(
              MetagraphNamespace(metagraphId),
              GlobalStateFieldId.Balances,
              EmptyNamespace,
              AddressNamespace(addr)
            )
          )

          for {
            hexes <- keys.traverse(GlobalStateKey.toHex[IO])
          } yield {
            val networkPrefix = hexes.head.value.take(66)
            expect.all(
              hexes.forall(_.value.take(66) == networkPrefix)
            )
          }
        }
    }
  }

  test("different field IDs produce different hex outputs") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
        val balanceKey = GlobalStateKey(
          HypergraphNamespace,
          GlobalStateFieldId.Balances,
          EmptyNamespace,
          AddressNamespace(address)
        )
        val txRefKey = GlobalStateKey(
          HypergraphNamespace,
          GlobalStateFieldId.LastTxRefs,
          EmptyNamespace,
          AddressNamespace(address)
        )

        for {
          balanceHex <- GlobalStateKey.toHex[IO](balanceKey)
          txRefHex <- GlobalStateKey.toHex[IO](txRefKey)
        } yield expect(balanceHex != txRefHex)
      }
    }
  }

  test("hypergraph key starts with 00") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
        val key = GlobalStateKey(
          HypergraphNamespace,
          GlobalStateFieldId.Balances,
          EmptyNamespace,
          AddressNamespace(address)
        )

        GlobalStateKey.toHex[IO](key).map { hex =>
          expect.all(
            hex.value.take(2) == "00"
          )
        }
      }
    }
  }

  test("metagraph key starts with 01") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (metagraphId, address) =>
        res.withCurrent { implicit hasher =>
          val key = GlobalStateKey(
            MetagraphNamespace(metagraphId),
            GlobalStateFieldId.Balances,
            EmptyNamespace,
            AddressNamespace(address)
          )

          GlobalStateKey.toHex[IO](key).map { hex =>
            expect.all(
              hex.value.take(2) == "01"
            )
          }
        }
    }
  }

  test("hierarchical prefix matching works for nested keys with same contract namespace") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (contractAddr, userAddr) =>
        res.withCurrent { implicit hasher =>
          val contractOnlyKey = GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.TokenLockBalances,
            AddressNamespace(contractAddr),
            EmptyNamespace
          )
          val fullKey = GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.TokenLockBalances,
            AddressNamespace(contractAddr),
            AddressNamespace(userAddr)
          )

          for {
            contractOnlyHex <- GlobalStateKey.toHex[IO](contractOnlyKey)
            fullHex <- GlobalStateKey.toHex[IO](fullKey)
          } yield
            expect.all(
              contractOnlyHex.value.length == 78,
              fullHex.value.length == 142,
              fullHex.value.startsWith(contractOnlyHex.value.take(74))
            )
        }
    }
  }

  test("keys with different namespace types do not share prefixes beyond field") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (contractAddr, userAddr) =>
        res.withCurrent { implicit hasher =>
          val emptyContractKey = GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.TokenLockBalances,
            EmptyNamespace,
            EmptyNamespace
          )
          val addressContractKey = GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.TokenLockBalances,
            AddressNamespace(contractAddr),
            EmptyNamespace
          )

          for {
            emptyHex <- GlobalStateKey.toHex[IO](emptyContractKey)
            addressHex <- GlobalStateKey.toHex[IO](addressContractKey)
          } yield
            expect.all(
              emptyHex.value.length == 14,
              addressHex.value.length == 78,
              emptyHex.value.take(10) == addressHex.value.take(10),
              emptyHex.value.drop(10) != addressHex.value.drop(10).take(4)
            )
        }
    }
  }

  test("keys with same hypergraph and field share prefix") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (addr1, addr2) =>
        res.withCurrent { implicit hasher =>
          val key1 = GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.Balances,
            EmptyNamespace,
            AddressNamespace(addr1)
          )
          val key2 = GlobalStateKey(
            HypergraphNamespace,
            GlobalStateFieldId.Balances,
            EmptyNamespace,
            AddressNamespace(addr2)
          )

          for {
            hex1 <- GlobalStateKey.toHex[IO](key1)
            hex2 <- GlobalStateKey.toHex[IO](key2)
          } yield {
            val sharedPrefix = hex1.value.substring(0, 10)
            expect(hex2.value.startsWith(sharedPrefix))
          }
        }
    }
  }
}

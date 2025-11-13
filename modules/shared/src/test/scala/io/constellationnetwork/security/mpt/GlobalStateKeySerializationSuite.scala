package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.generators.addressGen
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
      implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].toResource
    } yield
      HasherSelector.forSync[IO](
        Hasher.forJson[IO],
        Hasher.forKryo[IO],
        hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
      )

  test("toHex produces valid hex string of 130 characters for full key without secondary") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (metagraphId, address) =>
        res.withCurrent { implicit hasher =>
          val key = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(address), None)

          GlobalStateKey.toHex[IO](key).map { hex =>
            expect.all(
              hex.value.length == 130,
              hex.value.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
            )
          }
        }
    }
  }

  test("toHex is deterministic for same input") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (metagraphId, address) =>
        res.withCurrent { implicit hasher =>
          val key = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(address), None)

          for {
            hex1 <- GlobalStateKey.toHex[IO](key)
            hex2 <- GlobalStateKey.toHex[IO](key)
          } yield expect(hex1 == hex2)
        }
    }
  }

  test("toHex produces different outputs for different addresses") { implicit res =>
    forall(Gen.zip(addressGen, addressGen, addressGen)) {
      case (metagraphId, address1, address2) =>
        res.withCurrent { implicit hasher =>
          val key1 = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(address1), None)
          val key2 = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(address2), None)

          for {
            hex1 <- GlobalStateKey.toHex[IO](key1)
            hex2 <- GlobalStateKey.toHex[IO](key2)
          } yield
            if (address1 == address2) expect(hex1 == hex2)
            else expect(hex1 != hex2)
        }
    }
  }

  test("partial key produces shorter output than full key") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (metagraphId, address) =>
        res.withCurrent { implicit hasher =>
          val prefixKey = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), None, None)
          val fullKey = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(address), None)

          for {
            prefixHex <- GlobalStateKey.toHex[IO](prefixKey)
            fullHex <- GlobalStateKey.toHex[IO](fullKey)
          } yield
            expect.all(
              prefixHex.value.length == 66,
              fullHex.value.length == 130,
              fullHex.value.startsWith(prefixHex.value)
            )
        }
    }
  }

  test("prefix key matches all full keys with same prefix") { implicit res =>
    forall(Gen.zip(addressGen, Gen.listOfN(5, addressGen))) {
      case (metagraphId, addresses) =>
        res.withCurrent { implicit hasher =>
          val prefixKey = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), None, None)
          val fullKeys = addresses.map(addr => GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(addr), None))

          for {
            prefix <- GlobalStateKey.toHex[IO](prefixKey)
            fullHexes <- fullKeys.traverse(GlobalStateKey.toHex[IO])
          } yield
            expect.all(
              fullHexes.forall(_.value.startsWith(prefix.value))
            )
        }
    }
  }

  test("different field IDs produce different hex outputs") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (metagraphId, address) =>
        res.withCurrent { implicit hasher =>
          val balanceKey = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(address), None)
          val txRefKey = GlobalStateKey(GlobalStateFieldId.LastTxRefs, Some(metagraphId), Some(address), None)

          for {
            balanceHex <- GlobalStateKey.toHex[IO](balanceKey)
            txRefHex <- GlobalStateKey.toHex[IO](txRefKey)
          } yield expect(balanceHex != txRefHex)
        }
    }
  }

  test("None metagraphId omits metagraph from output") { implicit res =>
    forall(addressGen) { address =>
      res.withCurrent { implicit hasher =>
        val key = GlobalStateKey(GlobalStateFieldId.Balances, None, Some(address), None)

        GlobalStateKey.toHex[IO](key).map { hex =>
          expect.all(
            hex.value.length == 66,
            hex.value.take(2) == f"${GlobalStateFieldId.Balances.toByte}%02x"
          )
        }
      }
    }
  }

  test("hierarchical prefix matching works for nested keys") { implicit res =>
    forall(Gen.zip(addressGen, addressGen, addressGen)) {
      case (metagraphId, primaryAddr, secondaryAddr) =>
        res.withCurrent { implicit hasher =>
          val level1Key = GlobalStateKey(GlobalStateFieldId.ActiveTokenLocks, Some(metagraphId), None, None)
          val level2Key =
            GlobalStateKey(GlobalStateFieldId.ActiveTokenLocks, Some(metagraphId), Some(primaryAddr), None)
          val fullKey =
            GlobalStateKey(GlobalStateFieldId.ActiveTokenLocks, Some(metagraphId), Some(primaryAddr), Some(secondaryAddr))

          for {
            level1Prefix <- GlobalStateKey.toHex[IO](level1Key)
            level2Prefix <- GlobalStateKey.toHex[IO](level2Key)
            fullHex <- GlobalStateKey.toHex[IO](fullKey)
          } yield
            expect.all(
              fullHex.value.startsWith(level1Prefix.value),
              fullHex.value.startsWith(level2Prefix.value),
              level2Prefix.value.startsWith(level1Prefix.value),
              level1Prefix.value.length == 66,
              level2Prefix.value.length == 130,
              fullHex.value.length == 194
            )
        }
    }
  }

  test("field ID is correctly encoded at position 0-1") { implicit res =>
    forall(Gen.zip(addressGen, addressGen)) {
      case (metagraphId, address) =>
        res.withCurrent { implicit hasher =>
          val key = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(address), None)

          GlobalStateKey.toHex[IO](key).map { hex =>
            val fieldIdHex = hex.value.substring(0, 2)
            expect(fieldIdHex == f"${GlobalStateFieldId.Balances.toByte}%02x")
          }
        }
    }
  }

  test("keys with same metagraph and field share prefix") { implicit res =>
    forall(Gen.zip(addressGen, addressGen, addressGen)) {
      case (metagraphId, addr1, addr2) =>
        res.withCurrent { implicit hasher =>
          val key1 = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(addr1), None)
          val key2 = GlobalStateKey(GlobalStateFieldId.Balances, Some(metagraphId), Some(addr2), None)

          for {
            hex1 <- GlobalStateKey.toHex[IO](key1)
            hex2 <- GlobalStateKey.toHex[IO](key2)
          } yield {
            val sharedPrefix = hex1.value.substring(0, 66)
            expect(hex2.value.startsWith(sharedPrefix))
          }
        }
    }
  }
}

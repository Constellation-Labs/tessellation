package org.tessellation.node.shared.infrastructure.genesis

import cats.effect.{IO, Resource}

import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.generators.{addressGen, balanceGen}
import org.tessellation.security._
import org.tessellation.security.signature.Signed

import fs2.io.file.Files
import fs2.text
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck._

object GenesisFSSuite extends MutableIOSuite with Checkers {
  type Res = (KryoSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forSync[IO](new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash })
  } yield (ks, h, sp)

  val gen: Gen[(Map[Address, Balance], Address)] = for {
    balancesMap <- Gen.mapOf(balanceGen.flatMap(balance => addressGen.map((_, balance))))
    identifier <- addressGen
  } yield (balancesMap, identifier)

  test("writes and loads genesis") { res =>
    implicit val (ks, h, sp) = res
    val genesisFS = GenesisFS.make[IO, CurrencySnapshot]

    forall(gen) {
      case (balances, identifier) =>
        Files[F].tempDirectory.use { tempDir =>
          for {
            kp <- KeyPairGenerator.makeKeyPair
            genesis = CurrencySnapshot.mkGenesis(balances, None)
            signedGenesis <- Signed.forAsyncHasher(genesis, kp)
            _ <- genesisFS.write(signedGenesis, identifier, tempDir)
            loaded <- genesisFS.loadSignedGenesis(tempDir / "genesis.snapshot")
          } yield expect.eql(loaded, signedGenesis)
        }

    }
  }

  test("writes and loads metadata") { res =>
    implicit val (ks, h, sp) = res
    val genesisFS = GenesisFS.make[IO, CurrencySnapshot]

    forall(gen) {
      case (balances, identifier) =>
        Files[F].tempDirectory.use { tempDir =>
          for {
            kp <- KeyPairGenerator.makeKeyPair
            genesis = CurrencySnapshot.mkGenesis(balances, None)
            signedGenesis <- Signed.forAsyncHasher(genesis, kp)
            _ <- genesisFS.write(signedGenesis, identifier, tempDir)
            loaded <- Files[F]
              .readAll(tempDir / "genesis.address")
              .through(text.utf8.decode)
              .compile
              .string
          } yield expect.eql(loaded, identifier.value.value)
        }
    }
  }

}

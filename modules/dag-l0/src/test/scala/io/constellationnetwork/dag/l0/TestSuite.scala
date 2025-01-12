package io.constellationnetwork.dag.l0

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.keytool.KeyStoreUtils
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe.syntax._
import weaver.MutableIOSuite

object TestSuite extends MutableIOSuite {
  type Res = (SecurityProvider[IO], Hasher[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    hj = Hasher.forJson[IO]
    sp <- SecurityProvider.forAsync[IO]
  } yield (sp, hj)

  test("") { res =>
    implicit val (sp, hj) = res

    for {
      as <- allowSpend(Address("DAG3FJFFsjzYeSNVeL3cykLqWbijkq25K6Eg23yp"))
      _ = println(as.asJson)
    } yield expect(true === true)
  }

  def allowSpend(src: Address)(implicit s: SecurityProvider[IO], h: Hasher[IO]): IO[Signed[AllowSpend]] = {
    val dst = Address("DAG0CyySf35ftDQDQBnd1bdQ9aPyUdacMghpnCuM")
    val currency = none[CurrencyId]
    val amount = SwapAmount(1L)
    val fee = AllowSpendFee(1L)
    val parent = AllowSpendReference.empty
    val lastValidEpochProgress = EpochProgress(50L)
    val approvers = List.empty[Address]

    val allowSpend = AllowSpend(src, dst, currency, amount, fee, parent, lastValidEpochProgress, approvers)

    KeyStoreUtils
      .readKeyPairFromStore[IO](
        "/Users/marcinwadon/Projects/constellation/tessellation/kubernetes/data/genesis-keys/key-1.p12",
        "alias",
        "password".toCharArray(),
        "password".toCharArray()
      )
      .flatMap { kp =>
        println(kp.getPublic.toAddress)
        Signed.forAsyncHasher(allowSpend, kp)
      }
  }

}

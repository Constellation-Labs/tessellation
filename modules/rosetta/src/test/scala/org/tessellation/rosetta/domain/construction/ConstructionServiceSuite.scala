package org.tessellation.rosetta.domain.construction

import cats.effect.{IO, Resource}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.foldable._

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.transaction.TransactionGenerator
import org.tessellation.ext.kryo.MapRegistrationId
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.domain._
import org.tessellation.rosetta.domain.error.{InvalidPublicKey, MalformedTransaction}
import org.tessellation.schema.address.Address
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.PosInt
import weaver._
import weaver.scalacheck.Checkers

object ConstructionServiceSuite extends MutableIOSuite with Checkers with TransactionGenerator {

  type Res = (SecurityProvider[IO], KryoSerializer[F])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer
      .forAsync[IO](dagSharedKryoRegistrar.union(sharedKryoRegistrar))
      .flatMap(kryo => SecurityProvider.forAsync[IO].map(securityProvider => (securityProvider, kryo)))

  test("derives public key") { res =>
    implicit val (sp, k) = res

    val publicKey = RosettaPublicKey(
      Hex(
        "0483e4f38072fa59975fc796f220f4c07a7a6a3af1ad7fc091cbd6b8ebe78bac6a959da3587e6e761daf93693d4d2dc6b349fbc44dac5a9fcc5f809a59e93818ea"
      ),
      CurveType.SECP256K1
    )

    val expected = AccountIdentifier(Address("DAG8Q4CnZ1fSMn1Hrui9MmPogEp5UoT5MSH1LwHg"), None)

    val cs = ConstructionService.make[IO]()

    cs.derive(publicKey).rethrowT.map {
      expect.eql(expected, _)
    }
  }

  test("returns InvalidPublicKey when cannot derive public key") { res =>
    implicit val (sp, k) = res

    val publicKey = RosettaPublicKey(Hex("foobarbaz"), CurveType.SECP256K1)

    val cs = ConstructionService.make[IO]()

    cs.derive(publicKey).value.map {
      expect.eql(Left(InvalidPublicKey), _)
    }
  }

  test("returns a transaction hash for a valid transaction hex") { res =>
    implicit val (sp, k) = res

    val cs = ConstructionService.make[IO]()

    def txs = (KeyPairGenerator.makeKeyPair[IO], KeyPairGenerator.makeKeyPair[IO]).tupled.flatMap {
      case (srcKey, dstKey) =>
        val srcAddress = srcKey.getPublic.toAddress
        val dstAddress = dstKey.getPublic.toAddress
        val txCount = PosInt(100)

        generateTransactions(srcAddress, srcKey, dstAddress, txCount)
          .flatMap(_.traverse { hashedTransaction =>
            KryoSerializer[F]
              .serialize(hashedTransaction.signed)
              .map(Hex.fromBytes(_))
              .map((_, hashedTransaction.hash))
              .liftTo[IO]
          })
    }

    txs
      .flatMap(_.traverse {
        case (hex, hash) =>
          cs.getTransactionIdentifier(hex)
            .rethrowT
            .map(expect.eql(_, TransactionIdentifier(hash)))
      })
      .map(_.fold)
  }

  test("returns MalformedTransaction for an invalid transaction hex") { res =>
    implicit val (sp, k) = res

    val hex = Hex(
      "0483e4f38072fa59975fc796f220f4c07a7a6a3af1ad7fc091cbd6b8ebe78bac6a959da3587e6e761daf93693d4d2dc6b349fbc44dac5a9fcc5f809a59e93818ea"
    )

    val cs = ConstructionService.make[IO]()

    cs.getTransactionIdentifier(hex)
      .value
      .map(
        expect.eql(Left(MalformedTransaction), _)
      )
  }
}

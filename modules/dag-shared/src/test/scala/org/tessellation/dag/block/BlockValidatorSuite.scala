package org.tessellation.dag.block

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.{Async, IO, Resource}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.validated._

import org.tessellation.dag.block.BlockValidator.BlockValidationError
import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import org.scalacheck.Arbitrary.arbitrary
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockValidatorSuite extends MutableIOSuite with Checkers {
  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, BlockValidatorSuite.Res] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sharedKryoRegistrar)).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  private def makeValidator[G[_]: Async: KryoSerializer: SecurityProvider]: BlockValidator[G] = {
    val signedValidator = SignedValidator.make[G]
    val transactionChainValidator = TransactionChainValidator.make[G]
    val transactionValidator = TransactionValidator.make[G](signedValidator)
    BlockValidator.make[G](signedValidator, transactionChainValidator, transactionValidator)
  }

  private def generateKeys[G[_]: Async: SecurityProvider](count: PosInt): G[NonEmptyList[KeyPair]] =
    for {
      head <- KeyPairGenerator.makeKeyPair[G]
      tail <- KeyPairGenerator.makeKeyPair[G].replicateA(count - 1)
    } yield NonEmptyList.of(head, tail: _*)

  test("validation should pass for valid block") { res =>
    implicit val (kryo, sp) = res

    val validBlockGen = for {
      p1 <- arbitrary[BlockReference]
      p2 <- arbitrary[BlockReference].suchThat(br => br =!= p1)
    } yield DAGBlock(parent = NonEmptyList.of(p1, p2), transactions = Set.empty)

    val validator = makeValidator[IO]

    forall(validBlockGen) { block =>
      for {
        keys <- generateKeys[IO](3)
        signedBlock <- block.sign(keys)
        validated <- validator.validateGetBlock(signedBlock)
      } yield expect.same(validated, signedBlock.validNec[BlockValidationError])
    }
  }

}

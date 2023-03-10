package org.tessellation.rosetta.domain.construction

import cats.data.{EitherT, NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.domain._
import org.tessellation.rosetta.domain.amount.{Amount, AmountValue}
import org.tessellation.rosetta.domain.api.construction.ConstructionParse
import org.tessellation.rosetta.domain.currency.DAG
import org.tessellation.rosetta.domain.error._
import org.tessellation.rosetta.domain.operation._
import org.tessellation.schema.transaction.{DAGTransaction, Transaction}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Not
import eu.timepit.refined.generic.Equal
import eu.timepit.refined.refineV

trait ConstructionService[F[_]] {
  def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier]
  def getAccountIdentifiers(operations: List[Operation]): Option[NonEmptyList[AccountIdentifier]]
  def getTransactionIdentifier(hex: Hex): EitherT[F, ConstructionError, TransactionIdentifier]
  def parseTransaction(hex: Hex, isSigned: Boolean): EitherT[F, ConstructionError, ConstructionParse.ParseResult]
  def combineTransaction(hex: Hex, signature: RosettaSignature): EitherT[F, ConstructionError, Hex]
}

object ConstructionService {
  def make[F[_]: Async: SecurityProvider: KryoSerializer](): ConstructionService[F] = new ConstructionService[F] {
    def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier] =
      publicKey.hexBytes
        .toPublicKeyByEC[F]
        .map(_.toAddress)
        .map(AccountIdentifier(_, None))
        .attemptT
        .leftMap(_ => InvalidPublicKey)

    def getTransactionIdentifier(hex: Hex): EitherT[F, ConstructionError, TransactionIdentifier] = KryoSerializer[F]
      .deserialize[Signed[Transaction]](hex.toBytes)
      .liftTo[F]
      .flatMap(_.toHashed[F])
      .map(_.hash)
      .map(TransactionIdentifier(_))
      .attemptT
      .leftMap(_ => MalformedTransaction)

    def getAccountIdentifiers(operations: List[Operation]): Option[NonEmptyList[AccountIdentifier]] = {
      val accountIdentifiers =
        operations.filter(_.amount.exists(_.value.isNegative)).flatMap(_.account)

      NonEmptyList.fromList(accountIdentifiers)
    }

    def transactionToOperations(transaction: Transaction): Either[ConstructionError, NonEmptyList[Operation]] = {
      type Refinement = Not[Equal[0L]]

      val negativeTransfer = refineV[Refinement](-transaction.amount.value.value).map(
        (transaction.source, _, OperationIndex(0L))
      )
      val positiveTransfer = refineV[Refinement](+transaction.amount.value.value).map(
        (transaction.destination, _, OperationIndex(1L))
      )

      NonEmptyList.of(negativeTransfer, positiveTransfer).traverse {
        case (Right((address, amount, operationIndex))) =>
          Operation(
            OperationIdentifier(operationIndex),
            none,
            OperationType.Transfer,
            none,
            AccountIdentifier(address, none).some,
            Amount(AmountValue(amount), DAG).some
          ).asRight[ConstructionError]
        case _ => Left(MalformedTransaction)
      }
    }

    def parseSignedTransaction(hex: Hex): EitherT[F, ConstructionError, ConstructionParse.ParseResult] = {
      val result = for {
        signedTransaction <- KryoSerializer[F].deserialize[Signed[Transaction]](hex.toBytes).toEitherT
        operations <- transactionToOperations(signedTransaction).toEitherT
        proofs <- signedTransaction.proofs.toNonEmptyList.traverse(_.id.hex.toPublicKey).attemptT
        accountIds = proofs.map(_.toAddress).map(AccountIdentifier(_, none))
      } yield ConstructionParse.ParseResult(operations, accountIds.some)

      result.leftMap(_ => MalformedTransaction)
    }

    def parseUnsignedTransaction(hex: Hex): EitherT[F, ConstructionError, ConstructionParse.ParseResult] =
      for {
        unsignedTransaction <- KryoSerializer[F].deserialize[Transaction](hex.toBytes).toEitherT.leftMap(_ => MalformedTransaction)
        operations <- transactionToOperations(unsignedTransaction).toEitherT
      } yield ConstructionParse.ParseResult(operations, none)

    def parseTransaction(hex: Hex, isSigned: Boolean): EitherT[F, ConstructionError, ConstructionParse.ParseResult] =
      if (isSigned) {
        parseSignedTransaction(hex)
      } else {
        parseUnsignedTransaction(hex)
      }

    def combineTransaction(hex: Hex, signature: RosettaSignature): EitherT[F, ConstructionError, Hex] =
      EitherT
        .fromEither(KryoSerializer[F].deserialize[DAGTransaction](hex.toBytes))
        .leftMap(_ => MalformedTransaction)
        .flatMap { transaction =>
          EitherT {
            signature.publicKey.hexBytes.toPublicKeyByEC
              .map(pk => SignatureProof(pk.toId, Signature(signature.hexBytes)).asRight[ConstructionError])
          }.flatMap { proof =>
            EitherT
              .fromEither(
                KryoSerializer[F]
                  .serialize(Signed[DAGTransaction](transaction, NonEmptySet.of(proof)))
              )
              .leftMap(_ => MalformedTransaction.asInstanceOf[ConstructionError])
              .map(Hex.fromBytes(_))
          }
        }

  }
}

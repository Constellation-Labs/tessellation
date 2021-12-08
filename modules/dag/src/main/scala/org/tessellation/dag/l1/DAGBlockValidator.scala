package org.tessellation.dag.l1

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.syntax.traverse._

import scala.annotation.tailrec

import org.tessellation.dag.l1.storage.{BlockStorage, TransactionStorage}
import org.tessellation.domain.cluster.storage._
import org.tessellation.infrastructure.db.schema.StoredAddress
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.schema.{BlockValidator, nonNegBigIntSemigroup}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto.autoInfer
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import io.estatico.newtype.ops.toCoercibleIdOps

import DAGBlockValidator.takeConsecutiveTransactions

class DAGBlockValidator[F[_]: Async: KryoSerializer: SecurityProvider](
  transactionStorage: TransactionStorage[F],
  blockStorage: BlockStorage[F],
  addressStorage: AddressStorage[F],
  transactionValidator: TransactionValidator[F]
) extends BlockValidator[F, DAGBlock] {

  override def accept(signedBlock: Signed[DAGBlock]): F[Unit] =
    for {
      _ <- validateBlockSignatures(signedBlock).ifM(
        Async[F].unit,
        Async[F].raiseError[Unit](new Throwable("Invalid block signatures!"))
      )
      _ <- validateBlock(signedBlock.value).ifM(
        Async[F].unit,
        Async[F].raiseError[Unit](new Throwable("Invalid block data!"))
      )
      hashedBlock <- signedBlock.hashWithSignatureCheck.flatMap(_.liftTo[F])

      hashedTransactions <- signedBlock.value.transactions.toList
        .traverse(_.hashWithSignatureCheck.flatMap(_.liftTo[F]))

      _ <- acceptTransactions(hashedTransactions)
      _ <- blockStorage.acceptBlock(hashedBlock)
      // TODO: could happen outside of acceptance flow???
      _ <- blockStorage.handleTipsUpdate(hashedBlock)
    } yield ()

  def validateBlockSignatures(signedBlock: Signed[DAGBlock]): F[Boolean] =
    for {
      areSignaturesValid <- signedBlock.hasValidSignature
      areAtLeastThreeSignatures = signedBlock.proofs.map(_.id).toList.toSet.size >= 3 //At least 3 distinct signers
    } yield areSignaturesValid && areAtLeastThreeSignatures

  def validateBlock(block: DAGBlock): F[Boolean] =
    for {
      areParentsAccepted <- areParentsAccepted(block)
      areTxsValid <- areTransactionsValid(block.transactions.toList)
    } yield areParentsAccepted && areTxsValid

  private def areParentsAccepted(block: DAGBlock): F[Boolean] =
    blockStorage.areParentsAccepted(block)

  // TODO: for now I think last transaction stored is enough, I'm not storing all the transactions in transaction
  //       service as it was the case in constellation - all in all, we will store blocks and redownload based on them
  //       and anyway the check for transactions forming a correct chain should catch this case of accepted or stale transactions trying to pass validation
//  def containsAcceptedOrStaleTx(cbd: DAGBlockData): F[List[String]] = {
//    val containsAccepted = cbd.transactions.toList.map { t =>
//
//      transactionStorage.getLastAcceptedTransactionRef(t.baseHash).map {
//        //b => (t.hash, b)
//        case Some(tr) =>
//        case None =>
//      }
//    }.sequence[F, (String, Boolean)]
//      .map(l => l.collect { case (h, true) => h })
//
//    containsAccepted
//  }

  private def areTransactionsValid(signedTransactions: Seq[Signed[Transaction]]): F[Boolean] =
    for {
      arePassingValidation <- areTransactionsPassingValidation(signedTransactions)
      areChainedCorrectly <- areAllTransactionsFormingCorrectChain(signedTransactions)
    } yield arePassingValidation && areChainedCorrectly

  private def areTransactionsPassingValidation(signedTransactions: Seq[Signed[Transaction]]): F[Boolean] =
    transactionValidator
      .validateBatchOfTransactions(signedTransactions)
      .map(_.isValid)
      .handleError(_ => false)

  private def areAllTransactionsFormingCorrectChain(transactions: Seq[Signed[Transaction]]): F[Boolean] =
    transactions
      .groupBy(_.value.source)
      .toList
      .traverse {
        case (address, txs) =>
          for {
            lastAccepted <- transactionStorage.getLastAcceptedTransactionRef(address)
            sorted = txs.sortBy(_.value.parent.ordinal.coerce.value)
            // TODO: do we also need to validate if the first lastRef isn't referencing itself?
            //  I guess it makes no sense given that ordinal and hash of the current tx isnt specified so it's impossible to self-reference
            chainableTransactions = takeConsecutiveTransactions(lastAccepted, txs)
            //  was txs.toSet == chainableTransactions.toSet but that deduplicates, went with sorted lists as these should match
          } yield sorted == chainableTransactions
      }
      .map(_.forall(identity))

  private def prepareBalances(transactions: Seq[Transaction]): F[Seq[UpsertAction[StoredAddress]]] =
    transactions.traverse { tx =>
      for {
        sourceBalance <- addressStorage.getBalance(tx.source)
        newSourceBalance <- refineV[NonNegative]
          .apply(sourceBalance.coerce.value - tx.amount.coerce.value)
          .bimap(new Throwable(_), Balance(_))
          .liftTo[F]
        sourceAction = Insert(StoredAddress(tx.source, newSourceBalance))
        maybeDestinationBalance <- addressStorage.getMaybeBalance(tx.destination)
        destinationAction = maybeDestinationBalance.map(db => Balance(db.coerce |+| tx.amount.coerce)) match {
          case Some(newBalance) =>
            Update(StoredAddress(tx.destination, newBalance))
          case None =>
            Insert(StoredAddress(tx.destination, Balance(tx.amount.coerce)))
        }
      } yield Seq(sourceAction, destinationAction)
    }.map(_.flatten)

  private def acceptTransactions(hashedTransactions: Seq[Hashed[Transaction]]): F[Unit] =
    for {
      preparedBalances <- prepareBalances(hashedTransactions.map(_.signed.value))
      _ <- addressStorage.updateBatchBalances(preparedBalances)
      _ <- hashedTransactions.traverse(transactionStorage.acceptTransaction)
    } yield ()
//  private def transferTransactionAmount(transaction: Transaction): F[Unit] =
//    for {
//      sourceBalance <- addressStorage.getBalance(transaction.source)
//      newSourceBalance <- refineV[NonNegative]
//        .apply(sourceBalance.coerce.value - transaction.amount.coerce.value)
//        .bimap(new Throwable(_), Balance(_))
//        .liftTo[F]
//      _ <- addressStorage.updateBalance(transaction.source, newSourceBalance)
//      destinationBalance <- addressStorage.getBalance(transaction.destination)
//      newDestinationBalance = Balance(destinationBalance.coerce |+| transaction.amount.coerce)
//      _ <- addressStorage.updateBalance(transaction.destination, newDestinationBalance)
//    } yield ()
}

object DAGBlockValidator {

  def takeConsecutiveTransactions(
    lastAcceptedTxRef: TransactionReference,
    txs: Seq[Signed[Transaction]]
  ): Seq[Signed[Transaction]] = {
    @tailrec
    def loop(
      acc: Seq[Signed[Transaction]],
      prevTxRef: TransactionReference,
      txs: Seq[Signed[Transaction]]
    ): Seq[Signed[Transaction]] =
      txs.find(_.value.parent == prevTxRef) match {
        case Some(tx) =>
          loop(tx +: acc, tx.value.parent, txs.diff(Seq(tx)))
        case None => acc.reverse //to preserve order of the chain
      }

    loop(Seq.empty, lastAcceptedTxRef, txs)
  }
}

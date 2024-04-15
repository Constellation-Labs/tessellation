package org.tessellation.dag.l1.domain.transaction

import java.security.KeyPair

import cats.data.NonEmptyChain
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import org.tessellation.dag.l1.domain.transaction.ContextualTransactionValidator._
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.generators._
import org.tessellation.schema.transaction._
import org.tessellation.security._
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.{NonNegLong, PosLong}
import eu.timepit.refined.types.numeric.PosInt
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object ContextualTransactionValidatorSuite extends MutableIOSuite with Checkers {

  type Res = (Hasher[IO], SecurityProvider[IO])

  def gen: Gen[(Address, TransactionSalt, Int)] = for {
    dst <- addressGen
    txnSalt <- transactionSaltGen
    keyPairs <- Gen.chooseNum(3, 100)
  } yield (dst, txnSalt, keyPairs)

  def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    implicit0(js: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forKryo[IO]
  } yield (h, sp)

  val config = TransactionLimitConfig(
    Balance(100000000L),
    20.hours,
    TransactionFee(200000L),
    43.seconds
  )

  def durationToOrdinals(duration: FiniteDuration): Long = Math.floor(duration / config.timeTriggerInterval).toLong

  def genTransaction(keyPair: KeyPair, lastRef: TransactionReference, fee: TransactionFee = TransactionFee.zero)(
    implicit hs: Hasher[IO],
    sp: SecurityProvider[IO]
  ): IO[Signed[Transaction]] =
    KeyPairGenerator.makeKeyPair.map(_.getPublic.toAddress).flatMap { dst =>
      Signed
        .forAsyncHasher(
          Transaction(keyPair.getPublic.toAddress, dst, TransactionAmount(1L), fee, lastRef, TransactionSalt(1L)),
          keyPair
        )
    }

  def generateTxChain(
    keyPair: KeyPair,
    count: PosInt
  )(implicit sp: SecurityProvider[IO], hs: Hasher[IO]): IO[SortedMap[TransactionOrdinal, StoredTransaction]] =
    (1 to count).toList
      .foldLeftM(SortedMap.empty[TransactionOrdinal, StoredTransaction]) {
        case (acc, _) =>
          val lastRef = acc.lastOption match {
            case Some((_, tx)) => tx.ref
            case None          => TransactionReference.empty
          }

          genTransaction(keyPair, lastRef, TransactionFee.zero)
            .flatMap(_.toHashed)
            .map { tx =>
              val ref = TransactionReference.of(tx)
              val stored = if (lastRef === TransactionReference.empty) MajorityTx(ref, SnapshotOrdinal.MinValue) else WaitingTx(tx)
              acc + (tx.ordinal -> stored)
            }
      }

  test("Transaction is rejected if insufficient balance") { res =>
    implicit val (hasher, sp) = res
    forall(gen) {
      case (dst, salt, _) =>
        for {
          kp <- KeyPairGenerator.makeKeyPair
          majorityTx <- Signed
            .forAsyncHasher(
              Transaction(
                kp.getPublic.toAddress,
                kp.getPublic.toAddress,
                TransactionAmount(1L),
                TransactionFee.zero,
                TransactionReference.empty,
                salt
              ),
              kp
            )
            .flatMap(_.toHashed)

          txs = SortedMap(
            majorityTx.ordinal -> MajorityTx(TransactionReference.of(majorityTx), SnapshotOrdinal.MinValue)
          ).some
          balance = Balance(100000000L)
          lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(84L)
          lastProcessedTransactionRef = TransactionReference.empty

          validator = ContextualTransactionValidator.make(config, None)
          tx = Transaction(
            kp.getPublic.toAddress,
            dst,
            TransactionAmount(100000001L),
            TransactionFee.zero,
            TransactionReference.of(majorityTx),
            salt
          )
          signedTx <- Signed.forAsyncHasher(tx, kp)
          hashedTx <- signedTx.toHashed
          context = TransactionValidatorContext(txs, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
          result = validator.validate(hashedTx, context)
        } yield
          expect.eql(
            result,
            NonEmptyChain
              .of(InsufficientBalance(tx.amount, balance))
              .invalid
          )
    }
  }

  test("Transaction is rejected if insufficient balance is caused by mempool transactions") { res =>
    implicit val (hasher, sp) = res
    forall(gen) {
      case (dst, salt, _) =>
        for {
          kp <- KeyPairGenerator.makeKeyPair
          majorityTx <- Signed
            .forAsyncHasher(
              Transaction(
                kp.getPublic.toAddress,
                dst,
                TransactionAmount(1L),
                TransactionFee.zero,
                TransactionReference.empty,
                salt
              ),
              kp
            )
            .flatMap(_.toHashed)
          waitingTx <- Signed
            .forAsyncHasher(
              Transaction(
                kp.getPublic.toAddress,
                dst,
                TransactionAmount(1L),
                TransactionFee.zero,
                TransactionReference.of(majorityTx),
                salt
              ),
              kp
            )
            .flatMap(_.toHashed)
          txs = SortedMap(
            majorityTx.ordinal -> MajorityTx(TransactionReference.of(majorityTx), SnapshotOrdinal.MinValue),
            waitingTx.ordinal -> WaitingTx(waitingTx)
          )

          balance = Balance(100000001L)
          lastSnapshotOrdinal = SnapshotOrdinal.MinValue
          lastProcessedTransactionRef = TransactionReference.empty

          validator = ContextualTransactionValidator.make(config, None)
          tx = Transaction(
            kp.getPublic.toAddress,
            dst,
            TransactionAmount(PosLong.unsafeFrom(balance.value)),
            TransactionFee.zero,
            TransactionReference.of(waitingTx),
            salt
          )
          signedTx <- Signed.forAsyncHasher(tx, kp)
          hashedTx <- signedTx.toHashed
          context = TransactionValidatorContext(txs.some, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
          result = validator.validate(hashedTx, context)
        } yield
          expect.eql(
            result,
            NonEmptyChain
              .of(InsufficientBalance(tx.amount, balance.minus(waitingTx.amount).getOrElse(Balance.empty)))
              .invalid
          )
    }
  }

  test("Transaction with minimal required fee is not limited") { res =>
    implicit val (hasher, sp) = res
    for {
      dst <- KeyPairGenerator.makeKeyPair
      kp <- KeyPairGenerator.makeKeyPair
      validator = ContextualTransactionValidator.make(config, None)
      tx = Transaction(
        kp.getPublic.toAddress,
        dst.getPublic.toAddress,
        TransactionAmount(1L),
        config.minFeeWithoutLimit,
        TransactionReference.empty,
        TransactionSalt(1L)
      )
      txs = none
      balance = config.baseBalance
      lastSnapshotOrdinal = SnapshotOrdinal.MinValue
      lastProcessedTransactionRef = TransactionReference.empty
      hashedTx <- Signed.forAsyncHasher(tx, kp).flatMap(_.toHashed)
      context = TransactionValidatorContext(txs, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
      result = validator.validate(
        hashedTx,
        context
      )
    } yield expect.eql(true, result.isValid)
  }

  test("Transaction overrides existing waiting transaction by higher fee") { res =>
    implicit val (hasher, sp) = res
    for {
      kp <- KeyPairGenerator.makeKeyPair
      majorityTx <- genTransaction(kp, TransactionReference.empty, TransactionFee.zero).flatMap(_.toHashed)
      majorityTxRef = TransactionReference.of(majorityTx)
      conflictingTx <- genTransaction(kp, majorityTxRef, TransactionFee(2L)).flatMap(_.toHashed)
      conflictingTxRef = TransactionReference.of(conflictingTx)

      txs = SortedMap(
        majorityTxRef.ordinal -> MajorityTx(majorityTxRef, SnapshotOrdinal.MinValue),
        conflictingTxRef.ordinal -> WaitingTx(conflictingTx)
      )
      balance = Balance(NonNegLong.MaxValue)
      lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(durationToOrdinals(config.timeToWaitForBaseBalance))
      lastProcessedTransactionRef = TransactionReference.empty
      validator = ContextualTransactionValidator.make(config, None)
      txLowerFee <- genTransaction(kp, majorityTxRef, TransactionFee(1L)).flatMap(_.toHashed)
      txEqualFee <- genTransaction(kp, majorityTxRef, TransactionFee(2L)).flatMap(_.toHashed)
      txHigherFee <- genTransaction(kp, majorityTxRef, TransactionFee(3L)).flatMap(_.toHashed)
      resultLower = validator.validate(
        txLowerFee,
        TransactionValidatorContext(txs.some, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
      )
      resultEqual = validator.validate(
        txEqualFee,
        TransactionValidatorContext(txs.some, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
      )
      resultHigher = validator.validate(
        txHigherFee,
        TransactionValidatorContext(txs.some, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
      )
    } yield
      expect.all(
        resultLower === Conflict(conflictingTx.ordinal, conflictingTxRef.hash, txLowerFee.hash).invalidNec,
        resultEqual === Conflict(conflictingTx.ordinal, conflictingTxRef.hash, txEqualFee.hash).invalidNec,
        resultHigher.isValid
      )
  }

  test("Transaction does not override existing non-waiting transaction") { res =>
    implicit val (hasher, sp) = res
    for {
      kp <- KeyPairGenerator.makeKeyPair
      majorityTx <- genTransaction(kp, TransactionReference.empty, TransactionFee.zero).flatMap(_.toHashed)
      majorityTxRef = TransactionReference.of(majorityTx)
      acceptedTx <- genTransaction(kp, majorityTxRef, TransactionFee(2L)).flatMap(_.toHashed)
      acceptedTxRef = TransactionReference.of(acceptedTx)
      processingTx <- genTransaction(kp, acceptedTxRef, TransactionFee(2L)).flatMap(_.toHashed)

      txs = SortedMap(
        majorityTx.ordinal -> MajorityTx(majorityTxRef, SnapshotOrdinal.MinValue),
        acceptedTx.ordinal -> AcceptedTx(acceptedTx), // LastTxRef
        processingTx.ordinal -> ProcessingTx(processingTx)
      )
      balance = Balance(NonNegLong.MaxValue)
      lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(durationToOrdinals(config.timeToWaitForBaseBalance))
      lastProcessedTransactionRef = TransactionReference.of(acceptedTx)
      validator = ContextualTransactionValidator.make(config, None)
      txOverridesAccepted <- genTransaction(kp, majorityTxRef, TransactionFee(3L)).flatMap(_.toHashed)
      txOverridesProcessing <- genTransaction(kp, acceptedTxRef, TransactionFee(3L)).flatMap(_.toHashed)
      resultAccepted = validator.validate(
        txOverridesAccepted,
        TransactionValidatorContext(txs.some, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
      )
      resultProcessing = validator.validate(
        txOverridesProcessing,
        TransactionValidatorContext(txs.some, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
      )
    } yield
      expect.all(
        resultAccepted === NonEmptyChain
          .of(ParentOrdinalLowerThenLastProcessedTxOrdinal(txOverridesAccepted.parent.ordinal, acceptedTx.ordinal))
          .invalid,
        resultProcessing === Conflict(txOverridesProcessing.ordinal, processingTx.hash, txOverridesProcessing.hash).invalidNec
      )
  }

  test(
    s"Transaction from base balance=${config.baseBalance.show} is limited for approx ${config.timeToWaitForBaseBalance.show}"
  ) { res =>
    implicit val (hasher, sp) = res
    forall(gen) {
      case (dst, salt, _) =>
        for {
          kp <- KeyPairGenerator.makeKeyPair
          balance = config.baseBalance
          lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(durationToOrdinals(config.timeToWaitForBaseBalance - 1.hour))
          txs = none
          lastProcessedTransactionRef = TransactionReference.empty
          validator = ContextualTransactionValidator.make(config, None)
          tx = Transaction(kp.getPublic.toAddress, dst, TransactionAmount(1L), TransactionFee.zero, TransactionReference.empty, salt)
          signedTx <- Signed.forAsyncHasher(tx, kp)
          hashedTx <- signedTx.toHashed
          context = TransactionValidatorContext(txs, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
          result = validator.validate(hashedTx, context)
        } yield
          expect.eql(
            result,
            TransactionLimited(TransactionReference.of(hashedTx), hashedTx.fee).invalidNec
          )
    }
  }

  test(
    s"Transaction from base balance=${config.baseBalance.show} is allowed after waiting approx ${config.timeToWaitForBaseBalance.show}"
  ) { res =>
    implicit val (hasher, sp) = res
    forall(gen) {
      case (dst, salt, _) =>
        for {
          kp <- KeyPairGenerator.makeKeyPair
          balance = config.baseBalance
          lastProcessedTransactionRef = TransactionReference.empty
          txs = none
          lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(durationToOrdinals(config.timeToWaitForBaseBalance))
          validator = ContextualTransactionValidator.make(config, None)
          tx = Transaction(kp.getPublic.toAddress, dst, TransactionAmount(1L), TransactionFee.zero, TransactionReference.empty, salt)
          signedTx <- Signed.forAsyncHasher(tx, kp).flatMap(_.toHashed)
          context = TransactionValidatorContext(txs, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
          result = validator.validate(signedTx, context)
        } yield expect.eql(true, result.isValid)
    }
  }

  test(
    s"Transaction limit is based on balance relatively to base balance"
  ) { res =>
    implicit val (hasher, sp) = res

    for {
      baseBalanceAddress <- KeyPairGenerator.makeKeyPair
      higherThanBaseBalanceAddress <- KeyPairGenerator.makeKeyPair
      lowerThanBaseBalanceAddress <- KeyPairGenerator.makeKeyPair

      baseBalanceAddressTxs <- generateTxChain(baseBalanceAddress, 2) // 2 should not be allowed
      higherThanBaseBalanceAddressTxs <- generateTxChain(higherThanBaseBalanceAddress, 2) // 2 should be allowed
      lowerThanBaseBalanceAddressTxs <- generateTxChain(lowerThanBaseBalanceAddress, 1) // 1 should not be allowed

      getBalance = (address: Address) =>
        address match {
          case a if a === baseBalanceAddress.getPublic.toAddress => config.baseBalance
          case b if b === higherThanBaseBalanceAddress.getPublic.toAddress =>
            Balance(NonNegLong.unsafeFrom(config.baseBalance.value * 100000))
          case c if c === lowerThanBaseBalanceAddress.getPublic.toAddress =>
            Balance(NonNegLong.unsafeFrom(config.baseBalance.value - 1))
          case _ => Balance.empty
        }

      getTransactions = (address: Address) =>
        address match {
          case a if a === baseBalanceAddress.getPublic.toAddress =>
            baseBalanceAddressTxs.some
          case b if b === higherThanBaseBalanceAddress.getPublic.toAddress =>
            higherThanBaseBalanceAddressTxs.some
          case c if c === lowerThanBaseBalanceAddress.getPublic.toAddress =>
            lowerThanBaseBalanceAddressTxs.some
          case _ => none
        }

      lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(durationToOrdinals(config.timeToWaitForBaseBalance))
      lastTxRef = TransactionReference.empty

      validator = ContextualTransactionValidator.make(config, None)

      txA <- genTransaction(baseBalanceAddress, baseBalanceAddressTxs.last._2.ref).flatMap(_.toHashed)
      txB <- genTransaction(higherThanBaseBalanceAddress, higherThanBaseBalanceAddressTxs.last._2.ref).flatMap(_.toHashed)
      txC <- genTransaction(lowerThanBaseBalanceAddress, lowerThanBaseBalanceAddressTxs.last._2.ref).flatMap(_.toHashed)

      resultA = validator.validate(
        txA,
        TransactionValidatorContext(getTransactions(txA.source), getBalance(txA.source), lastTxRef, lastSnapshotOrdinal)
      )
      resultB = validator.validate(
        txB,
        TransactionValidatorContext(getTransactions(txB.source), getBalance(txB.source), lastTxRef, lastSnapshotOrdinal)
      )
      resultC = validator.validate(
        txC,
        TransactionValidatorContext(getTransactions(txC.source), getBalance(txC.source), lastTxRef, lastSnapshotOrdinal)
      )
    } yield
      expect.all(
        resultA === TransactionLimited(TransactionReference.of(txA), txA.fee).invalidNec,
        resultB.isValid,
        resultC === TransactionLimited(TransactionReference.of(txC), txC.fee).invalidNec
      )
  }

  test("Custom validator rejects transaction") { res =>
    implicit val (hasher, sp) = res
    for {
      dst <- KeyPairGenerator.makeKeyPair
      kp <- KeyPairGenerator.makeKeyPair
      error = CustomValidationError("Fee can't be odd number!")
      customContextualValidator = new CustomContextualTransactionValidator {
        def validate(
          hashedTransaction: Hashed[Transaction],
          context: TransactionValidatorContext
        ): Either[CustomValidationError, Hashed[Transaction]] =
          Either.cond(
            hashedTransaction.fee.value % 2 == 0,
            hashedTransaction,
            error
          )
      }
      validator = ContextualTransactionValidator.make(config, customContextualValidator.some)
      tx = Transaction(
        kp.getPublic.toAddress,
        dst.getPublic.toAddress,
        TransactionAmount(1L),
        TransactionFee(99999999L),
        TransactionReference.empty,
        TransactionSalt(1L)
      )
      hashedTx <- Signed.forAsyncHasher(tx, kp).flatMap(_.toHashed)
      context = TransactionValidatorContext(none, config.baseBalance, TransactionReference.empty, SnapshotOrdinal.MinValue)
      result = validator.validate(
        hashedTx,
        context
      )
    } yield expect.eql(result, error.invalidNec)
  }

  test("Custom validator approves transaction") { res =>
    implicit val (hasher, sp) = res
    for {
      dst <- KeyPairGenerator.makeKeyPair
      kp <- KeyPairGenerator.makeKeyPair
      error = CustomValidationError("Fee can't be odd number!")
      customContextualValidator = new CustomContextualTransactionValidator {
        def validate(
          hashedTransaction: Hashed[Transaction],
          context: TransactionValidatorContext
        ): Either[CustomValidationError, Hashed[Transaction]] =
          Either.cond(
            hashedTransaction.fee.value % 2 == 0,
            hashedTransaction,
            error
          )
      }
      validator = ContextualTransactionValidator.make(config, customContextualValidator.some)
      tx = Transaction(
        kp.getPublic.toAddress,
        dst.getPublic.toAddress,
        TransactionAmount(1L),
        TransactionFee(99999998L),
        TransactionReference.empty,
        TransactionSalt(1L)
      )
      hashedTx <- Signed.forAsyncHasher(tx, kp).flatMap(_.toHashed)
      context = TransactionValidatorContext(none, config.baseBalance, TransactionReference.empty, SnapshotOrdinal.MinValue)
      result = validator.validate(
        hashedTx,
        context
      )
    } yield expect(result.isValid)
  }
}

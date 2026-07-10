package io.constellationnetwork.dag.l1.domain.transaction

import java.security.KeyPair

import cats.data.NonEmptyChain
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import io.constellationnetwork.dag.l1.domain.transaction.ContextualTransactionValidator._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed.{ProofsHasher, SignedHasher}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.{NonNegLong, PosLong}
import eu.timepit.refined.types.numeric.PosInt
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object ContextualTransactionValidatorSuite extends MutableIOSuite with Checkers {

  type Res = (Hasher[IO], SecurityProvider[IO], KryoSerializer[IO], JsonSerializer[IO])

  def gen: Gen[(Address, TransactionSalt, Int)] = for {
    dst <- addressGen
    txnSalt <- transactionSaltGen
    keyPairs <- Gen.chooseNum(3, 100)
  } yield (dst, txnSalt, keyPairs)

  def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    implicit0(js: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forKryo[IO]
  } yield (h, sp, ks, js)

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
  )(
    implicit sp: SecurityProvider[IO],
    hasher: Hasher[IO],
    js: JsonSerializer[IO],
    ks: KryoSerializer[IO]
  ): IO[SortedMap[TransactionOrdinal, StoredTransaction]] =
    (1 to count).toList
      .foldLeftM(SortedMap.empty[TransactionOrdinal, StoredTransaction]) {
        case (acc, _) =>
          val lastRef = acc.lastOption match {
            case Some((_, tx)) => tx.ref
            case None          => TransactionReference.empty
          }
          implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
          implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])

          genTransaction(keyPair, lastRef, TransactionFee.zero)
            .flatMap(_.toHashedHybrid)
            .map { tx =>
              val ref = TransactionReference.of(tx)
              val stored = if (lastRef === TransactionReference.empty) MajorityTx(ref, SnapshotOrdinal.MinValue) else WaitingTx(tx)
              acc + (tx.ordinal -> stored)
            }
      }

  test("Transaction is rejected if insufficient balance") { res =>
    implicit val (hasher, sp, ks, js) = res
    implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
    implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])
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
            .flatMap(_.toHashedHybrid)

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
          implicit0(kryoHasher: SignedHasher[IO]) = SignedHasher(Hasher.forKryo[IO])
          implicit0(proofsHasher: ProofsHasher[IO]) = ProofsHasher(Hasher.forJson[IO])
          hashedTx <- signedTx.toHashedHybrid
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
    implicit val (hasher, sp, ks, js) = res
    implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
    implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])
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
            .flatMap(_.toHashedHybrid)
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
            .flatMap(_.toHashedHybrid)
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
          implicit0(kryoHasher: SignedHasher[IO]) = SignedHasher(Hasher.forKryo[IO])
          implicit0(proofsHasher: ProofsHasher[IO]) = ProofsHasher(Hasher.forJson[IO])
          hashedTx <- signedTx.toHashedHybrid
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
    implicit val (hasher, sp, ks, js) = res
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
      implicit0(kryoHasher: SignedHasher[IO]) = SignedHasher(Hasher.forKryo[IO])
      implicit0(proofsHasher: ProofsHasher[IO]) = ProofsHasher(Hasher.forJson[IO])
      hashedTx <- Signed.forAsyncHasher(tx, kp).flatMap(_.toHashedHybrid)
      context = TransactionValidatorContext(txs, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
      result = validator.validate(
        hashedTx,
        context
      )
    } yield expect.eql(true, result.isValid)
  }

  test("Transaction overrides existing waiting transaction by higher fee") { res =>
    implicit val (hasher, sp, ks, js) = res
    implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
    implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])
    for {
      kp <- KeyPairGenerator.makeKeyPair
      majorityTx <- genTransaction(kp, TransactionReference.empty, TransactionFee.zero).flatMap(_.toHashedHybrid)
      majorityTxRef = TransactionReference.of(majorityTx)
      conflictingTx <- genTransaction(kp, majorityTxRef, TransactionFee(2L)).flatMap(_.toHashedHybrid)
      conflictingTxRef = TransactionReference.of(conflictingTx)

      txs = SortedMap(
        majorityTxRef.ordinal -> MajorityTx(majorityTxRef, SnapshotOrdinal.MinValue),
        conflictingTxRef.ordinal -> WaitingTx(conflictingTx)
      )
      balance = Balance(NonNegLong.MaxValue)
      lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(durationToOrdinals(config.timeToWaitForBaseBalance))
      lastProcessedTransactionRef = TransactionReference.empty
      validator = ContextualTransactionValidator.make(config, None)
      txLowerFee <- genTransaction(kp, majorityTxRef, TransactionFee(1L)).flatMap(_.toHashedHybrid)
      txEqualFee <- genTransaction(kp, majorityTxRef, TransactionFee(2L)).flatMap(_.toHashedHybrid)
      txHigherFee <- genTransaction(kp, majorityTxRef, TransactionFee(3L)).flatMap(_.toHashedHybrid)
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

  // Regression (TX-07): a higher-fee override must NOT be double-counted with the WaitingTx it replaces. The override
  // and the replaced tx share an ordinal; with the old `>` filter the replaced tx stayed in the validation context, so
  // the rate-limit cap was charged for BOTH and the override was wrongly rejected as TransactionLimited. With the `>=`
  // fix only the override is counted, so it validates. Fails on a revert to `>`.
  test("fee-bump override is not double-counted against the rate limit (TX-07)") { res =>
    implicit val (hasher, sp, ks, js) = res
    implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
    implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])
    for {
      kp <- KeyPairGenerator.makeKeyPair
      majorityTx <- genTransaction(kp, TransactionReference.empty, TransactionFee.zero).flatMap(_.toHashedHybrid)
      majorityTxRef = TransactionReference.of(majorityTx)
      conflictingTx <- genTransaction(kp, majorityTxRef, TransactionFee(1L)).flatMap(_.toHashedHybrid)
      conflictingTxRef = TransactionReference.of(conflictingTx)
      txs = SortedMap(
        majorityTxRef.ordinal -> MajorityTx(majorityTxRef, SnapshotOrdinal.MinValue),
        conflictingTxRef.ordinal -> WaitingTx(conflictingTx)
      )
      // 2x baseBalance => the cap comfortably admits ONE below-min-fee tx but not two; the second (the double-counted
      // replaced tx under `>`) blows the cap.
      balance = Balance(200000000L)
      lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(durationToOrdinals(config.timeToWaitForBaseBalance))
      validator = ContextualTransactionValidator.make(config, None)
      overrideTx <- genTransaction(kp, majorityTxRef, TransactionFee(2L)).flatMap(_.toHashedHybrid)
      result = validator.validate(
        overrideTx,
        TransactionValidatorContext(txs.some, balance, TransactionReference.empty, lastSnapshotOrdinal)
      )
    } yield expect.eql(true, result.isValid)
  }

  test("Transaction does not override existing non-waiting transaction") { res =>
    implicit val (hasher, sp, ks, js) = res
    for {
      kp <- KeyPairGenerator.makeKeyPair
      implicit0(kryoHasher: SignedHasher[IO]) = SignedHasher(Hasher.forKryo[IO])
      implicit0(proofsHasher: ProofsHasher[IO]) = ProofsHasher(Hasher.forJson[IO])
      majorityTx <- genTransaction(kp, TransactionReference.empty, TransactionFee.zero).flatMap(_.toHashedHybrid)
      majorityTxRef = TransactionReference.of(majorityTx)
      acceptedTx <- genTransaction(kp, majorityTxRef, TransactionFee(2L)).flatMap(_.toHashedHybrid)
      acceptedTxRef = TransactionReference.of(acceptedTx)
      processingTx <- genTransaction(kp, acceptedTxRef, TransactionFee(2L)).flatMap(_.toHashedHybrid)

      txs = SortedMap(
        majorityTx.ordinal -> MajorityTx(majorityTxRef, SnapshotOrdinal.MinValue),
        acceptedTx.ordinal -> AcceptedTx(acceptedTx), // LastTxRef
        processingTx.ordinal -> ProcessingTx(processingTx)
      )
      balance = Balance(NonNegLong.MaxValue)
      lastSnapshotOrdinal = SnapshotOrdinal.unsafeApply(durationToOrdinals(config.timeToWaitForBaseBalance))
      lastProcessedTransactionRef = TransactionReference.of(acceptedTx)
      validator = ContextualTransactionValidator.make(config, None)
      txOverridesAccepted <- genTransaction(kp, majorityTxRef, TransactionFee(3L)).flatMap(_.toHashedHybrid)
      txOverridesProcessing <- genTransaction(kp, acceptedTxRef, TransactionFee(3L)).flatMap(_.toHashedHybrid)
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
    implicit val (hasher, sp, ks, js) = res
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
          implicit0(kryoHasher: SignedHasher[IO]) = SignedHasher(Hasher.forKryo[IO])
          implicit0(proofsHasher: ProofsHasher[IO]) = ProofsHasher(Hasher.forJson[IO])
          hashedTx <- signedTx.toHashedHybrid
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
    implicit val (hasher, sp, ks, js) = res
    implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
    implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])
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
          signedTx <- Signed.forAsyncHasher(tx, kp).flatMap(_.toHashedHybrid)
          context = TransactionValidatorContext(txs, balance, lastProcessedTransactionRef, lastSnapshotOrdinal)
          result = validator.validate(signedTx, context)
        } yield expect.eql(true, result.isValid)
    }
  }

  test(
    s"Transaction limit is based on balance relatively to base balance"
  ) { res =>
    implicit val (hasher, sp, ks, js) = res
    implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
    implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])
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

      txA <- genTransaction(baseBalanceAddress, baseBalanceAddressTxs.last._2.ref).flatMap(_.toHashedHybrid)
      txB <- genTransaction(higherThanBaseBalanceAddress, higherThanBaseBalanceAddressTxs.last._2.ref).flatMap(_.toHashedHybrid)
      txC <- genTransaction(lowerThanBaseBalanceAddress, lowerThanBaseBalanceAddressTxs.last._2.ref).flatMap(_.toHashedHybrid)

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
    implicit val (hasher, sp, ks, js) = res
    implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
    implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])
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
      hashedTx <- Signed.forAsyncHasher(tx, kp).flatMap(_.toHashedHybrid)
      context = TransactionValidatorContext(none, config.baseBalance, TransactionReference.empty, SnapshotOrdinal.MinValue)
      result = validator.validate(
        hashedTx,
        context
      )
    } yield expect.eql(result, error.invalidNec)
  }

  test("Custom validator approves transaction") { res =>
    implicit val (hasher, sp, ks, js) = res
    implicit val kryoHasher: SignedHasher[IO] = SignedHasher(Hasher.forKryo[IO])
    implicit val proofsHasher: ProofsHasher[IO] = ProofsHasher(Hasher.forJson[IO])
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
      hashedTx <- Signed.forAsyncHasher(tx, kp).flatMap(_.toHashedHybrid)
      context = TransactionValidatorContext(none, config.baseBalance, TransactionReference.empty, SnapshotOrdinal.MinValue)
      result = validator.validate(
        hashedTx,
        context
      )
    } yield expect(result.isValid)
  }
}

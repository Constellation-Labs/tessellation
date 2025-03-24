package io.constellationnetwork.node.shared.domain.delegatedStake

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.{catsSyntaxOptionId, catsSyntaxValidatedIdBinCompat0}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator._
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.nodeCollateral._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.SignedValidator.{InvalidSignatures, NotSignedExclusivelyByAddressOwner}
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object UpdateDelegatedStakeValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO], KeyPair, Address)

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
    kp <- KeyPairGenerator.makeKeyPair[IO].asResource
    sourceAddress <- kp.getPublic.toId.toAddress.asResource
  } yield (j, h, sp, kp, sourceAddress)

  def testCreateDelegatedStake(
    keyPair: KeyPair,
    sourceAddress: Address,
    tokenLockReference: Hash = Hash.empty,
    parent: DelegatedStakeReference = DelegatedStakeReference.empty
  ): UpdateDelegatedStake.Create = UpdateDelegatedStake.Create(
    source = sourceAddress,
    nodeId = PeerId.fromPublic(keyPair.getPublic),
    amount = DelegatedStakeAmount(NonNegLong(100L)),
    tokenLockRef = tokenLockReference,
    parent = parent
  )

  def testWithdrawDelegatedStake(keyPair: KeyPair, sourceAddress: Address): UpdateDelegatedStake.Withdraw = UpdateDelegatedStake.Withdraw(
    source = sourceAddress,
    stakeRef = DelegatedStakeReference.empty.hash
  )

  def testTokenLocks(keyPair: KeyPair, amount: Long = 100L, tokenLockUnlockEpoch: Option[EpochProgress] = None)(
    implicit sp: SecurityProvider[IO],
    h: Hasher[IO]
  ) = {
    val testTokenLock = TokenLock(
      source = keyPair.getPublic.toAddress,
      amount = TokenLockAmount(PosLong.unsafeFrom(amount)),
      fee = TokenLockFee(NonNegLong(0L)),
      parent = TokenLockReference.empty,
      currencyId = None,
      unlockEpoch = tokenLockUnlockEpoch
    )
    for {
      signed <- forAsyncHasher(testTokenLock, keyPair)
      ref <- TokenLockReference.of(signed)
    } yield (ref, SortedMap(keyPair.getPublic.toAddress -> SortedSet(signed)))
  }

  def mkValidGlobalContext(keyPair: KeyPair, tokenLockAmount: Long = 100L, tokenLockUnlockEpoch: Option[EpochProgress] = None)(
    implicit sp: SecurityProvider[IO],
    h: Hasher[IO]
  ) =
    for {
      (ref, tokenLocks) <- testTokenLocks(keyPair, tokenLockAmount, tokenLockUnlockEpoch)
    } yield (ref.hash, mkGlobalContext(tokenLocks = tokenLocks))

  test("should succeed when the create delegated stake is signed correctly, the node is authorized, and parents are valid") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed <- forAsyncHasher(validCreate, keyPair)
      seedlist <- mkSeedlist(validCreate.nodeId)
      validator = mkValidator(seedlist)
      result <- validator.validateCreateDelegatedStake(signed, lastContext)
    } yield expect.same(Valid(signed), result)
  }

  test("should fail when the create delegated stake is not signed correctly") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed <- forAsyncHasher(validCreate, keyPair).map(signed =>
        signed.copy(proofs =
          NonEmptySet.fromSetUnsafe(
            SortedSet(signed.proofs.head.copy(id = keyPair1.getPublic.toId))
          )
        )
      )
      seedlist <- mkSeedlist(validCreate.nodeId)
      validator = mkValidator(seedlist)
      result <- validator.validateCreateDelegatedStake(signed, lastContext)
    } yield expect.all(result match {
      case Invalid(errors) =>
        errors.exists {
          case InvalidSigned(NotSignedExclusivelyByAddressOwner) => true
          case InvalidSigned(InvalidSignatures(signed.proofs)) => true
          case _ => false
        }
      case _ => false
    })
  }

  test("should fail when the create delegated stake has more than one signature") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      activeTokenLocks = lastContext.activeTokenLocks.get
      tokenLocks = activeTokenLocks(keyPair.getPublic.toAddress)
      lastContext1 = lastContext.copy(activeTokenLocks = Some(activeTokenLocks.updated(keyPair2.getPublic.toAddress, tokenLocks)))
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed1 <- forAsyncHasher(validCreate, keyPair1)
      signed2 <- forAsyncHasher(validCreate, keyPair2)
      signed = signed1.addProof(signed2.proofs.head)
      seedlist <- mkSeedlist(validCreate.nodeId)
      validator = mkValidator(seedlist)
      result <- validator.validateCreateDelegatedStake(signed, lastContext1)
    } yield
      expect.all(result match {
        case Invalid(errors) =>
          errors.exists {
            case TooManySignatures(proofs)                         => proofs == signed.proofs
            case InvalidSigned(NotSignedExclusivelyByAddressOwner) => true
            case _                                                 => false
          }
        case _ => false
      })
  }

  test("should succeed when the create delegated stake is signed correctly but the seed list is empty") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed <- forAsyncHasher(validCreate, keyPair)
      validator = mkValidator()
      result <- validator.validateCreateDelegatedStake(signed, lastContext)
    } yield expect.same(Valid(signed), result)
  }

  test("should fail when the create delegated stake is signed correctly but the node is not authorized") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      invalidCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed <- forAsyncHasher(invalidCreate, keyPair)
      seedlist <- mkSeedlist()
      validator = mkValidator(seedlist)
      result <- validator.validateCreateDelegatedStake(signed, lastContext)
    } yield expect.same(UnauthorizedNode(invalidCreate.nodeId).invalidNec, result)
  }

  test("should fail when there is another stake for this node") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      parent = testCreateDelegatedStake(keyPair, sourceAddress, Hash.empty)
      signedParent <- forAsyncHasher(parent, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = lastContext.copy(activeDelegatedStakes = Some(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue)))))
      lastRef <- DelegatedStakeReference.of(signedParent)
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference, lastRef)
      signed <- forAsyncHasher(validCreate, keyPair)
      validator = mkValidator()
      result <- validator.validateCreateDelegatedStake(signed, context)
    } yield expect.same(StakeExistsForNode(validCreate.nodeId).invalidNec, result)
  }

  test("should succeed when the tokenLock is not available (another delegated stake exists / overwrite)") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      nodeId1 = PeerId.fromPublic(keyPair1.getPublic)
      parent = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signedParent <- forAsyncHasher(parent.copy(nodeId = nodeId1), keyPair)
      lastRef <- DelegatedStakeReference.of(signedParent)
      address <- signedParent.proofs.head.id.toAddress
      context = lastContext.copy(activeDelegatedStakes = Some(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue)))))
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference, lastRef)
      signed <- forAsyncHasher(validCreate, keyPair)
      validator = mkValidator()
      result <- validator.validateCreateDelegatedStake(signed, context)
    } yield expect.same(Valid(signed), result)
  }

  test("should fail an overwrite request when a pending withdrawal exists") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      nodeId1 = PeerId.fromPublic(keyPair1.getPublic)
      parent = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signedParent <- forAsyncHasher(parent.copy(nodeId = nodeId1), keyPair)
      lastRef <- DelegatedStakeReference.of(signedParent)
      validWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef.hash)
      signedWithdraw <- forAsyncHasher(validWithdraw, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = lastContext.copy(
        activeDelegatedStakes = Some(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue)))),
        delegatedStakesWithdrawals = Some(SortedMap(address -> List((signedWithdraw, EpochProgress.MinValue))))
      )
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference, lastRef)
      signed <- forAsyncHasher(validCreate, keyPair)
      validator = mkValidator()
      result <- validator.validateCreateDelegatedStake(signed, context)
    } yield expect.same(AlreadyWithdrawn(validCreate.parent.hash).invalidNec, result)
  }

  test("should fair when the lastRef of the existing delegated stake is different") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      parent = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      nodeId1 = PeerId.fromPublic(keyPair1.getPublic)
      signedParent <- forAsyncHasher(parent.copy(nodeId = nodeId1), keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = lastContext.copy(activeDelegatedStakes = Some(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue)))))
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed <- forAsyncHasher(validCreate, keyPair)
      validator = mkValidator()
      result <- validator.validateCreateDelegatedStake(signed, context)
    } yield expect.same(InvalidParent(validCreate.parent).invalidNec, result)
  }

  test("should fail when the tokenLock is not available (a node collateral exists)") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair)
      nodeCollateral = UpdateNodeCollateral.Create(
        source = sourceAddress,
        nodeId = PeerId.fromPublic(keyPair.getPublic),
        amount = NodeCollateralAmount(NonNegLong(100L)),
        tokenLockRef = tokenLockReference
      )
      signedParent <- forAsyncHasher(nodeCollateral, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = lastContext.copy(activeNodeCollaterals = Some(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue)))))
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed <- forAsyncHasher(validCreate, keyPair)
      validator = mkValidator()
      result <- validator.validateCreateDelegatedStake(signed, context)
    } yield expect.same(InvalidTokenLock(tokenLockReference).invalidNec, result)
  }

  test("should fail when the tokenLock amount is too low") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val parent = testCreateDelegatedStake(keyPair, sourceAddress)

    for {
      signedParent <- forAsyncHasher(parent, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair, tokenLockAmount = 10L)
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed <- forAsyncHasher(validCreate, keyPair)
      validator = mkValidator()
      result <- validator.validateCreateDelegatedStake(signed, lastContext)
    } yield expect.same(InvalidTokenLock(tokenLockReference).invalidNec, result)
  }

  test("should fail when the tokenLock expires too soon") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    for {
      (tokenLockReference, lastContext) <- mkValidGlobalContext(keyPair, tokenLockUnlockEpoch = EpochProgress.MaxValue.some)
      parent = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signedParent <- forAsyncHasher(parent, keyPair)
      validCreate = testCreateDelegatedStake(keyPair, sourceAddress, tokenLockReference)
      signed <- forAsyncHasher(validCreate, keyPair)
      validator = mkValidator()
      result <- validator.validateCreateDelegatedStake(signed, lastContext)
    } yield expect.same(InvalidTokenLock(tokenLockReference).invalidNec, result)
  }

  test("should succeed when the withdraw delegated stake is signed correctly") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val validParent = testCreateDelegatedStake(keyPair, sourceAddress)

    for {
      signedParent <- forAsyncHasher(validParent, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = mkGlobalContext(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue))))
      lastRef <- DelegatedStakeReference.of(signedParent)
      validWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef.hash)
      signed <- forAsyncHasher(validWithdraw, keyPair)
      validator = mkValidator()
      result <- validator.validateWithdrawDelegatedStake(signed, context)
    } yield expect.same(Valid(signed), result)
  }

  test("should fail when the withdraw delegated stake is not signed correctly") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val validParent = testCreateDelegatedStake(keyPair, sourceAddress)

    for {
      signedParent <- forAsyncHasher(validParent, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = mkGlobalContext(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue))))
      lastRef <- DelegatedStakeReference.of(signedParent)
      validWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef.hash)
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      signed <- forAsyncHasher(validWithdraw, keyPair).map(signed =>
        signed.copy(proofs =
          NonEmptySet.fromSetUnsafe(
            SortedSet(signed.proofs.head.copy(id = keyPair1.getPublic.toId))
          )
        )
      )
      validator = mkValidator()
      result <- validator.validateWithdrawDelegatedStake(signed, context)
    } yield
      expect.all(result match {
        case invalid @ Invalid(_) =>
          invalid.e.exists { e =>
            e == InvalidSigned(NotSignedExclusivelyByAddressOwner)
          }
        case _ => false
      })
  }

  test("should fail when the withdraw delegated stake has more than one signature") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val validParent = testCreateDelegatedStake(keyPair, sourceAddress)

    for {
      signedParent <- forAsyncHasher(validParent, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = mkGlobalContext(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue))))
      lastRef <- DelegatedStakeReference.of(signedParent)
      validWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef.hash)
      signed1 <- forAsyncHasher(validWithdraw, keyPair)
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      signed2 <- forAsyncHasher(validWithdraw, keyPair1)
      signed = signed1.addProof(signed2.proofs.head)
      validator = mkValidator()
      result <- validator.validateWithdrawDelegatedStake(signed, context)
    } yield
      expect.all(result match {
        case invalid @ Invalid(_) =>
          invalid.e.exists { e =>
            e == TooManySignatures(signed.proofs)
          }
        case _ => false
      })
  }

  test("should fail when lastRef of the withdraw delegated stake is empty and the global context is empty") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val validWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress)

    for {
      signed <- forAsyncHasher(validWithdraw, keyPair)
      validator = mkValidator()
      result <- validator.validateWithdrawDelegatedStake(signed, mkGlobalContext())
    } yield expect.same(InvalidStake(signed.stakeRef).invalidNec, result)
  }

  test("should fail when lastRef of the withdraw delegated stake is not empty and the global context is empty") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val lastRef = DelegatedStakeReference.empty // .copy(ordinal = CreateDelegatedStakeReference.empty.ordinal.next)
    val invalidWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef.hash)

    for {
      signed <- forAsyncHasher(invalidWithdraw, keyPair)
      validator = mkValidator()
      result <- validator.validateWithdrawDelegatedStake(signed, mkGlobalContext())
    } yield expect.same(InvalidStake(lastRef.hash).invalidNec, result)
  }

  test("should succeed when lastRef of the withdraw delegated stake is not empty and the global context contains the parent") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val parent = testCreateDelegatedStake(keyPair, sourceAddress)

    for {
      signedParent <- forAsyncHasher(parent, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = mkGlobalContext(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue))))
      lastRef <- DelegatedStakeReference.of(signedParent)
      validWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef.hash)
      signed <- forAsyncHasher(validWithdraw, keyPair)
      validator = mkValidator()
      result <- validator.validateWithdrawDelegatedStake(signed, context)
    } yield expect.same(Valid(signed), result)
  }

  test("should fail when lastRef of the withdraw delegated stake is not empty and the global context does not contain the parent") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val parent = testCreateDelegatedStake(keyPair, sourceAddress)

    for {
      signedParent <- forAsyncHasher(parent, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      context = mkGlobalContext(SortedMap(address -> List()))
      lastRef <- h.hash(parent)
      invalidWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef)
      signed <- forAsyncHasher(invalidWithdraw, keyPair)
      validator = mkValidator()
      result <- validator.validateWithdrawDelegatedStake(signed, context)
    } yield expect.same(InvalidStake(lastRef).invalidNec, result)
  }

  test("should fail when lastRef of the withdraw delegated stake is not empty and the global context contains a parent from another user") {
    res =>
      implicit val (json, h, sp, keyPair, sourceAddress) = res

      val parent = testCreateDelegatedStake(keyPair, sourceAddress)

      for {
        keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
        signedParent <- forAsyncHasher(parent, keyPair1)
        address <- signedParent.proofs.head.id.toAddress
        context = mkGlobalContext(SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue))))
        lastRef <- DelegatedStakeReference.of(signedParent)
        invalidWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef.hash)
        signed <- forAsyncHasher(invalidWithdraw, keyPair)
        validator = mkValidator()
        result <- validator.validateWithdrawDelegatedStake(signed, context)
      } yield expect.same(InvalidStake(lastRef.hash).invalidNec, result)
  }

  test("should fail when the delegated stake is already withdrawn") { res =>
    implicit val (json, h, sp, keyPair, sourceAddress) = res

    val validParent = testCreateDelegatedStake(keyPair, sourceAddress)

    for {
      signedParent <- forAsyncHasher(validParent, keyPair)
      address <- signedParent.proofs.head.id.toAddress
      lastRef <- DelegatedStakeReference.of(signedParent)
      validWithdraw = testWithdrawDelegatedStake(keyPair, sourceAddress).copy(stakeRef = lastRef.hash)
      signed <- forAsyncHasher(validWithdraw, keyPair)
      context = mkGlobalContext(
        SortedMap(address -> List((signedParent, SnapshotOrdinal.MinValue))),
        withdrawals = SortedMap(address -> List((signed, EpochProgress.MinValue)))
      )
      seedlist <- mkSeedlist(validParent.nodeId)
      validator = mkValidator(seedlist)
      result <- validator.validateWithdrawDelegatedStake(signed, context)
    } yield expect.same(AlreadyWithdrawn(lastRef.hash).invalidNec, result)
  }

  private def mkSeedlist(peerIds: PeerId*)(implicit sp: SecurityProvider[IO]): IO[Option[Set[SeedlistEntry]]] =
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      seedlistEntry = SeedlistEntry(PeerId.fromPublic(keyPair.getPublic), None, None, None, None)
    } yield Some(peerIds.map(SeedlistEntry(_, None, None, None, None)).toSet + seedlistEntry)

  private def mkValidator(seedlist: Option[Set[SeedlistEntry]] = None)(
    implicit S: SecurityProvider[IO],
    J: JsonSerializer[IO],
    H: Hasher[IO]
  ): UpdateDelegatedStakeValidator[IO] = {
    val signedValidator = SignedValidator.make[IO]
    UpdateDelegatedStakeValidator.make[IO](
      signedValidator,
      seedlist
    )
  }

  def mkGlobalContext(
    delegatedStakes: SortedMap[Address, List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)]] = SortedMap.empty,
    withdrawals: SortedMap[Address, List[(Signed[UpdateDelegatedStake.Withdraw], EpochProgress)]] = SortedMap.empty,
    tokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]] = SortedMap.empty
  ) =
    GlobalSnapshotInfo.empty.copy(
      activeDelegatedStakes = Option.when(delegatedStakes.nonEmpty)(delegatedStakes),
      delegatedStakesWithdrawals = Option.when(withdrawals.nonEmpty)(withdrawals),
      activeTokenLocks = Option.when(tokenLocks.nonEmpty)(tokenLocks)
    )

}

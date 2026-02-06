package io.constellationnetwork.schema.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.merkletree.Proof
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.StateChangesAccumulator
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.transaction.{Transaction, TransactionReference}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.validator.StateProofValidator

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF

class IncrementalStateProofValidationSuite extends CatsEffectSuite with ScalaCheckEffectSuite {

  implicit val kryoSerializer: KryoSerializer[IO] = KryoSerializer.forAsync[IO](sharedKryoRegistrar)
  implicit val jsonSerializer: JsonSerializer[IO] = new JsonSerializer[IO]
  implicit val securityProvider: SecurityProvider[IO] = SecurityProvider.forAsync[IO]
  implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forAsync[IO](jsonFirst = true)

  // Mock state proof selector for testing
  implicit val globalStateProofSelector: GlobalStateProofSelector = new GlobalStateProofSelector {
    def select(ordinal: SnapshotOrdinal): StateProofLogic = JsonHash
  }

  // Test addresses for mock metagraphs
  val metagraphA: Address = Address("DAG1111metagraphAAAA")
  val metagraphB: Address = Address("DAG2222metagraphBBBB") 
  val metagraphC: Address = Address("DAG3333metagraphCCCC")

  def createMockCurrencySnapshot(
    metagraphAddr: Address,
    ordinal: SnapshotOrdinal,
    balance: Long = 1000
  ): IO[Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] = {
    for {
      keyPair <- securityProvider.keyPair
      
      balances = SortedMap(Address("DAG4444mockuser") -> Balance(Amount(NonNegLong.unsafeFrom(balance))))
      
      currencyInfo = CurrencySnapshotInfo(
        balances = balances,
        lastTxRefs = SortedMap.empty[Address, TransactionReference],
        lastSnapshotHash = Hash.empty,
        epoch = EpochProgress.MinValue
      )
      
      incrementalSnapshot = CurrencyIncrementalSnapshot(
        ordinal = ordinal,
        lastSnapshotHash = Hash.empty,
        balances = balances,
        lastTxRefs = SortedMap.empty,
        epoch = EpochProgress.MinValue,
        stateProof = CurrencySnapshotStateProof.empty // Will be updated later
      )
      
      signed <- securityProvider.sign(incrementalSnapshot)(keyPair)
      
    } yield Right((signed, currencyInfo))
  }

  def createMockProof(): Proof = {
    // Create a mock proof for testing
    Proof(List.empty, Hash("proof123"), Hash.empty)
  }

  test("Incremental StateProof validation should fail with delta-only proofs") {
    PropF.forAllF { (ordinal: SnapshotOrdinal) =>
      val testOrdinal = SnapshotOrdinal(ordinal.value + 1L) // Ensure > 0
      
      hasherSelector.withCurrent { implicit hasher =>
        for {
          // Create currency snapshots for 3 metagraphs
          snapshotA <- createMockCurrencySnapshot(metagraphA, testOrdinal, 1000)
          snapshotB <- createMockCurrencySnapshot(metagraphB, testOrdinal, 2000)
          snapshotC <- createMockCurrencySnapshot(metagraphC, testOrdinal, 3000)
          
          currencySnapshots = SortedMap(
            metagraphA -> snapshotA,
            metagraphB -> snapshotB,
            metagraphC -> snapshotC
          )
          
          // Build complete Merkle tree and proofs (simulates consensus creation)
          completeProofs <- currencySnapshots.traverse {
            case (addr, _) => (addr -> createMockProof()).pure[IO]
          }.map(_.toMap.to(SortedMap))
          
          // Create StateChangesAccumulator with ALL proofs (correct scenario)
          completeAccumulator = StateChangesAccumulator(
            lastCurrencySnapshots = currencySnapshots,
            lastCurrencySnapshotsProofs = completeProofs
          )
          
          // Create StateChangesAccumulator with DELTA-only proofs (problematic scenario)
          // Simulates the filtering in GlobalSnapshotAcceptanceManager lines 1105-1107
          changedCurrencySnapshots = SortedMap(metagraphA -> snapshotA) // Only A changed
          deltaOnlyProofs = completeProofs.filter { case (addr, _) => 
            changedCurrencySnapshots.contains(addr) 
          }
          
          deltaAccumulator = StateChangesAccumulator(
            lastCurrencySnapshots = changedCurrencySnapshots,
            lastCurrencySnapshotsProofs = deltaOnlyProofs // Missing proofs for B and C!
          )
          
          // Build MPT root from complete state (simulates original creation)
          completeMptRoot <- completeAccumulator.toStateEntries.flatMap(_.buildMpt)
          
          // Build MPT root from delta state (simulates validation context)
          deltaMptRoot <- deltaAccumulator.toStateEntries.flatMap(_.buildMpt)
          
          // The roots should be different, demonstrating the issue
          result = completeMptRoot != deltaMptRoot
          
          _ <- IO.println(s"Complete MPT root: ${completeMptRoot}")
          _ <- IO.println(s"Delta MPT root: ${deltaMptRoot}")
          _ <- IO.println(s"Roots differ: $result")
          
        } yield {
          // This test demonstrates that delta-only proof storage leads to different MPT roots
          // which would cause StateProof validation failures in the real system
          assert(result, "Delta-only proofs should produce different MPT root than complete proofs")
        }
      }
    }
  }

  test("StateProofValidator should fail when proof context is incomplete") {
    PropF.forAllF { (ordinal: SnapshotOrdinal) =>
      val testOrdinal = SnapshotOrdinal(ordinal.value + 1L)
      
      hasherSelector.withCurrent { implicit hasher =>
        for {
          // Create a mock GlobalSnapshotInfo with complete currency proofs
          snapshotA <- createMockCurrencySnapshot(metagraphA, testOrdinal)
          snapshotB <- createMockCurrencySnapshot(metagraphB, testOrdinal)
          
          completeProofs = SortedMap(
            metagraphA -> createMockProof(),
            metagraphB -> createMockProof()
          )
          
          // Complete context (what consensus creates)
          completeContext = GlobalSnapshotInfo(
            lastStateChannelSnapshotHashes = SortedMap.empty,
            lastTxRefs = SortedMap.empty,
            balances = SortedMap.empty,
            lastCurrencySnapshots = SortedMap(metagraphA -> snapshotA, metagraphB -> snapshotB),
            lastCurrencySnapshotsProofs = completeProofs,
            activeAllowSpends = None,
            activeTokenLocks = None,
            tokenLockBalances = None,
            lastAllowSpendRefs = None,
            lastTokenLockRefs = None,
            updateNodeParameters = None,
            activeDelegatedStakes = None,
            delegatedStakesWithdrawals = None,
            activeNodeCollaterals = None,
            nodeCollateralWithdrawals = None,
            priceState = None,
            metagraphSyncData = None
          )
          
          // Incomplete context (what validation sees after delta filtering)
          incompleteContext = completeContext.copy(
            lastCurrencySnapshots = SortedMap(metagraphA -> snapshotA), // Only A present
            lastCurrencySnapshotsProofs = SortedMap(metagraphA -> createMockProof()) // Missing B's proof
          )
          
          // Create a mock incremental snapshot with state proof computed from complete context
          keyPair <- securityProvider.keyPair
          
          // Build state proof from complete context
          completeStateProof <- completeContext.allStateEntries.flatMap(_.buildMpt).map { mptRoot =>
            GlobalSnapshotStateProof(mptRoot.hash, Hash.empty, Hash.empty, Hash.empty)
          }
          
          incrementalSnapshot = GlobalIncrementalSnapshot(
            ordinal = testOrdinal,
            lastSnapshotHash = Hash.empty,
            blocks = SortedSet.empty,
            stateChannelSnapshots = List.empty,
            stateProof = completeStateProof,
            epochProgress = EpochProgress.MinValue,
            nextFacilitators = SortedSet.empty,
            transactions = SortedSet.empty,
            events = SortedSet.empty,
            rewards = SortedSet.empty
          )
          
          signedSnapshot <- securityProvider.sign(incrementalSnapshot)(keyPair)
          
          // Test validation with complete context (should succeed)
          validator = StateProofValidator.forGlobal[IO]()
          
          completeValidation <- validator.validate(signedSnapshot, completeContext)
          
          // Test validation with incomplete context (should fail)  
          incompleteValidation <- validator.validate(signedSnapshot, incompleteContext)
          
          _ <- IO.println(s"Complete context validation: ${completeValidation}")
          _ <- IO.println(s"Incomplete context validation: ${incompleteValidation}")
          
        } yield {
          // The incomplete validation should fail, demonstrating Marcus's issue
          assert(completeValidation.isValid, "Validation with complete context should succeed")
          assert(incompleteValidation.isInvalid, "Validation with incomplete context should fail (reproducing the bug)")
        }
      }
    }
  }
}
package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.env.AppEnvironment.Mainnet
import io.constellationnetwork.node.shared.infrastructure.BalanceAdjustmentLoader
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyBalanceAdjustments.{
  AdjustmentType,
  RequiredAdjustment,
  metagraphsBalancesAdjustments
}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{BalanceAdjustment, FeeTransactionBugDeduction}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

object CurrencyBalanceAdjustmentsResourceSuite extends SimpleIOSuite {

  private val pacaswap = Address("DAG7X5idd4aLfp4XC6WQdG1eDfR3LGPVEwtUUB2W")

  private val adjustmentOrdinal = SnapshotOrdinal.unsafeApply(731647L)

  /** 2^62, the amount each of the four fee transactions in metagraph snapshot 731261 credited. */
  private val minted = 4611686018427387904L

  /** Balances observed when the metagraph was stopped. Two wallets still hold the full 2^62, two were partially spent, so the deduction has
    * to zero the exact and the drained case alike.
    */
  private val mintedWallets: List[(Address, Long)] = List(
    Address("DAG1kEmLAgnCVBURHrL4AMsfn9TZdk4QCYQ8tUu3") -> minted,
    Address("DAG7ZjENTP4T36PPSp3skJdTHtQbcuLfpEaAFWdn") -> minted,
    Address("DAG4w5mUqNNxQNS4hgdpx3E8FGgiu2UCRsJxHwhX") -> 4573023841357253629L,
    Address("DAG8uqhyGtFABWSS5KeVB2ia1R4vXop5AeijXeoU") -> 4111686018427387904L
  )

  /** The pool address. Phantom PACA the attacker swapped in piled up here on top of the reserve the pool held before the mint, and the
    * deduction takes that pile back off.
    */
  private val poolBalance = 360351876219858115L
  private val poolSurplus = 355312351884858115L

  /** Two of the ten addresses that bought phantom PACA out of the pool with real DAG, as (held, deducted). The deduction lands short of
    * zero on purpose: each keeps what their DAG would have bought at the pre-attack price, on top of any genuine pre-mint balance.
    */
  private val buyers: List[(Address, Long, Long)] = List(
    (Address("DAG6zZakMJrrf25FSvPZAi8QA9wVDdmvFkPvTbKu"), 68023006936021839L, 67978819470437886L),
    (Address("DAG1DD2bM1hpFyWwa8UNgh3wMLGAe5JDSwpoUS9M"), 77579706235110L, 76610032383686L)
  )

  /** An address no adjustment names, which has to come out of the fix byte for byte. */
  private val bystander = Address("DAG62QdFnvW8xX3uGmo6F3yB2CT5i25hZoVmN6za") -> 4200000000L

  /** Read straight out of the shipped resource rather than restated here, so this is the exact set the metagraph has to emit at 731647 for
    * the snapshot to be accepted.
    */
  private val requiredAdjustments: Set[RequiredAdjustment] =
    (for {
      jsonString <- BalanceAdjustmentLoader.readResourceFile("/adjustments.json")
      parsed <- BalanceAdjustmentLoader.parseJsonToModel(jsonString)
      byCurrency <- BalanceAdjustmentLoader.convertToBalanceAdjustments(parsed)
    } yield byCurrency.getOrElse(pacaswap, Set.empty[RequiredAdjustment]))
      .fold(error => throw new RuntimeException(error), identity)

  private def asArtifact(required: RequiredAdjustment): BalanceAdjustment =
    required.adjustment match {
      case AdjustmentType.Decrease(amount) =>
        BalanceAdjustment(required.address, FeeTransactionBugDeduction, SortedSet(Hash.empty), none, amount.some)
      case AdjustmentType.Increase(amount) =>
        BalanceAdjustment(required.address, FeeTransactionBugDeduction, SortedSet(Hash.empty), amount.some, none)
    }

  private val allDeductions: Set[BalanceAdjustment] = requiredAdjustments.map(asArtifact)

  private def deduction(address: Address, amount: Long): BalanceAdjustment =
    asArtifact(RequiredAdjustment(address, AdjustmentType.Decrease(Amount(NonNegLong.unsafeFrom(amount)))))

  private lazy val entryAt731647 =
    metagraphsBalancesAdjustments(pacaswap).find(_.snapshotOrdinal == adjustmentOrdinal).get

  private val balances: SortedMap[Address, Balance] =
    SortedMap.from(
      (mintedWallets ++ buyers.map { case (a, held, _) => a -> held } :+ (pacaswap -> poolBalance) :+ bystander).map {
        case (address, value) => address -> Balance(NonNegLong.unsafeFrom(value))
      }
    )

  // Every historical block remains active so replay can reproduce the balance transition at its ordinal.
  pureTest("the active Pacaswap entry is the fee-transaction deduction at ordinal 731647") {
    val entry = metagraphsBalancesAdjustments.get(pacaswap)

    expect.all(
      entry.isDefined,
      entry.exists(_.exists(_.snapshotOrdinal == adjustmentOrdinal)),
      entry.exists(_.exists(_.environment == Mainnet))
    )
  }

  pureTest("the live block covers the mint, the pool and the buyers, once each") {
    expect.all(
      requiredAdjustments.size == 17,
      requiredAdjustments.map(_.address).size == 17,
      requiredAdjustments.forall { required =>
        required.adjustment match {
          case AdjustmentType.Decrease(_) => true
          case AdjustmentType.Increase(_) => false
        }
      },
      mintedWallets.forall {
        case (address, _) =>
          requiredAdjustments.contains(
            RequiredAdjustment(address, AdjustmentType.Decrease(Amount(NonNegLong.unsafeFrom(minted))))
          )
      },
      requiredAdjustments.contains(
        RequiredAdjustment(pacaswap, AdjustmentType.Decrease(Amount(NonNegLong.unsafeFrom(poolSurplus))))
      )
    )
  }

  pureTest("the entry zeroes the minted wallets and takes the surplus off the pool") {
    val result = entryAt731647.balanceAdjustFunction(balances, allDeductions)

    result match {
      case Left(error) => failure(s"expected the adjustment to apply, got: $error")
      case Right(updated) =>
        expect.all(
          mintedWallets.forall { case (address, _) => updated.get(address).contains(Balance.empty) },
          updated.get(pacaswap).contains(Balance(NonNegLong.unsafeFrom(poolBalance - poolSurplus))),
          updated.get(bystander._1).contains(Balance(NonNegLong.unsafeFrom(bystander._2)))
        )
    }
  }

  pureTest("buyers keep the PACA they held before the mint") {
    val result = entryAt731647.balanceAdjustFunction(balances, allDeductions)

    result match {
      case Left(error) => failure(s"expected the adjustment to apply, got: $error")
      case Right(updated) =>
        expect(buyers.forall {
          case (address, held, deduct) =>
            updated.get(address).contains(Balance(NonNegLong.unsafeFrom(held - deduct)))
        })
    }
  }

  // If the metagraph fails to emit the full artifact set at 731647 the snapshot must not be produced,
  // rather than being accepted with a partial deduction.
  pureTest("the entry rejects an incomplete adjustment set") {
    val partial = allDeductions - deduction(mintedWallets.head._1, minted)
    val result = entryAt731647.balanceAdjustFunction(balances, partial)

    expect(result.isLeft)
  }

  // Amounts are matched exactly, so a rounded or rescaled figure on either side of the pair is
  // indistinguishable from a missing artifact.
  pureTest("the entry rejects an adjustment whose amount is off by one") {
    val skewed = allDeductions - deduction(pacaswap, poolSurplus) + deduction(pacaswap, poolSurplus - 1L)
    val result = entryAt731647.balanceAdjustFunction(balances, skewed)

    expect(result.isLeft)
  }

  pureTest("every historical adjustment block for a currency stays live, not just the last") {
    // convertToAdjustmentEntries used to end in .toMap, so the last block in the resource silently
    // retired every earlier block for the same currency. Replaying one of those ordinals then applied
    // no adjustment and diverged without raising, and a follow-up block could not be scheduled.
    val blocks = metagraphsBalancesAdjustments.getOrElse(pacaswap, List.empty)
    val ordinals = blocks.map(_.snapshotOrdinal.value.value).toSet

    expect.all(
      blocks.size >= 4,
      ordinals.contains(109991L),
      ordinals.contains(145000L),
      ordinals.contains(472325L),
      ordinals.contains(adjustmentOrdinal.value.value),
      ordinals.size == blocks.size
    )
  }

  pureTest("the entry rejects an adjustment that is not in the authorized set") {
    // validateRequiredAdjustments used to check only that the required set was a subset of what the
    // metagraph emitted, so a metagraph could emit the authorized deductions plus arbitrary extras and
    // every one of them would be applied. The resource is the authorization boundary, so the emitted
    // set has to match it exactly.
    // An address nowhere in the authorized set, with a deduction nobody approved.
    val extra = deduction(Address("DAG6zZakMJrrf25FSvPZAi8QA9wVDdmvFkPvTbKu"), 999999999L)

    val result = entryAt731647.balanceAdjustFunction(balances, allDeductions + extra)

    expect(result.isLeft) &&
    expect(result.left.exists(_.toLowerCase.contains("unauthorized")))
  }
}

package io.constellationnetwork.currency.l0.snapshot

import cats.effect.{IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.storage.CurrencyFeeContextReceiptStorage
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Files
import weaver.SimpleIOSuite

object CurrencySnapshotConsensusFunctionsSuite extends SimpleIOSuite {

  private val ordinal = SnapshotOrdinal.unsafeApply(354L)
  private val expectedHash = Hash("expected")
  private val view = GlobalSyncView(ordinal, expectedHash, EpochProgress.MinValue)
  private val currencyOrdinal = SnapshotOrdinal.unsafeApply(77L)
  private val artifactHash = Hash("currency-artifact")
  private val stakingAddress = Address.fromBytes("staking-address".getBytes("UTF-8"))
  private val stakingBalance = Balance(NonNegLong.unsafeFrom(123L))

  test("proposal validation durably captures the exact fee input") {
    for {
      retained <- Ref.of[IO, Option[CurrencyFeeContextReceiptStorage.CurrencyFeeContextReceipt]](none)
      _ <- CurrencySnapshotConsensusFunctions.captureFeeContextReceipt[IO](
        currencyOrdinal,
        artifactHash,
        view.some,
        stakingAddress.some,
        _ => (expectedHash -> stakingBalance).some.pure[IO],
        receipt => retained.set(receipt.some)
      )
      observed <- retained.get
    } yield
      expect(
        observed.exists(receipt =>
          receipt.currencyOrdinal === currencyOrdinal &&
            receipt.currencyArtifactHash === artifactHash &&
            receipt.globalSyncView === view &&
            receipt.stakingAddress.contains(stakingAddress) &&
            receipt.stakingBalance === stakingBalance
        )
      )
  }

  test("proposal fee context capture fails when the selected Global state is unavailable") {
    CurrencySnapshotConsensusFunctions
      .captureFeeContextReceipt[IO](
        currencyOrdinal,
        artifactHash,
        view.some,
        stakingAddress.some,
        _ => IO.pure(none),
        _ => IO.unit
      )
      .attempt
      .map(result => expect(result == Left(CurrencySnapshotConsensusFunctions.ExactGlobalFeeContextUnavailable(ordinal))))
  }

  test("temporary Global context unavailability invalidates only the proposal") {
    CurrencySnapshotConsensusFunctions
      .validateFeeContextCapture[IO](
        CurrencySnapshotConsensusFunctions.captureFeeContextReceipt[IO](
          currencyOrdinal,
          artifactHash,
          view.some,
          stakingAddress.some,
          _ => IO.pure(none),
          _ => IO.unit
        )
      )
      .map(result => expect.same(Left(CurrencySnapshotConsensusFunctions.ExactGlobalFeeContextUnavailable(ordinal)), result))
  }

  test("proposal fee context capture fails on a same-ordinal Global hash conflict") {
    val actualHash = Hash("conflicting")

    CurrencySnapshotConsensusFunctions
      .captureFeeContextReceipt[IO](
        currencyOrdinal,
        artifactHash,
        view.some,
        stakingAddress.some,
        _ => IO.pure((actualHash -> stakingBalance).some),
        _ => IO.unit
      )
      .attempt
      .map(result =>
        expect(
          result == Left(
            CurrencySnapshotConsensusFunctions.ExactGlobalFeeContextHashMismatch(ordinal, expectedHash, actualHash)
          )
        )
      )
  }

  test("proposal fee context capture fails closed without a Global sync view") {
    for {
      calls <- Ref.of[IO, Int](0)
      result <- CurrencySnapshotConsensusFunctions
        .validateFeeContextCapture[IO](
          CurrencySnapshotConsensusFunctions.captureFeeContextReceipt[IO](
            currencyOrdinal,
            artifactHash,
            none,
            stakingAddress.some,
            _ => calls.update(_ + 1).as((expectedHash -> stakingBalance).some),
            _ => calls.update(_ + 1)
          )
        )
      observed <- calls.get
    } yield
      expect.same(Left(CurrencySnapshotConsensusFunctions.MissingGlobalSyncViewForFeeContext(currencyOrdinal)), result) &&
        expect.same(0, observed)
  }

  test("same-ordinal Global hash conflicts remain hard failures during proposal validation") {
    val actualHash = Hash("conflicting")

    CurrencySnapshotConsensusFunctions
      .validateFeeContextCapture[IO](
        CurrencySnapshotConsensusFunctions.captureFeeContextReceipt[IO](
          currencyOrdinal,
          artifactHash,
          view.some,
          stakingAddress.some,
          _ => IO.pure((actualHash -> stakingBalance).some),
          _ => IO.unit
        )
      )
      .attempt
      .map(result =>
        expect.same(
          Left(CurrencySnapshotConsensusFunctions.ExactGlobalFeeContextHashMismatch(ordinal, expectedHash, actualHash)),
          result
        )
      )
  }

  test("the oldest of twelve proposal views remains usable after normal Global history is pruned") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        storage <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        proposals = (1 to 12).toList.map { index =>
          val globalOrdinal = SnapshotOrdinal.unsafeApply(1000L + index)
          val globalHash = Hash(s"global-$index")
          val proposalHash = Hash(s"proposal-$index")
          val proposalBalance = Balance(NonNegLong.unsafeFrom(index.toLong))
          (GlobalSyncView(globalOrdinal, globalHash, EpochProgress.MinValue), proposalHash, proposalBalance)
        }
        normalGlobalHistory <- Ref.of[IO, Map[SnapshotOrdinal, (Hash, Balance)]](
          proposals.map {
            case (proposalView, _, proposalBalance) =>
              proposalView.ordinal -> (proposalView.hash -> proposalBalance)
          }.toMap
        )
        _ <- proposals.traverse_ {
          case (proposalView, proposalHash, _) =>
            CurrencySnapshotConsensusFunctions.captureFeeContextReceipt[IO](
              currencyOrdinal,
              proposalHash,
              proposalView.some,
              stakingAddress.some,
              requested => normalGlobalHistory.get.map(_.get(requested)),
              receipt => storage.putDurably(receipt).void
            )
        }
        _ <- normalGlobalHistory.set(Map.empty)
        restarted <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        (oldestView, oldestHash, oldestBalance) = proposals.head
        oldestKey = CurrencyFeeContextReceiptStorage.CurrencyFeeContextKey(currencyOrdinal, oldestHash)
        _ <- restarted.retainSelected(oldestKey)
        restored <- restarted.get(oldestKey)
      } yield
        expect(
          restored.exists(receipt =>
            receipt.globalSyncView === oldestView &&
              receipt.stakingAddress.contains(stakingAddress) &&
              receipt.stakingBalance === oldestBalance
          )
        )
    }
  }
}

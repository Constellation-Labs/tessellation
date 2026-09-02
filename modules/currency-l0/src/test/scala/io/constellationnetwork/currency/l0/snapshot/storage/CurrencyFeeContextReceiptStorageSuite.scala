package io.constellationnetwork.currency.l0.snapshot.storage

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.storage.CurrencyFeeContextReceiptStorage.CurrencyFeeContextReceipt
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

object CurrencyFeeContextReceiptStorageSuite extends SimpleIOSuite {

  private val stakingAddress = Address.fromBytes("fee-receipt-staking".getBytes("UTF-8"))

  private def receipt(currencyOrdinal: Long, proposal: Int): CurrencyFeeContextReceipt =
    CurrencyFeeContextReceipt(
      CurrencyFeeContextReceiptStorage.CurrentEncodingVersion,
      SnapshotOrdinal.unsafeApply(currencyOrdinal),
      Hash(s"artifact-$proposal"),
      GlobalSyncView(
        SnapshotOrdinal.unsafeApply(1000L + proposal),
        Hash(s"global-$proposal"),
        EpochProgress.MinValue
      ),
      stakingAddress.some,
      Balance(NonNegLong.unsafeFrom(proposal.toLong))
    )

  test("twelve facilitator proposal receipts survive restart and the oldest can be selected") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        storage <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        proposals = (1 to 12).toList.map(receipt(50L, _))
        _ <- proposals.traverse_(storage.putDurably)
        restarted <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        beforeSelection <- restarted.list
        selected <- restarted.retainSelected(proposals.head.key)
        afterSelection <- restarted.list
      } yield
        expect.all(
          beforeSelection.toSet === proposals.toSet,
          selected === proposals.head,
          afterSelection === List(proposals.head)
        )
    }
  }

  test("a durable receipt remains available after restart before binary construction") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        storage <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        expected = receipt(60L, 1)
        _ <- storage.putDurably(expected)
        restarted <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        restored <- restarted.get(expected.key)
      } yield expect.same(expected.some, restored)
    }
  }

  test("receipts are released only after selection, durable completion, or abandonment") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        storage <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        selected = receipt(70L, 1)
        rejected = receipt(70L, 2)
        abandoned = receipt(71L, 3)
        _ <- List(selected, rejected, abandoned).traverse_(storage.putDurably)
        beforeSelection <- storage.list
        _ <- storage.retainSelected(selected.key)
        afterSelection <- storage.list
        _ <- storage.complete(selected.key)
        afterCompletion <- storage.list
        _ <- storage.abandonGeneration(abandoned.currencyOrdinal)
        afterAbandonment <- storage.list
      } yield
        expect.all(
          beforeSelection.toSet === Set(selected, rejected, abandoned),
          afterSelection.toSet === Set(selected, abandoned),
          afterCompletion === List(abandoned),
          afterAbandonment.isEmpty
        )
    }
  }

  test("startup sweep preserves pending publication receipts and active generations") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        storage <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        completed = receipt(80L, 1)
        protectedPublication = receipt(81L, 2)
        activeGeneration = receipt(82L, 3)
        _ <- List(completed, protectedPublication, activeGeneration).traverse_(storage.putDurably)
        removed <- storage.sweepCompleted(
          SnapshotOrdinal.unsafeApply(81L),
          Set(protectedPublication.key)
        )
        restarted <- CurrencyFeeContextReceiptStorage.make[IO](directory)
        remaining <- restarted.list
      } yield
        expect.all(
          removed === List(completed.key),
          remaining.toSet === Set(protectedPublication, activeGeneration)
        )
    }
  }
}

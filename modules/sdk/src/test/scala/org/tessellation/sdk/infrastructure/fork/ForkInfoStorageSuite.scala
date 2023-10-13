package org.tessellation.sdk.infrastructure.fork

import cats.effect.{IO, Ref}
import cats.syntax.traverse._

import org.tessellation.sdk.config.types.ForkInfoStorageConfig
import org.tessellation.sdk.domain.fork.{ForkInfoEntries, ForkInfoMap, ForkInfoStorage}
import org.tessellation.sdk.infrastructure.fork.generators.genStoredForkInfoEntry

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.numeric.PosInt
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ForkInfoStorageSuite extends SimpleIOSuite with Checkers {

  def mkForkInfoStorage(entries: ForkInfoMap, queueSize: PosInt): F[ForkInfoStorage[F]] = {
    val config = ForkInfoStorageConfig(queueSize)

    Ref[IO].of(entries).map(ForkInfoStorage.make(_, config))
  }

  test("successfully add when there is capacity") {
    val gen = for {
      storageSize <- Gen.chooseNum(2, 10)
      refinedStorageSize = Refined.unsafeApply[Int, Positive](storageSize)

      entryCount <- Gen.chooseNum(0, storageSize - 1)
      entries <- Gen.listOfN(entryCount, genStoredForkInfoEntry)
    } yield (entries, refinedStorageSize)

    forall(gen) {
      case (entries, storageSize) =>
        for {
          store <- mkForkInfoStorage(ForkInfoMap.empty, storageSize)
          _ <- entries.traverse { case (peerId, info) => store.add(peerId, info) }

          expected = ForkInfoMap(
            entries.groupMap { case (peerId, _) => peerId } { case (_, info) => info }.view.mapValues {
              ForkInfoEntries.from(storageSize, _)
            }.toMap
          )
          actual <- store.getForkInfo
        } yield expect.eql(expected, actual)
    }
  }

  test("add when at capacity ejects the first queue entry") {
    val gen = for {
      size <- Gen.chooseNum(1, 10)
      entries <- Gen.listOfN(size, genStoredForkInfoEntry)
      queueSize = Refined.unsafeApply[Int, Positive](size)
      newEntry <- genStoredForkInfoEntry
    } yield (entries, queueSize, newEntry)

    forall(gen) {
      case (entries, storageSize, entry) =>
        val forkInfoMap = ForkInfoMap(
          entries.groupMap { case (peerId, _) => peerId } { case (_, info) => info }.view.mapValues {
            ForkInfoEntries.from(storageSize, _)
          }.toMap
        )

        for {
          store <- mkForkInfoStorage(forkInfoMap, storageSize)

          peerId = entry._1
          info = entry._2
          _ <- store.add(peerId, info)

          update = peerId -> forkInfoMap.forks
            .getOrElse(
              peerId,
              ForkInfoEntries(storageSize)
            )
            .add(info)
          expected = ForkInfoMap(forkInfoMap.forks + update)
          actual <- store.getForkInfo
        } yield expect.eql(expected, actual)
    }
  }

}

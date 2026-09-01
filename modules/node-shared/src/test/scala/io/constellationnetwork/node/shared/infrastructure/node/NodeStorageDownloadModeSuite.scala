package io.constellationnetwork.node.shared.infrastructure.node

import cats.effect.IO

import io.constellationnetwork.node.shared.domain.node.DownloadMode

import weaver.SimpleIOSuite

object NodeStorageDownloadModeSuite extends SimpleIOSuite {

  test("download modes are mutually exclusive and clear back to the full path") {
    for {
      storage <- NodeStorage.make[IO]
      initial <- storage.getDownloadMode
      _ <- storage.setRecoveryDownload
      recovery <- storage.getDownloadMode
      _ <- storage.setFollowerCatchUpDownload
      follower <- storage.getDownloadMode
      legacyRecoveryFlag <- storage.isRecoveryDownload
      _ <- storage.clearRecoveryDownload
      cleared <- storage.getDownloadMode
      clearedLegacyFlag <- storage.isRecoveryDownload
    } yield
      expect.same(DownloadMode.Full, initial) &&
        expect.same(DownloadMode.Recovery, recovery) &&
        expect.same(DownloadMode.FollowerCatchUp, follower) &&
        expect(legacyRecoveryFlag) &&
        expect.same(DownloadMode.Full, cleared) &&
        expect(!clearedLegacyFlag)
  }
}

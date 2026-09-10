package io.constellationnetwork.dag.l0.domain.snapshot.programs

import cats.effect.IO
import cats.effect.testkit.TestControl

import scala.concurrent.duration._

import weaver.SimpleIOSuite

object DownloadInactivityWatchdogSuite extends SimpleIOSuite {

  test("permits a long recovery replay that keeps advancing") {
    val replay = Download.withInactivityTimeout[IO, Int](10.minutes, 1.minute) { touch =>
      touch >> IO.sleep(9.minutes) >> touch >> IO.sleep(9.minutes) >> touch.as(42)
    }

    TestControl.executeEmbed(replay).map(expect.same(42, _))
  }

  test("interrupts a recovery fiber only after progress stops") {
    val stalled = Download.withInactivityTimeout[IO, Unit](10.minutes, 1.minute) { touch =>
      touch >> IO.never[Unit]
    }

    TestControl
      .executeEmbed(stalled.attempt)
      .map(result => expect(result.swap.contains(Download.DownloadStartTimedOut)))
  }

  // The catch-up profile that wedged testnet gl0, pinned against the watchdog.
  //
  // gl0 sat at ordinal 3271071 while the cluster tip was 3276854 -- 5,783 snapshots behind. The full
  // download path fetched at ~4.4 snapshots/s, so a complete catch-up needs ~22 minutes of
  // continuous work, well past its 10-minute budget. Because that path used a fixed
  // `timeoutTo(start, downloadStartMaxDuration, ...)` instead of this watchdog, each attempt was
  // cancelled mid-walk and the next one re-walked the same ground: ~2,600 snapshots fetched per
  // attempt for ~265 net persisted ordinals. Progress was retained across attempts (3271071 ->
  // 3272926 over 7 of them), so the cap cost throughput rather than correctness. Both cases below
  // assert the property that removes the waste: the budget must bound IDLENESS, not total elapsed
  // time.
  //
  // SCOPE: these exercise the watchdog combinator against that profile, NOT the full path's wiring
  // to it -- `Download.make` takes ~15 collaborators and DownloadSuite has no harness for it, so
  // "guardedStart actually calls withInactivityTimeout" is covered by review, not by this suite.
  // They therefore also pass against the unfixed wiring; they exist to keep the timing contract
  // from regressing underneath it.
  private val missingSnapshots = 5783
  private val perSnapshot = 227.millis // ~4.4/s, the rate observed on testnet

  test("the testnet catch-up profile outlives a budget shorter than its total run") {
    val catchUp = Download.withInactivityTimeout[IO, Int](10.minutes, 30.seconds) { touch =>
      // Stands in for the onProgress callback the full path threads into downloadWithProgress,
      // which fires per walk_back/validateChain ordinal.
      def fetch(remaining: Int): IO[Unit] =
        if (remaining <= 0) IO.unit
        else IO.sleep(perSnapshot) >> touch >> fetch(remaining - 1)

      fetch(missingSnapshots).as(missingSnapshots)
    }

    // Total run is ~21.9 minutes against a 10-minute budget: it may only complete because no single
    // gap between snapshots approaches the budget.
    TestControl.executeEmbed(catchUp).map(expect.same(missingSnapshots, _))
  }

  test("the testnet catch-up profile still times out once it stalls") {
    val stalledMidway = Download.withInactivityTimeout[IO, Int](10.minutes, 30.seconds) { touch =>
      def fetch(remaining: Int): IO[Unit] =
        if (remaining <= 0) IO.unit
        else IO.sleep(perSnapshot) >> touch >> fetch(remaining - 1)

      // Fetches a while, then hangs: the hung-fiber protection must still fire.
      fetch(100) >> IO.never[Int]
    }

    TestControl
      .executeEmbed(stalledMidway.attempt)
      .map(result => expect(result.swap.contains(Download.DownloadStartTimedOut)))
  }

  // The initial cleanup pass reports nothing until it finishes, so on a store whose bodies are not
  // hardlinked to their ordinal index it outlasts the idle budget and the download never begins:
  // observed on testnet gl0, where 1,494,018 of 2,552,100 persisted bodies had nlink == 1 and were
  // decoded rather than skipped, taking ~45 minutes against a 10-minute budget.
  private val silentCleanup = 45.minutes

  test("a silent local pass outlasting the budget survives while it heartbeats") {
    val guarded = Download.withInactivityTimeout[IO, Int](10.minutes, 1.minute) { touch =>
      Download.withProgressHeartbeat(15.seconds, touch)(IO.sleep(silentCleanup).as(7))
    }

    TestControl.executeEmbed(guarded).map(expect.same(7, _))
  }

  test("the same silent pass times out without a heartbeat") {
    val guarded = Download.withInactivityTimeout[IO, Int](10.minutes, 1.minute) { _ =>
      IO.sleep(silentCleanup).as(7)
    }

    TestControl
      .executeEmbed(guarded.attempt)
      .map(result => expect(result.swap.contains(Download.DownloadStartTimedOut)))
  }

  // The heartbeat must not outlive the pass it guards, or it would keep a genuinely wedged fetch
  // alive for as long as the download ran -- removing the protection the budget exists to give.
  test("the heartbeat ends with its pass, so a later stall still times out") {
    val guarded = Download.withInactivityTimeout[IO, Unit](10.minutes, 1.minute) { touch =>
      Download.withProgressHeartbeat(15.seconds, touch)(IO.sleep(silentCleanup)) >> IO.never[Unit]
    }

    TestControl
      .executeEmbed(guarded.attempt)
      .map(result => expect(result.swap.contains(Download.DownloadStartTimedOut)))
  }

}

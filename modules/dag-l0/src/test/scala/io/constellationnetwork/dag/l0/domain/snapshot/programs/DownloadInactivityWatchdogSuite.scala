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
}

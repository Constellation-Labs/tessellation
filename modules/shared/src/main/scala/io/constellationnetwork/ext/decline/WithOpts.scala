package io.constellationnetwork.ext.decline

import com.monovore.decline.Opts

trait WithOpts[A] {
  val opts: Opts[A]
}

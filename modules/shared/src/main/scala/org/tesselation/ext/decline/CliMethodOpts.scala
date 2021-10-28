package org.tesselation.ext.decline

import com.monovore.decline.Opts

trait CliMethodOpts[A] {
  val opts: Opts[A]
}

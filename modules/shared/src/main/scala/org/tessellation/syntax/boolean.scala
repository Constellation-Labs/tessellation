package org.tessellation.syntax

object boolean {

  implicit class BooleanOps(value: Boolean) {

    def ==>(b: => Boolean): Boolean =
      !value || b
  }

}

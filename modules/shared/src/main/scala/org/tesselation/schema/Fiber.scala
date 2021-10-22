package org.tesselation.schema

trait Fiber[A, B] {
  def parent: A
  def reference: B
}

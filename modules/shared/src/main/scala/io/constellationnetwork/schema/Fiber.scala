package io.constellationnetwork.schema

trait Fiber[A, B] {
  def reference: A
  def data: B
}

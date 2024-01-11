package org.tessellation.security

import cats.Show
import cats.kernel.Order
import cats.syntax.contravariant._
import cats.syntax.show._

import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed

case class Hashed[+A](signed: Signed[A], hash: Hash, proofsHash: ProofsHash)

object Hashed {
  implicit def autoUnwrap[T](t: Hashed[T]): T = t.signed

  implicit def order[A: Order]: Order[Hashed[A]] = Order.fromOrdering(ordering(Order[A].toOrdering))

  implicit def ordering[A: Ordering]: Ordering[Hashed[A]] = new HashedOrdering[A]()

  implicit def show[A: Show]: Show[Hashed[A]] =
    h => s"Hashed(signed=${h.signed.show}, hash=${h.hash.show}, proofsHash=${h.proofsHash.show})"
}

final class HashedOrdering[A: Ordering] extends Ordering[Hashed[A]] {

  def compare(x: Hashed[A], y: Hashed[A]): Int =
    Order
      .whenEqual(
        Order.fromOrdering(Ordering[A]).contramap[Hashed[A]](h => h.signed),
        Order[Hash].contramap[Hashed[A]](h => h.hash)
      )
      .compare(x, y)
}

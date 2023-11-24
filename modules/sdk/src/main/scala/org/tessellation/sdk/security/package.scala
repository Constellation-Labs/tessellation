package org.tessellation.sdk

import cats.effect.{Async, Resource}

/** Provides security-related functionalities.
  */
package object security {

  /** Represents a security provider abstraction. It is essentially a wrapper around Java's security providers.
    */
  type SecurityProvider[F[_]] = org.tessellation.security.SecurityProvider[F]

  object SecurityProvider {

    /** Retrieves an implicit instance of `SecurityProvider` for the specified effect type.
      *
      * This method is typically used to summon an implicit `SecurityProvider` instance in a scope where it's implicitly available.
      * @tparam F
      *   The effect type for which to retrieve the `SecurityProvider`.
      * @return
      *   The implicit `SecurityProvider` instance for the effect type `F`.
      */
    def apply[F[_]: SecurityProvider]: SecurityProvider[F] = implicitly

    /** Provides a `Resource` for `SecurityProvider` which operates on an effect type `F`.
      *
      * This method creates a `Resource` that manages the lifecycle of a `SecurityProvider`. The `SecurityProvider` is initialized and
      * disposed of properly, ensuring resource safety. The is particularly important when dealing with cryptographic operations and their
      * associated resources.
      *
      * @tparam F
      *   The effect type, which must have an `Async` instance.
      * @return
      *   A `Resource[F, SecurityProvider[F]]` that manages the `SecurityProvider`.
      */
    def forAsync[F[_]: Async]: Resource[F, SecurityProvider[F]] =
      org.tessellation.security.SecurityProvider.forAsync
  }
}

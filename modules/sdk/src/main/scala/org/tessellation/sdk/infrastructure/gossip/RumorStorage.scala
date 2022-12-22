package org.tessellation.sdk.infrastructure.gossip

import cats.Applicative
import cats.data.{Chain, NonEmptyChain}
import cats.effect.{Async, Ref}
import cats.syntax.all._

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.Hashed
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.signature.Signed
import org.tessellation.sdk.config.types.RumorStorageConfig
import org.tessellation.sdk.infrastructure.gossip.RumorStorage.AddResult

import derevo.cats.{order, show}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.chrisdavenport.mapref.MapRef

trait RumorStorage[F[_]] {

  def getLastPeerOrdinals: F[Map[PeerId, Ordinal]]

  def getLastPeerRumors: F[Chain[Signed[PeerRumorRaw]]]

  def getPeerIds: F[Set[PeerId]]

  def getPeerRumorsFromCursor(peerId: PeerId, fromOrdinal: Ordinal): F[Chain[Signed[PeerRumorRaw]]]

  def addPeerRumorIfConsecutive(rumor: Signed[PeerRumorRaw]): F[AddResult]

  def setInitialPeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit]

  def getCommonRumorActiveHashes: F[Set[Hash]]

  def getCommonRumorSeenHashes: F[Set[Hash]]

  def getCommonRumors(hashes: Set[Hash]): F[Chain[Signed[CommonRumorRaw]]]

  def addCommonRumorIfUnseen(rumor: Hashed[CommonRumorRaw]): F[Boolean]

  def setInitialCommonRumorSeenHashes(hashes: Set[Hash]): F[Unit]

}

object RumorStorage {

  @derive(order, show)
  sealed trait AddResult
  case object AddSuccess extends AddResult
  case object CounterTooHigh extends AddResult
  case object CounterTooLow extends AddResult
  case object GenerationTooLow extends AddResult

  case class NonEmptyChainWrapper[A](chain: NonEmptyChain[A], size: PosLong)

  object NonEmptyChainWrapper {
    def one[A](a: A): NonEmptyChainWrapper[A] = NonEmptyChainWrapper(NonEmptyChain.one(a), 1L)
  }

  implicit class NonEmptyChainWrapperOps[A](value: NonEmptyChainWrapper[A]) {
    def prependInitLast(a: A, n: PosLong): (NonEmptyChainWrapper[A], Option[A]) =
      if (value.size < n)
        (NonEmptyChainWrapper(value.chain.prepend(a), value.size |+| 1L), none[A])
      else
        value.chain.initLast match {
          case (init, last) =>
            (NonEmptyChainWrapper(NonEmptyChain.fromChainPrepend(a, init), value.size), last.some)
        }

    def prependInit(a: A, n: PosLong): NonEmptyChainWrapper[A] =
      prependInitLast(a, n)._1
  }

  case class ChainWrapper[A](chain: Chain[A], size: NonNegLong)

  object ChainWrapper {
    def one[A](a: A): ChainWrapper[A] = ChainWrapper(Chain.one(a), 1L)
    def empty[A]: ChainWrapper[A] = ChainWrapper(Chain.empty, 0L)
  }

  implicit class ChainWrapperOps[A](value: ChainWrapper[A]) {
    def prependInitLast(a: A, n: NonNegLong): (ChainWrapper[A], Option[A]) =
      if (value.size < n)
        (ChainWrapper(value.chain.prepend(a), value.size |+| 1L), none[A])
      else {
        value.chain.initLast match {
          case Some((init, last)) =>
            (ChainWrapper(init.prepend(a), value.size), last.some)
          case None =>
            if (n === 0L)
              (ChainWrapper.empty, none)
            else
              (ChainWrapper.one(a), none)
        }
      }

    def prependInit(a: A, n: NonNegLong): ChainWrapper[A] =
      prependInitLast(a, n)._1
  }

  case class CommonRumors(
    seenChain: ChainWrapper[Hash],
    seenSet: Set[Hash],
    activeChain: ChainWrapper[Hashed[CommonRumorRaw]],
    activeSet: Set[Hash]
  )

  object CommonRumors {
    val empty: CommonRumors = CommonRumors(ChainWrapper.empty, Set.empty, ChainWrapper.empty, Set.empty)
  }

  def make[F[_]: Async](cfg: RumorStorageConfig): F[RumorStorage[F]] =
    for {
      peersR <- Ref.of(Set.empty[PeerId])
      peerRumorsR <- MapRef.ofConcurrentHashMap[F, PeerId, NonEmptyChainWrapper[Signed[PeerRumorRaw]]]()
      commonRumorsR <- Ref.of(CommonRumors.empty)
    } yield
      new RumorStorage[F] {
        def getLastPeerOrdinals: F[Map[PeerId, Ordinal]] = peerRumorsR.keys.flatMap { peerIds =>
          peerIds.flatTraverse { peerId =>
            peerRumorsR(peerId).get.map { maybeWrapper =>
              maybeWrapper.map(peerId -> _.chain.head.ordinal).toList
            }
          }.map(_.toMap)
        }

        def getLastPeerRumors: F[Chain[Signed[PeerRumorRaw]]] = peerRumorsR.keys.flatMap { peerIds =>
          peerIds.flatTraverse { peerId =>
            peerRumorsR(peerId).get.map { maybeWrapper =>
              maybeWrapper.map(_.chain.head).toList
            }
          }.map(Chain.fromSeq(_))
        }

        def getPeerIds: F[Set[PeerId]] = peersR.get

        def getPeerRumorsFromCursor(peerId: PeerId, cursor: Ordinal): F[Chain[Signed[PeerRumorRaw]]] =
          peerRumorsR(peerId).get.map { maybeWrapper =>
            val Ordinal(cursorGen, cursorCounter) = cursor

            maybeWrapper.map { wrapper =>
              val headGen = wrapper.chain.head.ordinal.generation
              val lastCounter = wrapper.chain.last.ordinal.counter

              if (headGen === cursorGen)
                if (lastCounter <= cursorCounter)
                  wrapper.chain.toChain.takeWhile(_.ordinal.counter >= cursorCounter)
                else
                  Chain.empty
              else if (headGen > cursorGen)
                if (lastCounter === Counter.MinValue)
                  wrapper.chain.toChain
                else
                  Chain.empty
              else
                Chain.empty
            }
              .map(_.reverse)
              .getOrElse(Chain.empty[Signed[PeerRumorRaw]])
          }

        def addPeerRumorIfConsecutive(rumor: Signed[PeerRumorRaw]): F[AddResult] =
          peerRumorsR(rumor.origin).modify { maybeWrapper =>
            val Ordinal(rumorGen, rumorCounter) = rumor.ordinal
            val unit = Applicative[F].unit

            maybeWrapper.map { wrapper =>
              val Ordinal(headGen, headCounter) = wrapper.chain.head.ordinal

              if (headGen === rumorGen)
                if (headCounter.next === rumorCounter)
                  (wrapper.prependInit(rumor, cfg.peerRumorsCapacity), (AddSuccess, unit))
                else if (headCounter.next > rumorCounter)
                  (wrapper, (CounterTooLow, unit))
                else
                  (wrapper, (CounterTooHigh, unit))
              else if (headGen < rumorGen)
                if (rumorCounter === Counter.MinValue)
                  (NonEmptyChainWrapper.one(rumor), (AddSuccess, unit))
                else
                  (wrapper, (CounterTooHigh, unit))
              else
                (wrapper, (GenerationTooLow, unit))
            }.map {
              case (wrapper, resultAndEffect) => (wrapper.some, resultAndEffect)
            }.getOrElse {
              if (rumorCounter === Counter.MinValue)
                (NonEmptyChainWrapper.one(rumor).some, (AddSuccess, peersR.update(_.incl(rumor.origin))))
              else
                (none, (CounterTooHigh, unit))
            }
          }.flatMap {
            case (result, effect) => effect.as(result)
          }

        def setInitialPeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit] =
          peerRumorsR(rumor.origin).set(NonEmptyChainWrapper.one(rumor).some) >> peersR.update(_.incl(rumor.origin))

        def getCommonRumorActiveHashes: F[Set[Hash]] =
          commonRumorsR.get.map(_.activeSet)

        def getCommonRumorSeenHashes: F[Set[Hash]] =
          commonRumorsR.get.map(_.seenSet)

        def getCommonRumors(hashes: Set[Hash]): F[Chain[Signed[CommonRumorRaw]]] =
          commonRumorsR.get.map { commonRumors =>
            commonRumors.activeChain.chain.filter(h => hashes.contains(h.hash)).reverse.map(_.signed)
          }

        def addCommonRumorIfUnseen(rumor: Hashed[CommonRumorRaw]): F[Boolean] =
          commonRumorsR.modify {
            case o @ CommonRumors(seenChain, seenSet, activeChain, activeSet) =>
              if (seenSet.contains(rumor.hash))
                (o, false)
              else {
                val (seenChainUpdated, last) = seenChain.prependInitLast(rumor.hash, cfg.seenCommonRumorsCapacity)
                val seenSetUpdated = last.map(h => seenSet.excl(h)).getOrElse(seenSet).incl(rumor.hash)

                val (activeChainUpdated, removedRumor) = activeChain.prependInitLast(rumor, cfg.activeCommonRumorsCapacity)
                val activeSetUpdated = removedRumor.map(r => activeSet.excl(r.hash)).getOrElse(activeSet).incl(rumor.hash)
                (
                  CommonRumors(
                    seenChain = seenChainUpdated,
                    seenSet = seenSetUpdated,
                    activeChain = activeChainUpdated,
                    activeSet = activeSetUpdated
                  ),
                  true
                )
              }
          }

        def setInitialCommonRumorSeenHashes(hashes: Set[Hash]): F[Unit] =
          commonRumorsR.set(
            CommonRumors(
              seenChain = ChainWrapper(Chain.fromSeq(hashes.toSeq), NonNegLong.unsafeFrom(hashes.size.toLong)),
              seenSet = hashes,
              activeChain = ChainWrapper.empty,
              activeSet = Set.empty
            )
          )
      }

}

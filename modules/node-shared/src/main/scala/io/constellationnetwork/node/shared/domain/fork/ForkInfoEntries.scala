package io.constellationnetwork.node.shared.domain.fork

import cats.syntax.eq._
import cats.{Eq, Show}

import scala.collection.immutable.Queue

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

sealed trait ForkInfoEntries {
  def add(entry: ForkInfo): ForkInfoEntries
  def getEntries: Iterable[ForkInfo]
}

object ForkInfoEntries {
  implicit val eq: Eq[ForkInfoEntries] = Eq.instance[ForkInfoEntries] { (e1, e2) =>
    e1.getEntries.toSeq === e2.getEntries.toSeq
  }

  implicit val show: Show[ForkInfoEntries] = Show.show[ForkInfoEntries] { e =>
    e.getEntries.mkString("[", ",", "]")
  }

  private case class ForkInfoEntriesImpl(maxSize: PosInt, value: Queue[ForkInfo]) extends ForkInfoEntries {
    def add(entry: ForkInfo): ForkInfoEntries = {
      val updated =
        if (value.size === maxSize)
          value.dequeue._2.enqueue(entry)
        else
          value.enqueue(entry)

      copy(value = updated)
    }

    def getEntries: Iterable[ForkInfo] = value
  }

  def apply(maxSize: PosInt): ForkInfoEntries = ForkInfoEntriesImpl(maxSize, Queue.empty)

  def from(maxSize: PosInt, entries: Iterable[ForkInfo]): ForkInfoEntries =
    ForkInfoEntriesImpl(maxSize, Queue.from(entries.takeRight(maxSize)))

}

package org.tessellation

import cats.Show

object Log {
  private def log[A](color: String)(a: A): Unit = println(s"${color}${a.toString}${Console.RESET}")

  val red = log(Console.RED) _
  val green = log(Console.GREEN) _
  val blue = log(Console.BLUE) _
  val cyan = log(Console.CYAN) _
  val yellow = log(Console.YELLOW) _
  val magenta = log(Console.MAGENTA) _
  val white = log(Console.WHITE) _

  def logNode[A](node: Node): A => Unit = node.id match {
    case "nodeA" => green
    case "nodeB" => cyan
    case "nodeC" => yellow
    case "nodeD" => blue
    case _       => red
  }
}

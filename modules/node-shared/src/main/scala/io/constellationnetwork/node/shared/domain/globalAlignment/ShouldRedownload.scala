package io.constellationnetwork.node.shared.domain.globalAlignment

case class ShouldRedownload(
  value: Boolean,
  reason: List[String]
)
object ShouldRedownload {
  def empty: ShouldRedownload = ShouldRedownload(value = false, List.empty)
}

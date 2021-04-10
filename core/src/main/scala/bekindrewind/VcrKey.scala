package bekindrewind

sealed trait VcrKey {

  /**
   * Determines if 2 grouped keys (VcrKey.Grouped) are equal. This is different from the plain `equals` method in that
   * we don't want comparing 2 ungrouped keys to equal `true`
   */
  def areSameGroupedKey(other: VcrKey): Boolean
}

object VcrKey {
  def apply(x: Any): VcrKey.Grouped =
    VcrKey.Grouped(x)

  final case class Grouped(key: Any) extends VcrKey {
    override def areSameGroupedKey(other: VcrKey): Boolean = other match {
      case VcrKey.Grouped(otherKey) => key == otherKey
      case VcrKey.Ungrouped         => false
    }
  }

  case object Ungrouped extends VcrKey {
    override def areSameGroupedKey(other: VcrKey): Boolean = false
  }
}

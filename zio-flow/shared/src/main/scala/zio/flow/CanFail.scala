package zio.flow

sealed trait CanFail[-E]

object CanFail extends CanFail[Any] {
  implicit def canFail[E]: CanFail[E] = CanFail

  /**
   * Providing multiple ambiguous values for CanFail[Nothing] makes it not compile when `E = Nothing`.
   * We need this because `CanFail`` should always have a legitimate error type `E` representing a ZFlow that can fail with that `error`.
   */

  implicit val canFailAmbiguous1: CanFail[Nothing] = CanFail
  implicit val canFailAmbiguous2: CanFail[Nothing] = CanFail
}

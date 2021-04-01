package zio.flow

sealed trait Tuple[Z] {
  type out

  def unapply(remote: Remote[Z]): Option[out]
}

object Tuple {
  type Aux[A, B] = Tuple[A] {
    type out = B
  }

  implicit def tuple2[A, B]: Aux[(A, B), (Remote[A], Remote[B])] = new Tuple[(A, B)] {
    type out = (Remote[A], Remote[B])

    def unapply(remote: Remote[(A, B)]): Option[out] = Some((remote._1, remote._2))
  }

  implicit def tuple3[A, B, C]: Aux[(A, B, C), (Remote[A], Remote[B], Remote[C])] = new Tuple[(A, B, C)] {
    override type out = (Remote[A], Remote[B], Remote[C])

    override def unapply(remote: Remote[(A, B, C)]): Option[out] = Some((remote._1, remote._2, remote._3))
  }

  implicit def tuple4[A, B, C, D]: Aux[(A, B, C, D), (Remote[A], Remote[B], Remote[C], Remote[D])] =
    new Tuple[(A, B, C, D)] {
      override type out = (Remote[A], Remote[B], Remote[C], Remote[D])

      override def unapply(remote: Remote[(A, B, C, D)]): Option[out] = Some(
        (remote._1, remote._2, remote._3, remote._4)
      )
    }

  def unapply[Z, O](remote: Remote[Z])(implicit tuple: Aux[Z, O]): Option[O] = tuple.unapply(remote)
}

package zio.flow.runtime

import scala.annotation.nowarn

package object internal {
  @nowarn trait =:!=[A, B]

  implicit def neq[A, B]: A =:!= B = new =:!=[A, B] {}

  implicit def neqAmbig1[A]: A =:!= A = ???

  implicit def neqAmbig2[A]: A =:!= A = ???
}

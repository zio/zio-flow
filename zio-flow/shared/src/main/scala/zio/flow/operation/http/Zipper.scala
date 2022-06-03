package zio.flow.operation.http

import zio.schema.Schema
import zio.schema.CaseSet

trait Zipper[A, B] {
  type Out
  def zip(left: A, right: B): Out
  def unzip(out: Out): (A, B)
}

object Zipper extends ZipperLowPriority1 {

  implicit def schema[A, B, C]: Schema[Zipper.WithOut[A, B, C]] =
    Schema.EnumN(
      CaseSet
        .Cons(zipperLeftIdentitySchemaCase[A, B, C], CaseSet.Empty[Zipper.WithOut[A, B, C]]())
        .:+:(zipperRightIdentitySchemaCase[A, B, C])
        .:+:(zipper3SchemaCase[A, B, C])
        .:+:(zipper4SchemaCase[A, B, C])
        .:+:(zipper5SchemaCase[A, B, C])
    )

  type WithOut[A, B, C] = Zipper[A, B] { type Out = C }

  implicit def zipperLeftIdentity[A]: Zipper.WithOut[Unit, A, A] =
    new ZipperLeftIdentity[A]

  class ZipperLeftIdentity[A] extends Zipper[Unit, A] {
    type Out = A
    def zip(left: Unit, right: A): A =
      right

    override def unzip(out: A): (Unit, A) =
      ((), out)
  }

  def zipperLeftIdentitySchemaCase[A, B, C]: Schema.Case[ZipperLeftIdentity[Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("leftIdentity", Schema.singleton(new ZipperLeftIdentity[Any]), _.asInstanceOf[ZipperLeftIdentity[Any]])

  def zipperRightIdentitySchemaCase[A, B, C]: Schema.Case[ZipperRightIdentity[Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("rightIdentity", Schema.singleton(new ZipperRightIdentity[Any]), _.asInstanceOf[ZipperRightIdentity[Any]])

  def zipper3SchemaCase[A, B, C]: Schema.Case[Zipper3[Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("3", Schema.singleton(new Zipper3[Any, Any, Any]), _.asInstanceOf[Zipper3[Any, Any, Any]])

  def zipper4SchemaCase[A, B, C]: Schema.Case[Zipper4[Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("4", Schema.singleton(new Zipper4[Any, Any, Any, Any]), _.asInstanceOf[Zipper4[Any, Any, Any, Any]])

  def zipper5SchemaCase[A, B, C]: Schema.Case[Zipper5[Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "5",
      Schema.singleton(new Zipper5[Any, Any, Any, Any, Any]),
      _.asInstanceOf[Zipper5[Any, Any, Any, Any, Any]]
    )
}

trait ZipperLowPriority1 extends ZipperLowPriority2 {

  implicit def zipperRightIdentity[A]: Zipper.WithOut[A, Unit, A] =
    new ZipperRightIdentity[A]

  class ZipperRightIdentity[A] extends Zipper[A, Unit] {
    type Out = A
    def zip(left: A, right: Unit): A =
      left

    override def unzip(out: A): (A, Unit) =
      (out, ())
  }
}

trait ZipperLowPriority2 extends ZipperLowPriority3 {

  implicit def zipper3[A, B, Z]: Zipper.WithOut[(A, B), Z, (A, B, Z)] =
    new Zipper3[A, B, Z]

  class Zipper3[A, B, Z] extends Zipper[(A, B), Z] {
    type Out = (A, B, Z)
    def zip(left: (A, B), right: Z): (A, B, Z) =
      (left._1, left._2, right)

    override def unzip(out: (A, B, Z)): ((A, B), Z) =
      ((out._1, out._2), out._3)
  }

  implicit def zipper4[A, B, C, Z]: Zipper.WithOut[(A, B, C), Z, (A, B, C, Z)] =
    new Zipper4[A, B, C, Z]

  class Zipper4[A, B, C, Z] extends Zipper[(A, B, C), Z] {
    type Out = (A, B, C, Z)
    def zip(left: (A, B, C), right: Z): (A, B, C, Z) =
      (left._1, left._2, left._3, right)

    override def unzip(out: (A, B, C, Z)): ((A, B, C), Z) =
      ((out._1, out._2, out._3), out._4)
  }

  implicit def zipper5[A, B, C, D, Z]: Zipper.WithOut[(A, B, C, D), Z, (A, B, C, D, Z)] =
    new Zipper5[A, B, C, D, Z]

  class Zipper5[A, B, C, D, Z] extends Zipper[(A, B, C, D), Z] {
    type Out = (A, B, C, D, Z)
    def zip(left: (A, B, C, D), right: Z): (A, B, C, D, Z) =
      (left._1, left._2, left._3, left._4, right)

    override def unzip(out: (A, B, C, D, Z)): ((A, B, C, D), Z) =
      ((out._1, out._2, out._3, out._4), out._5)
  }

  // implicit def Zipper6[A, B, C, D, E, Z]: Zipper.WithOut[(A, B, C, D, E), Z, (A, B, C, D, E, Z)] =
  //   new Zipper[(A, B, C, D, E), Z] {
  //     type Out = (A, B, C, D, E, Z)
  //     def zip(left: (A, B, C, D, E), right: Z): (A, B, C, D, E, Z) =
  //       (left._1, left._2, left._3, left._4, left._5, right)

  //     override def unzip(out: (A, B, C, D, E, Z)): ((A, B, C, D, E), Z) =
  //       ((out._1, out._2, out._3, out._4, out._5), out._6)
  //   }

  // implicit def Zipper7[A, B, C, D, E, F, Z]: Zipper.WithOut[(A, B, C, D, E, F), Z, (A, B, C, D, E, F, Z)] =
  //   new Zipper[(A, B, C, D, E, F), Z] {
  //     type Out = (A, B, C, D, E, F, Z)
  //     def zip(left: (A, B, C, D, E, F), right: Z): (A, B, C, D, E, F, Z) =
  //       (left._1, left._2, left._3, left._4, left._5, left._6, right)

  //     override def unzip(out: (A, B, C, D, E, F, Z)): ((A, B, C, D, E, F), Z) =
  //       ((out._1, out._2, out._3, out._4, out._5, out._6), out._7)
  //   }

  // implicit def Zipper8[A, B, C, D, E, F, G, Z]: Zipper.WithOut[(A, B, C, D, E, F, G), Z, (A, B, C, D, E, F, G, Z)] =
  //   new Zipper[(A, B, C, D, E, F, G), Z] {
  //     type Out = (A, B, C, D, E, F, G, Z)
  //     def zip(left: (A, B, C, D, E, F, G), right: Z): (A, B, C, D, E, F, G, Z) =
  //       (left._1, left._2, left._3, left._4, left._5, left._6, left._7, right)

  //     override def unzip(out: (A, B, C, D, E, F, G, Z)): ((A, B, C, D, E, F, G), Z) =
  //       ((out._1, out._2, out._3, out._4, out._5, out._6, out._7), out._8)
  //   }

  // implicit def Zipper9[A, B, C, D, E, F, G, H, Z]
  //     : Zipper.WithOut[(A, B, C, D, E, F, G, H), Z, (A, B, C, D, E, F, G, H, Z)] =
  //   new Zipper[(A, B, C, D, E, F, G, H), Z] {
  //     type Out = (A, B, C, D, E, F, G, H, Z)
  //     def zip(left: (A, B, C, D, E, F, G, H), right: Z): (A, B, C, D, E, F, G, H, Z) =
  //       (left._1, left._2, left._3, left._4, left._5, left._6, left._7, left._8, right)

  //     override def unzip(out: (A, B, C, D, E, F, G, H, Z)): ((A, B, C, D, E, F, G, H), Z) =
  //       ((out._1, out._2, out._3, out._4, out._5, out._6, out._7, out._8), out._9)
  //   }

  // implicit def Zipper10[A, B, C, D, E, F, G, H, I, Z]
  //     : Zipper.WithOut[(A, B, C, D, E, F, G, H, I), Z, (A, B, C, D, E, F, G, H, I, Z)] =
  //   new Zipper[(A, B, C, D, E, F, G, H, I), Z] {
  //     type Out = (A, B, C, D, E, F, G, H, I, Z)
  //     def zip(left: (A, B, C, D, E, F, G, H, I), right: Z): (A, B, C, D, E, F, G, H, I, Z) =
  //       (left._1, left._2, left._3, left._4, left._5, left._6, left._7, left._8, left._9, right)

  //     override def unzip(out: (A, B, C, D, E, F, G, H, I, Z)): ((A, B, C, D, E, F, G, H, I), Z) =
  //       ((out._1, out._2, out._3, out._4, out._5, out._6, out._7, out._8, out._9), out._10)

  //   }

//  implicit def Zipper11[A, B, C, D, E, F, G, H, I, J, Z]
//      : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J), Z, (A, B, C, D, E, F, G, H, I, J, Z)] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, Z)
//      def zip(left: (A, B, C, D, E, F, G, H, I, J), right: Z): (A, B, C, D, E, F, G, H, I, J, Z) =
//        (left._1, left._2, left._3, left._4, left._5, left._6, left._7, left._8, left._9, left._10, right)
//    }
//
//  implicit def Zipper12[A, B, C, D, E, F, G, H, I, J, K, Z]
//      : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K), Z, (A, B, C, D, E, F, G, H, I, J, K, Z)] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, Z)
//      def zip(left: (A, B, C, D, E, F, G, H, I, J, K), right: Z): (A, B, C, D, E, F, G, H, I, J, K, Z) =
//        (left._1, left._2, left._3, left._4, left._5, left._6, left._7, left._8, left._9, left._10, left._11, right)
//    }
//
//  implicit def Zipper13[A, B, C, D, E, F, G, H, I, J, K, L, Z]
//      : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K, L), Z, (A, B, C, D, E, F, G, H, I, J, K, L, Z)] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, Z)
//      def zip(left: (A, B, C, D, E, F, G, H, I, J, K, L), right: Z): (A, B, C, D, E, F, G, H, I, J, K, L, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          right
//        )
//    }
//
//  implicit def Zipper14[A, B, C, D, E, F, G, H, I, J, K, L, M, Z]
//      : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K, L, M), Z, (A, B, C, D, E, F, G, H, I, J, K, L, M, Z)] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, Z)
//      def zip(left: (A, B, C, D, E, F, G, H, I, J, K, L, M), right: Z): (A, B, C, D, E, F, G, H, I, J, K, L, M, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          right
//        )
//    }
//
//  implicit def Zipper15[A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z]
//      : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K, L, M, N), Z, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z)] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z)
//      def zip(
//          left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N),
//          right: Z
//      ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          left._14,
//          right
//        )
//    }
//
//  implicit def Zipper16[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z]
//      : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O), Z, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z)] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z)
//      def zip(
//          left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O),
//          right: Z
//      ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          left._14,
//          left._15,
//          right
//        )
//    }
//
//  implicit def Zipper17[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z]: Zipper.WithOut[
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P),
//    Z,
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z)
//  ] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z)
//      def zip(
//          left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P),
//          right: Z
//      ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          left._14,
//          left._15,
//          left._16,
//          right
//        )
//    }
//
//  implicit def Zipper18[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z]: Zipper.WithOut[
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q),
//    Z,
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z)
//  ] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z)
//      def zip(
//          left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q),
//          right: Z
//      ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          left._14,
//          left._15,
//          left._16,
//          left._17,
//          right
//        )
//    }
//
//  implicit def Zipper19[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z]: Zipper.WithOut[
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R),
//    Z,
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z)
//  ] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z)
//      def zip(
//          left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R),
//          right: Z
//      ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          left._14,
//          left._15,
//          left._16,
//          left._17,
//          left._18,
//          right
//        )
//    }
//
//  implicit def Zipper20[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z]: Zipper.WithOut[
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S),
//    Z,
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z)
//  ] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z)
//      def zip(
//          left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S),
//          right: Z
//      ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          left._14,
//          left._15,
//          left._16,
//          left._17,
//          left._18,
//          left._19,
//          right
//        )
//    }
//
//  implicit def Zipper21[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z]: Zipper.WithOut[
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T),
//    Z,
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z)
//  ] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z)
//      def zip(
//          left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T),
//          right: Z
//      ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          left._14,
//          left._15,
//          left._16,
//          left._17,
//          left._18,
//          left._19,
//          left._20,
//          right
//        )
//    }
//
//  implicit def Zipper22[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z]: Zipper.WithOut[
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U),
//    Z,
//    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z)
//  ] =
//    new Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U), Z] {
//      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z)
//      def zip(
//          left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U),
//          right: Z
//      ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z) =
//        (
//          left._1,
//          left._2,
//          left._3,
//          left._4,
//          left._5,
//          left._6,
//          left._7,
//          left._8,
//          left._9,
//          left._10,
//          left._11,
//          left._12,
//          left._13,
//          left._14,
//          left._15,
//          left._16,
//          left._17,
//          left._18,
//          left._19,
//          left._20,
//          left._21,
//          right
//        )
//    }
}

trait ZipperLowPriority3 {

  implicit def Zipper2[A, B]: Zipper.WithOut[A, B, (A, B)] =
    new Zipper[A, B] {
      type Out = (A, B)
      def zip(left: A, right: B): Out = (left, right)

      override def unzip(out: (A, B)): (A, B) =
        out
    }
}

/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.operation.http

import zio.schema.{CaseSet, Schema, TypeId}

trait Zipper[A, B] {
  type Out
  def zip(left: A, right: B): Out
  def unzip(out: Out): (A, B)

  def zipSchema(left: Schema[A], right: Schema[B]): Schema[Out]
}

object Zipper extends ZipperLowPriority1 {
  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Zipper")

  implicit def schema[A, B, C]: Schema[Zipper.WithOut[A, B, C]] =
    Schema.EnumN(
      typeId,
      CaseSet
        .Cons(zipperLeftIdentitySchemaCase[A, B, C], CaseSet.Empty[Zipper.WithOut[A, B, C]]())
        .:+:(zipperRightIdentitySchemaCase[A, B, C])
        .:+:(zipper2SchemaCase[A, B, C])
        .:+:(zipper3SchemaCase[A, B, C])
        .:+:(zipper4SchemaCase[A, B, C])
        .:+:(zipper5SchemaCase[A, B, C])
        .:+:(zipper6SchemaCase[A, B, C])
        .:+:(zipper7SchemaCase[A, B, C])
        .:+:(zipper8SchemaCase[A, B, C])
        .:+:(zipper9SchemaCase[A, B, C])
        .:+:(zipper10SchemaCase[A, B, C])
        .:+:(zipper11SchemaCase[A, B, C])
        .:+:(zipper12SchemaCase[A, B, C])
        .:+:(zipper13SchemaCase[A, B, C])
        .:+:(zipper14SchemaCase[A, B, C])
        .:+:(zipper15SchemaCase[A, B, C])
        .:+:(zipper16SchemaCase[A, B, C])
        .:+:(zipper17SchemaCase[A, B, C])
        .:+:(zipper18SchemaCase[A, B, C])
        .:+:(zipper19SchemaCase[A, B, C])
        .:+:(zipper20SchemaCase[A, B, C])
        .:+:(zipper21SchemaCase[A, B, C])
        .:+:(zipper22SchemaCase[A, B, C])
    )

  type WithOut[A, B, C] = Zipper[A, B] { type Out = C }

  implicit def zipperLeftIdentity[A]: Zipper.WithOut[Unit, A, A] = ZipperLeftIdentity[A]()

  case class ZipperLeftIdentity[A]() extends Zipper[Unit, A] {
    type Out = A
    def zip(left: Unit, right: A): A =
      right

    override def unzip(out: A): (Unit, A) =
      ((), out)

    override def zipSchema(left: Schema[Unit], right: Schema[A]): Schema[A] =
      right
  }

  def zipperLeftIdentitySchemaCase[A, B, C]: Schema.Case[ZipperLeftIdentity[Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("leftIdentity", Schema.singleton(ZipperLeftIdentity[Any]()), _.asInstanceOf[ZipperLeftIdentity[Any]])

  def zipperRightIdentitySchemaCase[A, B, C]: Schema.Case[ZipperRightIdentity[Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("rightIdentity", Schema.singleton(ZipperRightIdentity[Any]()), _.asInstanceOf[ZipperRightIdentity[Any]])

  def zipper2SchemaCase[A, B, C]: Schema.Case[Zipper2[Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("2", Schema.singleton(Zipper2[Any, Any]()), _.asInstanceOf[Zipper2[Any, Any]])

  def zipper3SchemaCase[A, B, C]: Schema.Case[Zipper3[Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("3", Schema.singleton(Zipper3[Any, Any, Any]()), _.asInstanceOf[Zipper3[Any, Any, Any]])

  def zipper4SchemaCase[A, B, C]: Schema.Case[Zipper4[Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case("4", Schema.singleton(Zipper4[Any, Any, Any, Any]()), _.asInstanceOf[Zipper4[Any, Any, Any, Any]])

  def zipper5SchemaCase[A, B, C]: Schema.Case[Zipper5[Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "5",
      Schema.singleton(Zipper5[Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper5[Any, Any, Any, Any, Any]]
    )

  def zipper6SchemaCase[A, B, C]: Schema.Case[Zipper6[Any, Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "6",
      Schema.singleton(Zipper6[Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper6[Any, Any, Any, Any, Any, Any]]
    )

  def zipper7SchemaCase[A, B, C]: Schema.Case[Zipper7[Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "7",
      Schema.singleton(Zipper7[Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper7[Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper8SchemaCase[A, B, C]
    : Schema.Case[Zipper8[Any, Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "8",
      Schema.singleton(Zipper8[Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper8[Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper9SchemaCase[A, B, C]
    : Schema.Case[Zipper9[Any, Any, Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "9",
      Schema.singleton(Zipper9[Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper9[Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper10SchemaCase[A, B, C]
    : Schema.Case[Zipper10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "10",
      Schema.singleton(Zipper10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper10[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper11SchemaCase[A, B, C]
    : Schema.Case[Zipper11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "11",
      Schema.singleton(Zipper11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper11[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper12SchemaCase[A, B, C]
    : Schema.Case[Zipper12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "12",
      Schema.singleton(Zipper12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper12[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper13SchemaCase[A, B, C]
    : Schema.Case[Zipper13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "13",
      Schema.singleton(Zipper13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper13[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper14SchemaCase[A, B, C]
    : Schema.Case[Zipper14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[
      A,
      B,
      C
    ]] =
    Schema.Case(
      "14",
      Schema.singleton(Zipper14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper14[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper15SchemaCase[A, B, C]
    : Schema.Case[Zipper15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any], Zipper.WithOut[
      A,
      B,
      C
    ]] =
    Schema.Case(
      "15",
      Schema.singleton(Zipper15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper15[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper16SchemaCase[A, B, C]: Schema.Case[Zipper16[
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any
  ], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "16",
      Schema.singleton(Zipper16[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper16[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper17SchemaCase[A, B, C]: Schema.Case[Zipper17[
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any
  ], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "17",
      Schema.singleton(Zipper17[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()),
      _.asInstanceOf[Zipper17[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper18SchemaCase[A, B, C]: Schema.Case[Zipper18[
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any
  ], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "18",
      Schema.singleton(
        Zipper18[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()
      ),
      _.asInstanceOf[Zipper18[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]]
    )

  def zipper19SchemaCase[A, B, C]: Schema.Case[Zipper19[
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any
  ], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "19",
      Schema.singleton(
        Zipper19[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()
      ),
      _.asInstanceOf[
        Zipper19[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
      ]
    )

  def zipper20SchemaCase[A, B, C]: Schema.Case[Zipper20[
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any
  ], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "20",
      Schema.singleton(
        Zipper20[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]()
      ),
      _.asInstanceOf[
        Zipper20[Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any]
      ]
    )

  def zipper21SchemaCase[A, B, C]: Schema.Case[Zipper21[
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any
  ], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "21",
      Schema.singleton(
        Zipper21[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]()
      ),
      _.asInstanceOf[Zipper21[
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any
      ]]
    )

  def zipper22SchemaCase[A, B, C]: Schema.Case[Zipper22[
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any,
    Any
  ], Zipper.WithOut[A, B, C]] =
    Schema.Case(
      "22",
      Schema.singleton(
        Zipper22[
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any,
          Any
        ]()
      ),
      _.asInstanceOf[Zipper22[
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any,
        Any
      ]]
    )
}

trait ZipperLowPriority1 extends ZipperLowPriority2 {

  implicit def zipperRightIdentity[A]: Zipper.WithOut[A, Unit, A] = ZipperRightIdentity[A]()

  case class ZipperRightIdentity[A]() extends Zipper[A, Unit] {
    type Out = A
    def zip(left: A, right: Unit): A =
      left

    override def unzip(out: A): (A, Unit) =
      (out, ())

    override def zipSchema(left: Schema[A], right: Schema[Unit]): Schema[A] = left
  }
}

trait ZipperLowPriority2 extends ZipperLowPriority3 {

  implicit def zipper3[A, B, Z]: Zipper.WithOut[(A, B), Z, (A, B, Z)] =
    new Zipper3[A, B, Z]

  case class Zipper3[A, B, Z]() extends Zipper[(A, B), Z] {
    type Out = (A, B, Z)
    def zip(left: (A, B), right: Z): (A, B, Z) =
      (left._1, left._2, right)

    override def unzip(out: (A, B, Z)): ((A, B), Z) =
      ((out._1, out._2), out._3)

    override def zipSchema(left: Schema[(A, B)], right: Schema[Z]): Schema[(A, B, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper4[A, B, C, Z]: Zipper.WithOut[(A, B, C), Z, (A, B, C, Z)] = Zipper4()

  case class Zipper4[A, B, C, Z]() extends Zipper[(A, B, C), Z] {
    type Out = (A, B, C, Z)
    def zip(left: (A, B, C), right: Z): (A, B, C, Z) =
      (left._1, left._2, left._3, right)

    override def unzip(out: (A, B, C, Z)): ((A, B, C), Z) =
      ((out._1, out._2, out._3), out._4)

    override def zipSchema(left: Schema[(A, B, C)], right: Schema[Z]): Schema[(A, B, C, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper5[A, B, C, D, Z]: Zipper.WithOut[(A, B, C, D), Z, (A, B, C, D, Z)] = Zipper5()

  case class Zipper5[A, B, C, D, Z]() extends Zipper[(A, B, C, D), Z] {
    type Out = (A, B, C, D, Z)
    def zip(left: (A, B, C, D), right: Z): (A, B, C, D, Z) =
      (left._1, left._2, left._3, left._4, right)

    override def unzip(out: (A, B, C, D, Z)): ((A, B, C, D), Z) =
      ((out._1, out._2, out._3, out._4), out._5)

    override def zipSchema(left: Schema[(A, B, C, D)], right: Schema[Z]): Schema[(A, B, C, D, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper6[A, B, C, D, E, Z]: Zipper.WithOut[(A, B, C, D, E), Z, (A, B, C, D, E, Z)] =
    Zipper6()

  case class Zipper6[A, B, C, D, E, Z]() extends Zipper[(A, B, C, D, E), Z] {
    type Out = (A, B, C, D, E, Z)
    def zip(left: (A, B, C, D, E), right: Z): (A, B, C, D, E, Z) =
      (left._1, left._2, left._3, left._4, left._5, right)

    override def unzip(out: (A, B, C, D, E, Z)): ((A, B, C, D, E), Z) =
      ((out._1, out._2, out._3, out._4, out._5), out._6)

    override def zipSchema(left: Schema[(A, B, C, D, E)], right: Schema[Z]): Schema[(A, B, C, D, E, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper7[A, B, C, D, E, F, Z]: Zipper.WithOut[(A, B, C, D, E, F), Z, (A, B, C, D, E, F, Z)] =
    Zipper7()

  case class Zipper7[A, B, C, D, E, F, Z]() extends Zipper[(A, B, C, D, E, F), Z] {
    type Out = (A, B, C, D, E, F, Z)
    def zip(left: (A, B, C, D, E, F), right: Z): (A, B, C, D, E, F, Z) =
      (left._1, left._2, left._3, left._4, left._5, left._6, right)

    override def unzip(out: (A, B, C, D, E, F, Z)): ((A, B, C, D, E, F), Z) =
      ((out._1, out._2, out._3, out._4, out._5, out._6), out._7)

    override def zipSchema(left: Schema[(A, B, C, D, E, F)], right: Schema[Z]): Schema[(A, B, C, D, E, F, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper8[A, B, C, D, E, F, G, Z]: Zipper.WithOut[(A, B, C, D, E, F, G), Z, (A, B, C, D, E, F, G, Z)] =
    Zipper8()

  case class Zipper8[A, B, C, D, E, F, G, Z]() extends Zipper[(A, B, C, D, E, F, G), Z] {
    type Out = (A, B, C, D, E, F, G, Z)
    def zip(left: (A, B, C, D, E, F, G), right: Z): (A, B, C, D, E, F, G, Z) =
      (left._1, left._2, left._3, left._4, left._5, left._6, left._7, right)

    override def unzip(out: (A, B, C, D, E, F, G, Z)): ((A, B, C, D, E, F, G), Z) =
      ((out._1, out._2, out._3, out._4, out._5, out._6, out._7), out._8)

    override def zipSchema(left: Schema[(A, B, C, D, E, F, G)], right: Schema[Z]): Schema[(A, B, C, D, E, F, G, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper9[A, B, C, D, E, F, G, H, Z]
    : Zipper.WithOut[(A, B, C, D, E, F, G, H), Z, (A, B, C, D, E, F, G, H, Z)] = Zipper9()

  case class Zipper9[A, B, C, D, E, F, G, H, Z]() extends Zipper[(A, B, C, D, E, F, G, H), Z] {
    type Out = (A, B, C, D, E, F, G, H, Z)
    def zip(left: (A, B, C, D, E, F, G, H), right: Z): (A, B, C, D, E, F, G, H, Z) =
      (left._1, left._2, left._3, left._4, left._5, left._6, left._7, left._8, right)

    override def unzip(out: (A, B, C, D, E, F, G, H, Z)): ((A, B, C, D, E, F, G, H), Z) =
      ((out._1, out._2, out._3, out._4, out._5, out._6, out._7, out._8), out._9)

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper10[A, B, C, D, E, F, G, H, I, Z]
    : Zipper.WithOut[(A, B, C, D, E, F, G, H, I), Z, (A, B, C, D, E, F, G, H, I, Z)] = Zipper10()

  case class Zipper10[A, B, C, D, E, F, G, H, I, Z]() extends Zipper[(A, B, C, D, E, F, G, H, I), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, Z)
    def zip(left: (A, B, C, D, E, F, G, H, I), right: Z): (A, B, C, D, E, F, G, H, I, Z) =
      (left._1, left._2, left._3, left._4, left._5, left._6, left._7, left._8, left._9, right)

    override def unzip(out: (A, B, C, D, E, F, G, H, I, Z)): ((A, B, C, D, E, F, G, H, I), Z) =
      ((out._1, out._2, out._3, out._4, out._5, out._6, out._7, out._8, out._9), out._10)

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper11[A, B, C, D, E, F, G, H, I, J, Z]
    : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J), Z, (A, B, C, D, E, F, G, H, I, J, Z)] = Zipper11()

  case class Zipper11[A, B, C, D, E, F, G, H, I, J, Z]() extends Zipper[(A, B, C, D, E, F, G, H, I, J), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, Z)
    def zip(left: (A, B, C, D, E, F, G, H, I, J), right: Z): (A, B, C, D, E, F, G, H, I, J, Z) =
      (left._1, left._2, left._3, left._4, left._5, left._6, left._7, left._8, left._9, left._10, right)

    override def unzip(out: (A, B, C, D, E, F, G, H, I, J, Z)): ((A, B, C, D, E, F, G, H, I, J), Z) =
      ((out._1, out._2, out._3, out._4, out._5, out._6, out._7, out._8, out._9, out._10), out._11)

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper12[A, B, C, D, E, F, G, H, I, J, K, Z]
    : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K), Z, (A, B, C, D, E, F, G, H, I, J, K, Z)] = Zipper12()

  case class Zipper12[A, B, C, D, E, F, G, H, I, J, K, Z]() extends Zipper[(A, B, C, D, E, F, G, H, I, J, K), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, Z)
    def zip(left: (A, B, C, D, E, F, G, H, I, J, K), right: Z): (A, B, C, D, E, F, G, H, I, J, K, Z) =
      (left._1, left._2, left._3, left._4, left._5, left._6, left._7, left._8, left._9, left._10, left._11, right)

    override def unzip(out: (A, B, C, D, E, F, G, H, I, J, K, Z)): ((A, B, C, D, E, F, G, H, I, J, K), Z) =
      ((out._1, out._2, out._3, out._4, out._5, out._6, out._7, out._8, out._9, out._10, out._11), out._12)

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper13[A, B, C, D, E, F, G, H, I, J, K, L, Z]
    : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K, L), Z, (A, B, C, D, E, F, G, H, I, J, K, L, Z)] = Zipper13()

  case class Zipper13[A, B, C, D, E, F, G, H, I, J, K, L, Z]() extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, Z)
    def zip(left: (A, B, C, D, E, F, G, H, I, J, K, L), right: Z): (A, B, C, D, E, F, G, H, I, J, K, L, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        right
      )

    override def unzip(out: (A, B, C, D, E, F, G, H, I, J, K, L, Z)): ((A, B, C, D, E, F, G, H, I, J, K, L), Z) =
      ((out._1, out._2, out._3, out._4, out._5, out._6, out._7, out._8, out._9, out._10, out._11, out._12), out._13)

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper14[A, B, C, D, E, F, G, H, I, J, K, L, M, Z]
    : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K, L, M), Z, (A, B, C, D, E, F, G, H, I, J, K, L, M, Z)] =
    Zipper14()

  case class Zipper14[A, B, C, D, E, F, G, H, I, J, K, L, M, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, Z)
    def zip(left: (A, B, C, D, E, F, G, H, I, J, K, L, M), right: Z): (A, B, C, D, E, F, G, H, I, J, K, L, M, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        right
      )

    override def unzip(out: (A, B, C, D, E, F, G, H, I, J, K, L, M, Z)): ((A, B, C, D, E, F, G, H, I, J, K, L, M), Z) =
      (
        (out._1, out._2, out._3, out._4, out._5, out._6, out._7, out._8, out._9, out._10, out._11, out._12, out._13),
        out._14
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper15[A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z]
    : Zipper.WithOut[(A, B, C, D, E, F, G, H, I, J, K, L, M, N), Z, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z)] =
    Zipper15()

  case class Zipper15[A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z)
    def zip(
      left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N),
      right: Z
    ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        left._14,
        right
      )

    override def unzip(
      out: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z)
    ): ((A, B, C, D, E, F, G, H, I, J, K, L, M, N), Z) =
      (
        (
          out._1,
          out._2,
          out._3,
          out._4,
          out._5,
          out._6,
          out._7,
          out._8,
          out._9,
          out._10,
          out._11,
          out._12,
          out._13,
          out._14
        ),
        out._15
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper16[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z]: Zipper.WithOut[
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O),
    Z,
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z)
  ] = Zipper16()

  case class Zipper16[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z)
    def zip(
      left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O),
      right: Z
    ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        left._14,
        left._15,
        right
      )

    override def unzip(
      out: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z)
    ): ((A, B, C, D, E, F, G, H, I, J, K, L, M, N, O), Z) =
      (
        (
          out._1,
          out._2,
          out._3,
          out._4,
          out._5,
          out._6,
          out._7,
          out._8,
          out._9,
          out._10,
          out._11,
          out._12,
          out._13,
          out._14,
          out._15
        ),
        out._16
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper17[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z]: Zipper.WithOut[
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P),
    Z,
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z)
  ] = Zipper17()

  case class Zipper17[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z)
    def zip(
      left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P),
      right: Z
    ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        left._14,
        left._15,
        left._16,
        right
      )

    override def unzip(
      out: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z)
    ): ((A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P), Z) =
      (
        (
          out._1,
          out._2,
          out._3,
          out._4,
          out._5,
          out._6,
          out._7,
          out._8,
          out._9,
          out._10,
          out._11,
          out._12,
          out._13,
          out._14,
          out._15,
          out._16
        ),
        out._17
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper18[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z]: Zipper.WithOut[
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q),
    Z,
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z)
  ] = Zipper18()

  case class Zipper18[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z)
    def zip(
      left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q),
      right: Z
    ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        left._14,
        left._15,
        left._16,
        left._17,
        right
      )

    override def unzip(
      out: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z)
    ): ((A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q), Z) =
      (
        (
          out._1,
          out._2,
          out._3,
          out._4,
          out._5,
          out._6,
          out._7,
          out._8,
          out._9,
          out._10,
          out._11,
          out._12,
          out._13,
          out._14,
          out._15,
          out._16,
          out._17
        ),
        out._18
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper19[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z]: Zipper.WithOut[
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R),
    Z,
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z)
  ] = Zipper19()

  case class Zipper19[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z)
    def zip(
      left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R),
      right: Z
    ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        left._14,
        left._15,
        left._16,
        left._17,
        left._18,
        right
      )

    override def unzip(
      out: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z)
    ): ((A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R), Z) =
      (
        (
          out._1,
          out._2,
          out._3,
          out._4,
          out._5,
          out._6,
          out._7,
          out._8,
          out._9,
          out._10,
          out._11,
          out._12,
          out._13,
          out._14,
          out._15,
          out._16,
          out._17,
          out._18
        ),
        out._19
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper20[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z]: Zipper.WithOut[
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S),
    Z,
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z)
  ] = Zipper20()

  case class Zipper20[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z)
    def zip(
      left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S),
      right: Z
    ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        left._14,
        left._15,
        left._16,
        left._17,
        left._18,
        left._19,
        right
      )

    override def unzip(
      out: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z)
    ): ((A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S), Z) =
      (
        (
          out._1,
          out._2,
          out._3,
          out._4,
          out._5,
          out._6,
          out._7,
          out._8,
          out._9,
          out._10,
          out._11,
          out._12,
          out._13,
          out._14,
          out._15,
          out._16,
          out._17,
          out._18,
          out._19
        ),
        out._20
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper21[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z]: Zipper.WithOut[
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T),
    Z,
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z)
  ] = Zipper21()

  case class Zipper21[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z)
    def zip(
      left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T),
      right: Z
    ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        left._14,
        left._15,
        left._16,
        left._17,
        left._18,
        left._19,
        left._20,
        right
      )

    override def unzip(
      out: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z)
    ): ((A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T), Z) =
      (
        (
          out._1,
          out._2,
          out._3,
          out._4,
          out._5,
          out._6,
          out._7,
          out._8,
          out._9,
          out._10,
          out._11,
          out._12,
          out._13,
          out._14,
          out._15,
          out._16,
          out._17,
          out._18,
          out._19,
          out._20
        ),
        out._21
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }

  implicit def zipper22[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z]: Zipper.WithOut[
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U),
    Z,
    (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z)
  ] = Zipper22()

  case class Zipper22[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z]()
      extends Zipper[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U), Z] {
    type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z)
    def zip(
      left: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U),
      right: Z
    ): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z) =
      (
        left._1,
        left._2,
        left._3,
        left._4,
        left._5,
        left._6,
        left._7,
        left._8,
        left._9,
        left._10,
        left._11,
        left._12,
        left._13,
        left._14,
        left._15,
        left._16,
        left._17,
        left._18,
        left._19,
        left._20,
        left._21,
        right
      )

    override def unzip(
      out: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z)
    ): ((A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U), Z) =
      (
        (
          out._1,
          out._2,
          out._3,
          out._4,
          out._5,
          out._6,
          out._7,
          out._8,
          out._9,
          out._10,
          out._11,
          out._12,
          out._13,
          out._14,
          out._15,
          out._16,
          out._17,
          out._18,
          out._19,
          out._20,
          out._21
        ),
        out._22
      )

    override def zipSchema(
      left: Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)],
      right: Schema[Z]
    ): Schema[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Z)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }
}

trait ZipperLowPriority3 {

  implicit def zipper2[A, B]: Zipper.WithOut[A, B, (A, B)] =
    new Zipper2[A, B]

  case class Zipper2[A, B]() extends Zipper[A, B] {
    type Out = (A, B)
    def zip(left: A, right: B): Out = (left, right)

    override def unzip(out: (A, B)): (A, B) =
      out

    override def zipSchema(left: Schema[A], right: Schema[B]): Schema[(A, B)] =
      left.zip(right).transform((zip _).tupled, unzip)
  }
}

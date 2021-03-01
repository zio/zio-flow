package zio.flow

import zio.flow.ExprTuple.{ Has2, Has3, Has4 }

trait ExprTuple[+A] {
  def self: Expr[A]

  def _1(implicit ev: Has2[A]): Expr[ev._1] = ev._1(self)
  def _2(implicit ev: Has2[A]): Expr[ev._2] = ev._2(self)
  def _3(implicit ev: Has3[A]): Expr[ev._3] = ev._3(self)
  def _4(implicit ev: Has4[A]): Expr[ev._4] = ev._4(self)

  final def ->[B](that: Expr[B]): Expr[(A, B)] = Expr.tuple2((self, that))
}

object ExprTuple {

  trait Has2[-A] {
    type _1
    type _2

    def _1(tuple: Expr[A]): Expr[_1]
    def _2(tuple: Expr[A]): Expr[_2]
  }
  object Has2    {
    type Aux[-A, A1, A2] =
      Has2[A] { type _1 = A1; type _2 = A2 }
  }

  trait Has3[-A] extends Has2[A] {
    type _3

    def _3(tuple: Expr[A]): Expr[_3]
  }
  object Has3 {
    type Aux[-A, A1, A2, A3] =
      Has3[A] { type _1 = A1; type _2 = A2; type _3 = A3 }
  }

  trait Has4[-A] extends Has3[A] {
    type _4

    def _4(tuple: Expr[A]): Expr[_4]
  }
  object Has4 {
    type Aux[-A, A1, A2, A3, A4] =
      Has4[A] { type _1 = A1; type _2 = A2; type _3 = A3; type _4 = A4 }
  }

  implicit def tuple2[A, B]: Has2.Aux[(A, B), A, B] = new Has2[(A, B)] {
    override type _1 = A
    override type _2 = B

    override def _1(tuple: Expr[(A, B)]): Expr[_1] = Expr.First(tuple)
    override def _2(tuple: Expr[(A, B)]): Expr[_2] = Expr.Second(tuple)
  }

  implicit def tuple3[A, B, C]: Has3.Aux[(A, B, C), A, B, C] = new Has3[(A, B, C)] {
    override type _1 = A
    override type _2 = B
    override type _3 = C

    override def _1(tuple: Expr[(A, B, C)]): Expr[_1] = ???
    override def _2(tuple: Expr[(A, B, C)]): Expr[_2] = ???
    override def _3(tuple: Expr[(A, B, C)]): Expr[_3] = ???
  }

  implicit def tuple4[A, B, C, D]: Has4.Aux[(A, B, C, D), A, B, C, D] = new Has4[(A, B, C, D)] {
    override type _1 = A
    override type _2 = B
    override type _3 = C
    override type _4 = D

    override def _1(tuple: Expr[(A, B, C, D)]): Expr[_1] = ???
    override def _2(tuple: Expr[(A, B, C, D)]): Expr[_2] = ???
    override def _3(tuple: Expr[(A, B, C, D)]): Expr[_3] = ???
    override def _4(tuple: Expr[(A, B, C, D)]): Expr[_4] = ???
  }

  lazy val expr: Expr[(Int, String, Int, String)] = ???

  // val failsInDotty: Int = expr._1
}

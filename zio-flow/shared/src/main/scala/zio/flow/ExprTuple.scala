package zio.flow

import zio.flow.ExprTuple.{Has_2, Has_3 }

trait ExprTuple[+A] {
  def self: Expr[A]

  def _1(implicit ev: Has_2[A]): Expr[ev._1] = ev._1(self)
  def _2(implicit ev: Has_2[A]): Expr[ev._2] = ev._2(self)
  def _3(implicit ev: Has_3[A]): Expr[ev._3] = ev._3(self)

  final def ->[B](that: Expr[B]): Expr[(A, B)] = Expr.tuple2((self, that))
}

object ExprTuple {

  trait Has_2[-A] {
    type _1
    type _2

    def _1(tuple: Expr[A]): Expr[_1]
    def _2(tuple: Expr[A]): Expr[_2]
  }

  trait Has_3[-A] extends Has_2[A] {
    type _3

    def _3(tuple: Expr[A]): Expr[_3]
  }

  implicit def tuple2[A, B] = new Has_2[(A, B)] {
    override type _1 = A
    override type _2 = B

    override def _1(tuple: Expr[(A, B)]): Expr[_1] = Expr.First(tuple)
    override def _2(tuple: Expr[(A, B)]): Expr[_2] = Expr.Second(tuple)
  }

  implicit def tuple3[A, B, C] = new Has_3[(A, B, C)] {
    override type _1 = A
    override type _2 = B
    override type _3 = C

    override def _1(tuple: Expr[(A, B, C)]): Expr[_1] = ???
    override def _2(tuple: Expr[(A, B, C)]): Expr[_2] = ???
    override def _3(tuple: Expr[(A, B, C)]): Expr[_3] = ???
  }

  def a: Expr[(Int, String)] = ???

  def b: Expr[(Boolean, Int, String)] = ???

  val x: Expr[Boolean] = b._1

}

package zio.flow

import zio.flow.RemoteTuple.{ Has2, Has3, Has4 }

trait RemoteTuple[+A] {
  def self: Remote[A]

  def _1(implicit ev: Has2[A]): Remote[ev._1] = ev._1(self)
  def _2(implicit ev: Has2[A]): Remote[ev._2] = ev._2(self)
  def _3(implicit ev: Has3[A]): Remote[ev._3] = ev._3(self)
  def _4(implicit ev: Has4[A]): Remote[ev._4] = ev._4(self)

  final def ->[B](that: Remote[B]): Remote[(A, B)] = Remote.tuple2((self, that))
}

object RemoteTuple {

  trait Has2[-A] {
    type _1
    type _2

    def _1(tuple: Remote[A]): Remote[_1]
    def _2(tuple: Remote[A]): Remote[_2]
  }
  object Has2    {
    type Aux[-A, A1, A2] =
      Has2[A] { type _1 = A1; type _2 = A2 }
  }

  trait Has3[-A] extends Has2[A] {
    type _3

    def _3(tuple: Remote[A]): Remote[_3]
  }
  object Has3 {
    type Aux[-A, A1, A2, A3] =
      Has3[A] { type _1 = A1; type _2 = A2; type _3 = A3 }
  }

  trait Has4[-A] extends Has3[A] {
    type _4

    def _4(tuple: Remote[A]): Remote[_4]
  }
  object Has4 {
    type Aux[-A, A1, A2, A3, A4] =
      Has4[A] { type _1 = A1; type _2 = A2; type _3 = A3; type _4 = A4 }
  }

  implicit def tuple2[A, B]: Has2.Aux[(A, B), A, B] = new Has2[(A, B)] {
    override type _1 = A
    override type _2 = B

    override def _1(tuple: Remote[(A, B)]): Remote[_1] = Remote.First(tuple)
    override def _2(tuple: Remote[(A, B)]): Remote[_2] = Remote.Second(tuple)
  }

  implicit def tuple3[A, B, C]: Has3.Aux[(A, B, C), A, B, C] = new Has3[(A, B, C)] {
    override type _1 = A
    override type _2 = B
    override type _3 = C

    override def _1(tuple: Remote[(A, B, C)]): Remote[_1] = ???
    override def _2(tuple: Remote[(A, B, C)]): Remote[_2] = ???
    override def _3(tuple: Remote[(A, B, C)]): Remote[_3] = ???
  }

  implicit def tuple4[A, B, C, D]: Has4.Aux[(A, B, C, D), A, B, C, D] = new Has4[(A, B, C, D)] {
    override type _1 = A
    override type _2 = B
    override type _3 = C
    override type _4 = D

    override def _1(tuple: Remote[(A, B, C, D)]): Remote[_1] = ???
    override def _2(tuple: Remote[(A, B, C, D)]): Remote[_2] = ???
    override def _3(tuple: Remote[(A, B, C, D)]): Remote[_3] = ???
    override def _4(tuple: Remote[(A, B, C, D)]): Remote[_4] = ???
  }

  lazy val remote: Remote[(Int, String, Int, String)] = ???

  // val failsInDotty: Int = expr._1
}

package zio.flow.remote

import zio.flow.Remote
import zio.flow.remote.numeric.Numeric
import zio.schema.Schema

final class RemoteSetSyntax[A](val self: Remote[Set[A]]) extends AnyVal {

  def &(that: Remote[Set[A]]): Remote[Set[A]] =
    intersect(that)

  def &~(that: Remote[Set[A]]): Remote[Set[A]] =
    diff(that)

  def +(elem: Remote[A]): Remote[Set[A]] =
    incl(elem)

  def ++[B >: A](that: Remote[List[B]]): Remote[Set[B]] =
    concat(that)

  def -(elem: Remote[A]): Remote[Set[A]] =
    excl(elem)

  def --(that: Remote[List[A]]): Remote[Set[A]] =
    that.foldLeft(self)(_ - _)

  def apply(elem: Remote[A]): Remote[Boolean] =
    contains(elem)

  def concat[B >: A](that: Remote[List[B]]): Remote[Set[B]] =
    toList.concat(that).toSet

  def contains(elem: Remote[A]): Remote[Boolean] =
    toList.contains(elem)

  def corresponds[B](that: Remote[List[B]])(p: (Remote[A], Remote[B]) => Remote[Boolean]): Remote[Boolean] =
    toList.corresponds(that)(p)

  def count(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    toList.count(p)

  def diff(that: Remote[Set[A]]): Remote[Set[A]] =
    toList.diff(that.toList).toSet

  def drop(n: Remote[Int]): Remote[Set[A]] =
    toList.drop(n).toSet

  def dropRight(n: Remote[Int]): Remote[Set[A]] =
    toList.dropRight(n).toSet

  def dropWhile(predicate: Remote[A] => Remote[Boolean]): Remote[Set[A]] =
    toList.dropWhile(predicate).toSet

  def empty: Remote[Set[A]] =
    Remote.nil[A].toSet

  def excl(elem: Remote[A]): Remote[Set[A]] =
    toList.filter(_ !== elem).toSet

  def exists(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    toList.exists(p)

  def filter(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[Set[A]] =
    toList.filter(predicate).toSet

  def filterNot(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[Set[A]] =
    toList.filterNot(predicate).toSet

  def find(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    toList.find(p)

  def flatMap[B](f: Remote[A] => Remote[Set[B]]): Remote[Set[B]] =
    toList.flatMap(f(_).toList).toSet

  def flatten[B](implicit ev: A <:< Set[B]): Remote[Set[B]] =
    toList.map(a => a.widen[Set[B]].toList).flatten.toSet

  def fold[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    toList.fold(initial)(f)

  def foldLeft[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    toList.foldLeft(initial)(f)

  def foldRight[B](initial: Remote[B])(
    f: (Remote[A], Remote[B]) => Remote[B]
  ): Remote[B] =
    toList.foldRight(initial)(f)

  def forall(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    toList.forall(p)

  // TODO: groupBy etc if we have support for Remote[Map[K, V]]

  def head: Remote[A] =
    toList.head

  def headOption: Remote[Option[A]] =
    toList.headOption

  def incl(elem: Remote[A]): Remote[Set[A]] =
    toList.prepended(elem).toSet

  def init: Remote[Set[A]] =
    toList.init.toSet

  def inits: Remote[List[Set[A]]] =
    toList.inits.map(_.toSet)

  def intersect(that: Remote[Set[A]]): Remote[Set[A]] =
    toList.intersect(that.toList).toSet

  def isEmpty: Remote[Boolean] =
    toList.isEmpty

  def last: Remote[A] =
    toList.last

  def lastOption: Remote[Option[A]] =
    toList.lastOption

  def map[B](f: Remote[A] => Remote[B]): Remote[Set[B]] =
    toList.map(f).toSet

  def max(implicit schema: Schema[A]): Remote[A] =
    toList.max

  def maxBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    toList.maxBy(f)

  def maxByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    toList.maxByOption(f)

  def maxOption(implicit schema: Schema[A]): Remote[Option[A]] =
    toList.maxOption

  def min(implicit schema: Schema[A]): Remote[A] =
    toList.min

  def minBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    toList.minBy(f)

  def minByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    toList.minByOption(f)

  def minOption(implicit schema: Schema[A]): Remote[Option[A]] =
    toList.minOption

  def mkString(implicit schema: Schema[A]): Remote[String] =
    toList.mkString

  def mkString(sep: Remote[String])(implicit schema: Schema[A]): Remote[String] =
    toList.mkString(sep)

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String])(implicit
    schema: Schema[A]
  ): Remote[String] =
    toList.mkString(start, sep, end)

  def nonEmpty: Remote[Boolean] =
    toList.nonEmpty

  def partition(p: Remote[A] => Remote[Boolean]): Remote[(Set[A], Set[A])] = {
    val tuple = toList.partition(p)
    (tuple._1.toSet, tuple._2.toSet)
  }

  def partitionMap[A1, A2](p: Remote[A] => Remote[Either[A1, A2]]): Remote[(Set[A1], Set[A2])] = {
    val tuple = toList.partitionMap(p)
    (tuple._1.toSet, tuple._2.toSet)
  }

  def product(implicit numeric: Numeric[A]): Remote[A] =
    toList.product

  def reduce[B >: A](op: (Remote[B], Remote[B]) => Remote[B]): Remote[B] =
    toList.reduce(op)

  def reduceLeft[B >: A](op: (Remote[B], Remote[A]) => Remote[B]): Remote[B] =
    toList.reduceLeft(op)

  def reduceLeftOption[B >: A](op: (Remote[B], Remote[A]) => Remote[B]): Remote[Option[B]] =
    toList.reduceLeftOption(op)

  def reduceOption[B >: A](op: (Remote[B], Remote[B]) => Remote[B]): Remote[Option[B]] =
    toList.reduceOption(op)

  def reduceRight[B >: A](op: (Remote[A], Remote[B]) => Remote[B]): Remote[B] =
    toList.reduceRight(op)

  def reduceRightOption[B >: A](op: (Remote[A], Remote[B]) => Remote[B]): Remote[Option[B]] =
    toList.reduceRightOption(op)

  def removedAll(that: Remote[List[A]]): Remote[Set[A]] =
    diff(that.toSet)

  def scan[B >: A](z: Remote[B])(op: (Remote[B], Remote[B]) => Remote[B]): Remote[Set[B]] =
    toList.scan(z)(op).toSet

  def scanLeft[B >: A](z: Remote[B])(op: (Remote[B], Remote[A]) => Remote[B]): Remote[Set[B]] =
    toList.scanLeft(z)(op).toSet

  def scanRight[B >: A](z: Remote[B])(op: (Remote[A], Remote[B]) => Remote[B]): Remote[Set[B]] =
    toList.scanRight(z)(op).toSet

  def size: Remote[Int] =
    toList.size

  def slice(from: Remote[Int], until: Remote[Int]): Remote[Set[A]] =
    toList.slice(from, until).toSet

  def sliding(size: Remote[Int], step: Remote[Int]): Remote[List[Set[A]]] =
    toList.sliding(size, step).map(_.toSet)

  def sliding(size: Remote[Int]): Remote[List[Set[A]]] =
    sliding(size, 1)

  def span(p: Remote[A] => Remote[Boolean]): Remote[(Set[A], Set[A])] = {
    val tuple = toList.span(p)
    (tuple._1.toSet, tuple._2.toSet)
  }

  def splitAt(n: Remote[Int]): Remote[(Set[A], Set[A])] = {
    val tuple = toList.splitAt(n)
    (tuple._1.toSet, tuple._2.toSet)
  }

  def subsetOf(that: Remote[Set[A]]): Remote[Boolean] =
    forall(that.contains)

  def sum(implicit numeric: Numeric[A]): Remote[A] =
    toList.sum

  def tail: Remote[Set[A]] =
    toList.tail.toSet

  def tails: Remote[List[Set[A]]] =
    toList.tails.map(_.toSet)

  def take(n: Remote[Int]): Remote[Set[A]] =
    toList.take(n).toSet

  def takeRight(n: Remote[Int]): Remote[Set[A]] =
    toList.takeRight(n).toSet

  def takeWhile(p: Remote[A] => Remote[Boolean]): Remote[Set[A]] =
    toList.takeWhile(p).toSet

  def toList: Remote[List[A]] =
    Remote.SetToList(self)

  def toSet: Remote[Set[A]] =
    self

  def union(that: Remote[Set[A]]): Remote[Set[A]] =
    toList.concat(that.toList).toSet

  def unzip[A1, A2](implicit ev: A =:= (A1, A2)): Remote[(Set[A1], Set[A2])] = {
    val tuple = toList.unzip
    (tuple._1.toSet, tuple._2.toSet)
  }

  def unzip3[A1, A2, A3](implicit ev: A =:= (A1, A2, A3)): Remote[(Set[A1], Set[A2], Set[A3])] = {
    val tuple = toList.unzip3
    (tuple._1.toSet, tuple._2.toSet, tuple._3.toSet)
  }

  def zip[B](that: Remote[Set[B]]): Remote[Set[(A, B)]] =
    toList.zip(that.toList).toSet

  def zipAll[B](that: Remote[List[B]], thisElem: Remote[A], thatElem: Remote[B]): Remote[Set[(A, B)]] =
    toList.zipAll(that.toList, thisElem, thatElem).toSet

  def zipWithIndex: Remote[Set[(A, Int)]] =
    toList.zipWithIndex.toSet

  def |(that: Remote[Set[A]]): Remote[Set[A]] =
    union(that)
}

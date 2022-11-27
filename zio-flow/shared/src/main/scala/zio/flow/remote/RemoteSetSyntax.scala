package zio.flow.remote

import zio.flow.Remote
import zio.flow.debug.TrackRemotes
import zio.flow.remote.numeric.Numeric
import zio.schema.Schema

final class RemoteSetSyntax[A](val self: Remote[Set[A]], trackingEnabled: Boolean) {
  implicit private val remoteTracking: InternalRemoteTracking = InternalRemoteTracking(trackingEnabled)
  private val syntax                                          = TrackRemotes.ifEnabled
  import syntax._

  def &(that: Remote[Set[A]]): Remote[Set[A]] =
    intersect(that).trackInternal("Set#&")

  def &~(that: Remote[Set[A]]): Remote[Set[A]] =
    diff(that).trackInternal("Set#&~")

  def +(elem: Remote[A]): Remote[Set[A]] =
    incl(elem).trackInternal("Set+")

  def ++[B >: A](that: Remote[List[B]]): Remote[Set[B]] =
    concat(that).trackInternal("Set#++")

  def -(elem: Remote[A]): Remote[Set[A]] =
    excl(elem).trackInternal("Set#-")

  def --(that: Remote[List[A]]): Remote[Set[A]] =
    that.foldLeft(self)(_ - _).trackInternal("Set#--")

  def apply(elem: Remote[A]): Remote[Boolean] =
    contains(elem).trackInternal("Set#apply")

  def concat[B >: A](that: Remote[List[B]]): Remote[Set[B]] =
    toList.concat(that).toSet.trackInternal("Set#concat")

  def contains(elem: Remote[A]): Remote[Boolean] =
    toList.contains(elem).trackInternal("Set#contains")

  def corresponds[B](that: Remote[List[B]])(p: (Remote[A], Remote[B]) => Remote[Boolean]): Remote[Boolean] =
    toList.corresponds(that)(p).trackInternal("Set#corresponds")

  def count(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    toList.count(p).trackInternal("Set#count")

  def diff(that: Remote[Set[A]]): Remote[Set[A]] =
    toList.diff(that.toList).toSet.trackInternal("Set#diff")

  def drop(n: Remote[Int]): Remote[Set[A]] =
    toList.drop(n).toSet.trackInternal("Set#drop")

  def dropRight(n: Remote[Int]): Remote[Set[A]] =
    toList.dropRight(n).toSet.trackInternal("Set#dropRight")

  def dropWhile(predicate: Remote[A] => Remote[Boolean]): Remote[Set[A]] =
    toList.dropWhile(predicate).toSet.trackInternal("Set#dropWhile")

  def empty: Remote[Set[A]] =
    Remote.nil[A].toSet.trackInternal("Set#empty")

  def excl(elem: Remote[A]): Remote[Set[A]] =
    toList.filter(_ !== elem).toSet.trackInternal("Set#excl")

  def exists(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    toList.exists(p).trackInternal("Set#exists")

  def filter(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[Set[A]] =
    toList.filter(predicate).toSet.trackInternal("Set#filter")

  def filterNot(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[Set[A]] =
    toList.filterNot(predicate).toSet.trackInternal("Set#filterNot")

  def find(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    toList.find(p).trackInternal("Set#find")

  def flatMap[B](f: Remote[A] => Remote[Set[B]]): Remote[Set[B]] =
    toList.flatMap(f(_).toList).toSet.trackInternal("Set#flatMap")

  def flatten[B](implicit ev: A <:< Set[B]): Remote[Set[B]] =
    toList.map(a => a.widen[Set[B]].toList).flatten.toSet.trackInternal("Set#flatten")

  def fold[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    toList.fold(initial)(f).trackInternal("Set#fold")

  def foldLeft[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    toList.foldLeft(initial)(f).trackInternal("Set#foldLeft")

  def foldRight[B](initial: Remote[B])(
    f: (Remote[A], Remote[B]) => Remote[B]
  ): Remote[B] =
    toList.foldRight(initial)(f).trackInternal("Set#foldRight")

  def forall(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    toList.forall(p).trackInternal("Set#forall")

  def groupBy[K](f: Remote[A] => Remote[K]): Remote[Map[K, Set[A]]] =
    toList.groupBy(f).map(pair => (pair._1, pair._2.toSet)).trackInternal("Set#groupBy")

  def groupMap[K, B](key: Remote[A] => Remote[K])(f: Remote[A] => Remote[B]): Remote[Map[K, Set[B]]] =
    toList.groupMap(key)(f).map(pair => (pair._1, pair._2.toSet)).trackInternal("Set#groupMap")

  def groupMapReduce[K, B](key: Remote[A] => Remote[K])(f: Remote[A] => Remote[B])(
    reduce: (Remote[B], Remote[B]) => Remote[B]
  ): Remote[Map[K, B]] =
    toList
      .groupMapReduce(key)(f)(reduce)
      .trackInternal("Set#groupMapReduce")

  def head: Remote[A] =
    toList.head.trackInternal("Set#head")

  def headOption: Remote[Option[A]] =
    toList.headOption.trackInternal("Set#headOption")

  def incl(elem: Remote[A]): Remote[Set[A]] =
    toList.prepended(elem).toSet.trackInternal("Set#incl")

  def init: Remote[Set[A]] =
    toList.init.toSet.trackInternal("Set#init")

  def inits: Remote[List[Set[A]]] =
    toList.inits.map(_.toSet).trackInternal("Set#inits")

  def intersect(that: Remote[Set[A]]): Remote[Set[A]] =
    toList.intersect(that.toList).toSet.trackInternal("Set#intersect")

  def isEmpty: Remote[Boolean] =
    toList.isEmpty.trackInternal("Set#isEmpty")

  def last: Remote[A] =
    toList.last.trackInternal("Set#last")

  def lastOption: Remote[Option[A]] =
    toList.lastOption.trackInternal("Set#lastOption")

  def map[B](f: Remote[A] => Remote[B]): Remote[Set[B]] =
    toList.map(f).toSet.trackInternal("Set#map")

  def max(implicit schema: Schema[A]): Remote[A] =
    toList.max.trackInternal("Set#max")

  def maxBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    toList.maxBy(f).trackInternal("Set#maxBy")

  def maxByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    toList.maxByOption(f).trackInternal("Set#maxByOption")

  def maxOption(implicit schema: Schema[A]): Remote[Option[A]] =
    toList.maxOption.trackInternal("Set#maxOption")

  def min(implicit schema: Schema[A]): Remote[A] =
    toList.min.trackInternal("Set#min")

  def minBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    toList.minBy(f).trackInternal("Set#minBy")

  def minByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    toList.minByOption(f).trackInternal("Set#minByOption")

  def minOption(implicit schema: Schema[A]): Remote[Option[A]] =
    toList.minOption.trackInternal("Set#minOption")

  def mkString(implicit schema: Schema[A]): Remote[String] =
    toList.mkString.trackInternal("Set#mkString")

  def mkString(sep: Remote[String])(implicit schema: Schema[A]): Remote[String] =
    toList.mkString(sep).trackInternal("Set#mkString")

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String])(implicit
    schema: Schema[A]
  ): Remote[String] =
    toList.mkString(start, sep, end).trackInternal("Set#mkString")

  def nonEmpty: Remote[Boolean] =
    toList.nonEmpty.trackInternal("Set#nonEmpty")

  def partition(p: Remote[A] => Remote[Boolean]): Remote[(Set[A], Set[A])] =
    Remote
      .bind(toList.partition(p)) { tuple =>
        (tuple._1.toSet, tuple._2.toSet)
      }
      .trackInternal("Set#partition")

  def partitionMap[A1, A2](p: Remote[A] => Remote[Either[A1, A2]]): Remote[(Set[A1], Set[A2])] =
    Remote
      .bind(toList.partitionMap(p)) { tuple =>
        (tuple._1.toSet, tuple._2.toSet)
      }
      .trackInternal("Set#partitionMap")

  def product(implicit numeric: Numeric[A]): Remote[A] =
    toList.product.trackInternal("Set#product")

  def reduce[B >: A](op: (Remote[B], Remote[B]) => Remote[B]): Remote[B] =
    toList.reduce(op).trackInternal("Set#reduce")

  def reduceLeft[B >: A](op: (Remote[B], Remote[A]) => Remote[B]): Remote[B] =
    toList.reduceLeft(op).trackInternal("Set#reduceLeft")

  def reduceLeftOption[B >: A](op: (Remote[B], Remote[A]) => Remote[B]): Remote[Option[B]] =
    toList.reduceLeftOption(op).trackInternal("Set#reduceLeftOption")

  def reduceOption[B >: A](op: (Remote[B], Remote[B]) => Remote[B]): Remote[Option[B]] =
    toList.reduceOption(op).trackInternal("Set#reduceOption")

  def reduceRight[B >: A](op: (Remote[A], Remote[B]) => Remote[B]): Remote[B] =
    toList.reduceRight(op).trackInternal("Set#reduceRight")

  def reduceRightOption[B >: A](op: (Remote[A], Remote[B]) => Remote[B]): Remote[Option[B]] =
    toList.reduceRightOption(op).trackInternal("Set#reduceRightOption")

  def removedAll(that: Remote[List[A]]): Remote[Set[A]] =
    diff(that.toSet).trackInternal("Set#removedAll")

  def scan[B >: A](z: Remote[B])(op: (Remote[B], Remote[B]) => Remote[B]): Remote[Set[B]] =
    toList.scan(z)(op).toSet.trackInternal("Set#scan")

  def scanLeft[B >: A](z: Remote[B])(op: (Remote[B], Remote[A]) => Remote[B]): Remote[Set[B]] =
    toList.scanLeft(z)(op).toSet.trackInternal("Set#scanLeft")

  def scanRight[B >: A](z: Remote[B])(op: (Remote[A], Remote[B]) => Remote[B]): Remote[Set[B]] =
    toList.scanRight(z)(op).toSet.trackInternal("Set#scanRight")

  def size: Remote[Int] =
    toList.size.trackInternal("Set#size")

  def slice(from: Remote[Int], until: Remote[Int]): Remote[Set[A]] =
    toList.slice(from, until).toSet.trackInternal("Set#slice")

  def sliding(size: Remote[Int], step: Remote[Int]): Remote[List[Set[A]]] =
    toList.sliding(size, step).map(_.toSet).trackInternal("Set#sliding")

  def sliding(size: Remote[Int]): Remote[List[Set[A]]] =
    sliding(size, 1)

  def span(p: Remote[A] => Remote[Boolean]): Remote[(Set[A], Set[A])] =
    Remote
      .bind(toList.span(p)) { tuple =>
        (tuple._1.toSet, tuple._2.toSet)
      }
      .trackInternal("Set#span")

  def splitAt(n: Remote[Int]): Remote[(Set[A], Set[A])] =
    Remote.bind(toList.splitAt(n)) { tuple =>
      (tuple._1.toSet, tuple._2.toSet).trackInternal("Set#splitAt")
    }

  def subsetOf(that: Remote[Set[A]]): Remote[Boolean] =
    forall(that.contains).trackInternal("Set#subsetOf")

  def sum(implicit numeric: Numeric[A]): Remote[A] =
    toList.sum.trackInternal("Set#sum")

  def tail: Remote[Set[A]] =
    toList.tail.toSet.trackInternal("Set#tail")

  def tails: Remote[List[Set[A]]] =
    toList.tails.map(_.toSet).trackInternal("Set#tails")

  def take(n: Remote[Int]): Remote[Set[A]] =
    toList.take(n).toSet.trackInternal("Set#take")

  def takeRight(n: Remote[Int]): Remote[Set[A]] =
    toList.takeRight(n).toSet.trackInternal("Set#takeRight")

  def takeWhile(p: Remote[A] => Remote[Boolean]): Remote[Set[A]] =
    toList.takeWhile(p).toSet.trackInternal("Set#takeWhile")

  def toList: Remote[List[A]] =
    Remote.SetToList(self).trackInternal("Set#toList")

  def toMap[K, V](implicit ev: A <:< (K, V)): Remote[Map[K, V]] =
    toList.toMap.trackInternal("Set#toMap")

  def toSet: Remote[Set[A]] =
    self

  def union(that: Remote[Set[A]]): Remote[Set[A]] =
    toList.concat(that.toList).toSet.trackInternal("Set#union")

  def unzip[A1, A2](implicit ev: A =:= (A1, A2)): Remote[(Set[A1], Set[A2])] =
    Remote
      .bind(toList.unzip) { tuple =>
        (tuple._1.toSet, tuple._2.toSet)
      }
      .trackInternal("Set#unzip")

  def unzip3[A1, A2, A3](implicit ev: A =:= (A1, A2, A3)): Remote[(Set[A1], Set[A2], Set[A3])] =
    Remote
      .bind(toList.unzip3) { tuple =>
        (tuple._1.toSet, tuple._2.toSet, tuple._3.toSet)
      }
      .trackInternal("Set#unzip3")

  def zip[B](that: Remote[Set[B]]): Remote[Set[(A, B)]] =
    toList.zip(that.toList).toSet.trackInternal("Set#zip")

  def zipAll[B](that: Remote[List[B]], thisElem: Remote[A], thatElem: Remote[B]): Remote[Set[(A, B)]] =
    toList.zipAll(that.toList, thisElem, thatElem).toSet.trackInternal("Set#zipAll")

  def zipWithIndex: Remote[Set[(A, Int)]] =
    toList.zipWithIndex.toSet.trackInternal("Set#zipWithIndex")

  def |(that: Remote[Set[A]]): Remote[Set[A]] =
    union(that).trackInternal("Set#|")
}

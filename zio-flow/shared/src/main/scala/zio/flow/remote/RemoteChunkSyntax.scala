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

package zio.flow.remote

import zio.Chunk
import zio.flow._
import zio.flow.debug.TrackRemotes
import zio.flow.remote.numeric._
import zio.schema.Schema

final class RemoteChunkSyntax[A](val self: Remote[Chunk[A]], trackingEnabled: Boolean) {
  implicit private val remoteTracking: InternalRemoteTracking = InternalRemoteTracking(trackingEnabled)
  private val syntax                                          = TrackRemotes.ifEnabled
  import syntax._

  def ++[B >: A](that: Remote[Chunk[B]]): Remote[Chunk[B]] =
    concat(that).trackInternal("Chunk#++")

  def ++:(prefix: Remote[Chunk[A]]): Remote[Chunk[A]] =
    Chunk.fromList(prefix.toList ++: self.toList).trackInternal("Chunk#++:")

  def +:[B >: A](elem: Remote[B]): Remote[Chunk[B]] =
    Chunk.fromList(elem +: self.toList).trackInternal("Chunk#+:")

  def :+[B >: A](elem: Remote[B]): Remote[Chunk[B]] =
    Chunk.fromList(self.toList :+ elem).trackInternal("Chunk#:+")

  def :++[B >: A](suffix: Remote[Chunk[B]]): Remote[Chunk[B]] =
    Chunk.fromList(self.toList :++ suffix.toList).trackInternal("Chunk#:++")

  def appended[B >: A](elem: Remote[B]): Remote[Chunk[B]] =
    self :+ elem

  def appendedAll[B >: A](suffix: Remote[Chunk[B]]): Remote[Chunk[B]] =
    self :++ suffix

  def apply(index: Remote[Int]): Remote[A] =
    toList.apply(index).trackInternal("Chunk#apply")

  def concat[B >: A](that: Remote[Chunk[B]]): Remote[Chunk[B]] =
    Chunk.fromList(toList.concat(that.toList)).trackInternal("Chunk#concat")

  def contains(elem: Remote[A]): Remote[Boolean] =
    toList.contains(elem).trackInternal("Chunk#contains")

  def containsSlice[B >: A](that: Remote[Chunk[B]]): Remote[Boolean] =
    toList.containsSlice(that.toList).trackInternal("Chunk#containsSlice")

  def corresponds[B](that: Remote[Chunk[B]])(p: (Remote[A], Remote[B]) => Remote[Boolean]): Remote[Boolean] =
    toList.corresponds(that.toList)(p).trackInternal("Chunk#corresponds")

  def count(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    toList.count(p).trackInternal("Chunk#count")

  def diff(that: Remote[Chunk[A]]): Remote[Chunk[A]] =
    Chunk.fromList(toList.diff(that.toList)).trackInternal("Chunk#diff")

  def distinct: Remote[Chunk[A]] =
    Chunk.fromList(self.toList.distinct).trackInternal("Chunk#distinct")

  def distinctBy[B](f: Remote[A] => Remote[B]): Remote[Chunk[A]] =
    Chunk.fromList(self.toList.distinctBy(f)).trackInternal("Chunk#distinctBy")

  def drop(n: Remote[Int]): Remote[Chunk[A]] =
    Chunk.fromList(toList.drop(n)).trackInternal("Chunk#drop")

  def dropRight(n: Remote[Int]): Remote[Chunk[A]] =
    Chunk.fromList(toList.dropRight(n)).trackInternal("Chunk#dropRight")

  def dropWhile(predicate: Remote[A] => Remote[Boolean]): Remote[Chunk[A]] =
    Chunk.fromList(toList.dropWhile(predicate)).trackInternal("Chunk#dropWhile")

  def endsWith[B >: A](that: Remote[Chunk[B]]): Remote[Boolean] =
    self.toList.endsWith(that.toList).trackInternal("Chunk#endsWith")

  def empty: Remote[Chunk[A]] =
    Remote.emptyChunk[A].trackInternal("Chunk#empty")

  def exists(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    toList.exists(p).trackInternal("Chunk#exists")

  def filter(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[Chunk[A]] =
    Chunk.fromList(toList.filter(predicate)).trackInternal("Chunk#filter")

  def filterNot(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[Chunk[A]] =
    Chunk.fromList(toList.filterNot(predicate)).trackInternal("Chunk#filterNot")

  def find(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    toList.find(p).trackInternal("Chunk#find")

  def findLast(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    toList.findLast(p).trackInternal("Chunk#findLast")

  def flatMap[B](f: Remote[A] => Remote[Chunk[B]]): Remote[Chunk[B]] =
    Chunk.fromList(toList.flatMap(f(_).toList)).trackInternal("Chunk#flatMap")

  def flatten[B](implicit ev: A <:< Chunk[B]): Remote[Chunk[B]] =
    Chunk.fromList(toList.map(a => a.widen[Chunk[B]].toList).flatten).trackInternal("Chunk#flatten")

  def fold[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    toList.fold(initial)(f).trackInternal("Chunk#fold")

  def foldLeft[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    toList.foldLeft(initial)(f).trackInternal("Chunk#foldLeft")

  def foldRight[B](initial: Remote[B])(
    f: (Remote[A], Remote[B]) => Remote[B]
  ): Remote[B] =
    toList.foldRight(initial)(f).trackInternal("Chunk#foldRight")

  def forall(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    toList.forall(p).trackInternal("Chunk#forall")

  // TODO: groupBy etc if we have support for Remote[Map[K, V]]

  def head: Remote[A] =
    toList.head.trackInternal("Chunk#head")

  def headOption: Remote[Option[A]] =
    toList.headOption.trackInternal("Chunk#headOption")

  def indexOf[B >: A](elem: Remote[B]): Remote[Int] =
    toList.indexOf(elem).trackInternal("Chunk#indexOf")

  def indexOf[B >: A](elem: Remote[B], from: Remote[Int]): Remote[Int] =
    toList.indexOf(elem, from).trackInternal("Chunk#indexOf")

  def indexOfSlice[B >: A](that: Remote[Chunk[B]]): Remote[Int] =
    toList.indexOfSlice(that.toList).trackInternal("Chunk#indexOfSlice")

  def indexOfSlice[B >: A](that: Remote[Chunk[B]], from: Remote[Int]): Remote[Int] =
    toList.indexOfSlice(that.toList, from).trackInternal("Chunk#indexOfSlice")

  def indexWhere(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    toList.indexWhere(p).trackInternal("Chunk#indexWhere")

  def indexWhere(p: Remote[A] => Remote[Boolean], from: Remote[Int]): Remote[Int] =
    toList.indexWhere(p, from).trackInternal("Chunk#indexWhere")

  def init: Remote[Chunk[A]] =
    Chunk.fromList(toList.init).trackInternal("Chunk#init")

  def inits: Remote[Chunk[Chunk[A]]] =
    Chunk.fromList(toList.inits.map(Chunk.fromList(_))).trackInternal("Chunk#inits")

  def intersect(that: Remote[Chunk[A]]): Remote[Chunk[A]] =
    Chunk.fromList(toList.intersect(that.toList)).trackInternal("Chunk#intersect")

  def isDefinedAt(x: Remote[Int]): Remote[Boolean] =
    self.toList.isDefinedAt(x).trackInternal("Chunk#isDefinedAt")

  def isEmpty: Remote[Boolean] =
    toList.isEmpty.trackInternal("Chunk#isEmpty")

  def last: Remote[A] =
    toList.last.trackInternal("Chunk#last")

  def lastIndexOf[B >: A](elem: Remote[B]): Remote[Int] =
    toList.lastIndexOf(elem).trackInternal("Chunk#lastIndexOf")

  def lastIndexOf[B >: A](elem: Remote[B], from: Remote[Int]): Remote[Int] =
    toList.lastIndexOf(elem, from).trackInternal("Chunk#lastIndexOf")

  def lastIndexOfSlice[B >: A](that: Remote[Chunk[B]]): Remote[Int] =
    toList.lastIndexOfSlice(that.toList).trackInternal("Chunk#lastIndexOfSlice")

  def lastIndexOfSlice[B >: A](that: Remote[Chunk[B]], from: Remote[Int]): Remote[Int] =
    toList.lastIndexOfSlice(that.toList, from).trackInternal("Chunk#lastIndexOfSlice")

  def lastIndexWhere(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    toList.lastIndexWhere(p).trackInternal("Chunk#lastIndexWhere")

  def lastIndexWhere(p: Remote[A] => Remote[Boolean], from: Remote[Int]): Remote[Int] =
    toList.lastIndexWhere(p, from).trackInternal("Chunk#lastIndexWhere")

  def lastOption: Remote[Option[A]] =
    toList.lastOption.trackInternal("Chunk#lastOption")

  def length: Remote[Int] =
    toList.length.trackInternal("Chunk#length")

  def map[B](f: Remote[A] => Remote[B]): Remote[Chunk[B]] =
    Chunk.fromList(toList.map(f)).trackInternal("Chunk#map")

  def max(implicit schema: Schema[A]): Remote[A] =
    toList.max.trackInternal("Chunk#max")

  def maxBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    toList.maxBy(f).trackInternal("Chunk#maxBy")

  def maxByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    toList.maxByOption(f).trackInternal("Chunk#maxByOption")

  def maxOption(implicit schema: Schema[A]): Remote[Option[A]] =
    toList.maxOption.trackInternal("Chunk#maxOption")

  def min(implicit schema: Schema[A]): Remote[A] =
    toList.min.trackInternal("Chunk#min")

  def minBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    toList.minBy(f).trackInternal("Chunk#minBy")

  def minByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    toList.minByOption(f).trackInternal("Chunk#minByOption")

  def minOption(implicit schema: Schema[A]): Remote[Option[A]] =
    toList.minOption.trackInternal("Chunk#minOption")

  def mkString(implicit schema: Schema[A]): Remote[String] =
    toList.mkString.trackInternal("Chunk#mkString")

  def mkString(sep: Remote[String])(implicit schema: Schema[A]): Remote[String] =
    toList.mkString(sep).trackInternal("Chunk#mkString")

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String])(implicit
    schema: Schema[A]
  ): Remote[String] =
    toList.mkString(start, sep, end).trackInternal("Chunk#mkString")

  def nonEmpty: Remote[Boolean] =
    toList.nonEmpty.trackInternal("Chunk#nonEmpty")

  def padTo[B >: A](len: Remote[Int], elem: Remote[B]): Remote[Chunk[B]] =
    Chunk.fromList(self.toList.padTo(len, elem)).trackInternal("Chunk#padTo")

  def partition(p: Remote[A] => Remote[Boolean]): Remote[(Chunk[A], Chunk[A])] =
    Remote
      .bind(toList.partition(p)) { tuple =>
        (Chunk.fromList(tuple._1), Chunk.fromList(tuple._2))
      }
      .trackInternal("Chunk#partition")

  def partitionMap[A1, A2](p: Remote[A] => Remote[Either[A1, A2]]): Remote[(Chunk[A1], Chunk[A2])] =
    Remote
      .bind(toList.partitionMap(p)) { tuple =>
        (Chunk.fromList(tuple._1), Chunk.fromList(tuple._2))
      }
      .trackInternal("Chunk#partitionMap")

  def patch[B >: A](from: Remote[Int], other: Remote[Chunk[B]], replaced: Remote[Int]): Remote[Chunk[B]] =
    Chunk.fromList(toList.patch(from, other.toList, replaced)).trackInternal("Chunk#patch")

  def permutations: Remote[Chunk[Chunk[A]]] =
    Chunk.fromList(self.toList.permutations.map(Chunk.fromList(_))).trackInternal("Chunk#permutations")

  def prepended[B >: A](elem: Remote[B]): Remote[Chunk[B]] =
    Chunk.fromList(toList.prepended(elem)).trackInternal("Chunk#prepended")

  def prependedAll[B >: A](prefix: Remote[Chunk[B]]): Remote[Chunk[B]] =
    Chunk.fromList(toList.prependedAll(prefix.toList)).trackInternal("Chunk#prependedAll")

  def product(implicit numeric: Numeric[A]): Remote[A] =
    toList.product.trackInternal("Chunk#product")

  def reduce[B >: A](op: (Remote[B], Remote[B]) => Remote[B]): Remote[B] =
    toList.reduce(op).trackInternal("Chunk#reduce")

  def reduceLeft[B >: A](op: (Remote[B], Remote[A]) => Remote[B]): Remote[B] =
    toList.reduceLeft(op).trackInternal("Chunk#reduceLeft")

  def reduceLeftOption[B >: A](op: (Remote[B], Remote[A]) => Remote[B]): Remote[Option[B]] =
    toList.reduceLeftOption(op).trackInternal("Chunk#reduceLeftOption")

  def reduceOption[B >: A](op: (Remote[B], Remote[B]) => Remote[B]): Remote[Option[B]] =
    toList.reduceOption(op).trackInternal("Chunk#reduceOption")

  def reduceRight[B >: A](op: (Remote[A], Remote[B]) => Remote[B]): Remote[B] =
    toList.reduceRight(op).trackInternal("Chunk#reduceRight")

  def reduceRightOption[B >: A](op: (Remote[A], Remote[B]) => Remote[B]): Remote[Option[B]] =
    toList.reduceRightOption(op).trackInternal("Chunk#reduceRightOption")

  def removedAll(that: Remote[Chunk[A]]): Remote[Chunk[A]] =
    diff(Chunk.fromList(that.toList)).trackInternal("Chunk#removedAll")

  def reverse: Remote[Chunk[A]] =
    Chunk.fromList(toList.reverse).trackInternal("Chunk#reverse")

  def sameElements[B >: A](other: Remote[Chunk[B]]): Remote[Boolean] =
    toList.sameElements(other.toList).trackInternal("Chunk#sameElements")

  def scan[B >: A](z: Remote[B])(op: (Remote[B], Remote[B]) => Remote[B]): Remote[Chunk[B]] =
    Chunk.fromList(toList.scan(z)(op)).trackInternal("Chunk#scan")

  def scanLeft[B >: A](z: Remote[B])(op: (Remote[B], Remote[A]) => Remote[B]): Remote[Chunk[B]] =
    Chunk.fromList(toList.scanLeft(z)(op)).trackInternal("Chunk#scanLeft")

  def scanRight[B >: A](z: Remote[B])(op: (Remote[A], Remote[B]) => Remote[B]): Remote[Chunk[B]] =
    Chunk.fromList(toList.scanRight(z)(op)).trackInternal("Chunk#scanRight")

  def segmentLength(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    toList.segmentLength(p).trackInternal("Chunk#segmentLength")

  def segmentLength(p: Remote[A] => Remote[Boolean], from: Remote[Int]): Remote[Int] =
    toList.segmentLength(p, from).trackInternal("Chunk#segmentLength")

  def size: Remote[Int] =
    toList.size.trackInternal("Chunk#size")

  def slice(from: Remote[Int], until: Remote[Int]): Remote[Chunk[A]] =
    Chunk.fromList(toList.slice(from, until)).trackInternal("Chunk#slice")

  def sliding(size: Remote[Int], step: Remote[Int]): Remote[Chunk[Chunk[A]]] =
    Chunk.fromList(toList.sliding(size, step).map(Chunk.fromList(_))).trackInternal("Chunk#sliding")

  def sliding(size: Remote[Int]): Remote[Chunk[Chunk[A]]] =
    sliding(size, 1)

  def span(p: Remote[A] => Remote[Boolean]): Remote[(Chunk[A], Chunk[A])] =
    Remote.bind(toList.span(p)) { tuple =>
      (Chunk.fromList(tuple._1), Chunk.fromList(tuple._2)).trackInternal("Chunk#span")
    }

  def splitAt(n: Remote[Int]): Remote[(Chunk[A], Chunk[A])] =
    Remote.bind(toList.splitAt(n)) { tuple =>
      (Chunk.fromList(tuple._1), Chunk.fromList(tuple._2)).trackInternal("Chunk#splitAt")
    }

  def sum(implicit numeric: Numeric[A]): Remote[A] =
    toList.sum.trackInternal("Chunk#sum")

  def tail: Remote[Chunk[A]] =
    Chunk.fromList(toList.tail).trackInternal("Chunk#tail")

  def tails: Remote[Chunk[Chunk[A]]] =
    Chunk.fromList(toList.tails.map(Chunk.fromList(_))).trackInternal("Chunk#tails")

  def take(n: Remote[Int]): Remote[Chunk[A]] =
    Chunk.fromList(toList.take(n)).trackInternal("Chunk#take")

  def takeRight(n: Remote[Int]): Remote[Chunk[A]] =
    Chunk.fromList(toList.takeRight(n)).trackInternal("Chunk#takeRight")

  def takeWhile(p: Remote[A] => Remote[Boolean]): Remote[Chunk[A]] =
    Chunk.fromList(toList.takeWhile(p)).trackInternal("Chunk#takeWhile")

  def toList: Remote[List[A]] =
    self.asInstanceOf[Remote[List[A]]].trackInternal("Chunk#toList")

  def toSet: Remote[Set[A]] =
    toList.toSet.trackInternal("Chunk#toSet")

  def unzip[A1, A2](implicit ev: A =:= (A1, A2)): Remote[(Chunk[A1], Chunk[A2])] =
    Remote
      .bind(toList.unzip) { tuple =>
        (Chunk.fromList(tuple._1), Chunk.fromList(tuple._2))
      }
      .trackInternal("Chunk#unzip")

  def unzip3[A1, A2, A3](implicit ev: A =:= (A1, A2, A3)): Remote[(Chunk[A1], Chunk[A2], Chunk[A3])] =
    Remote
      .bind(toList.unzip3) { tuple =>
        (Chunk.fromList(tuple._1), Chunk.fromList(tuple._2), Chunk.fromList(tuple._3))
      }
      .trackInternal("Chunk#unzip3")

  def zip[B](that: Remote[Chunk[B]]): Remote[Chunk[(A, B)]] =
    Chunk.fromList(toList.zip(that.toList)).trackInternal("Chunk#zip")

  def zipAll[B](that: Remote[Chunk[B]], thisElem: Remote[A], thatElem: Remote[B]): Remote[Chunk[(A, B)]] =
    Chunk.fromList(toList.zipAll(that.toList, thisElem, thatElem)).trackInternal("Chunk#zipALl")

  def zipWithIndex: Remote[Chunk[(A, Int)]] =
    Chunk.fromList(toList.zipWithIndex).trackInternal("Chunk#zipWithIndex")
}

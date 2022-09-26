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

import zio.flow._

final class RemoteStringSyntax(val self: Remote[String]) extends AnyVal {

  def *(n: Remote[Int]): Remote[String] =
    Remote.recurse[(Int, String), String]((n, self)) { (input, rec) =>
      val remaining = input._1
      val current   = input._2
      (remaining === 0).ifThenElse(
        ifTrue = current,
        ifFalse = rec((remaining - 1, current + self))
      )
    }

  def +(other: Remote[String]): Remote[String] =
    (self.toList ++ other.toList).mkString

  def ++(other: Remote[String]): Remote[String] =
    self + other

  def ++:(other: Remote[String]): Remote[String] =
    (self.toList ++: other.toList).mkString

  def +:(c: Remote[Char]): Remote[String] =
    (c +: self.toList).mkString

  def :+(c: Remote[Char]): Remote[String] =
    (self.toList :+ c).mkString

  def :++(other: Remote[String]): Remote[String] =
    (self.toList :++ other.toList).mkString

  def appended(c: Remote[Char]): Remote[String] =
    self :+ c

  def appendedAll(other: Remote[String]): Remote[String] =
    self :++ other

  def apply(ix: Remote[Int]): Remote[Char] =
    self.toList.apply(ix)

  def capitalize: Remote[String] =
    self.headOption.fold(
      Remote("")
    )(head => head.toUpper +: self.tail)

  def charAt(index: Remote[Int]): Remote[Char] =
    apply(index)

  def combinations(n: Remote[Int]): Remote[List[String]] =
    ???

  def concat(suffix: Remote[String]): Remote[String] =
    self :++ suffix

  def contains(elem: Remote[Char]): Remote[Boolean] =
    self.toList.contains(elem)

  def count(p: Remote[Char] => Remote[Boolean]): Remote[Int] =
    self.toList.count(p)

  def diff[B >: Char](that: Remote[List[B]]): Remote[String] =
    self.toList.diff(that).mkString

  def distinct: Remote[String] =
    self.toList.distinct.mkString

  def distinctBy(f: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.distinctBy(f).mkString

  def drop(n: Remote[Int]): Remote[String] =
    self.toList.drop(n).mkString

  def dropRight(n: Remote[Int]): Remote[String] =
    self.toList.dropRight(n).mkString

  def dropWhile(predicate: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.dropWhile(predicate).mkString

  def endsWith(suffix: Remote[String]): Remote[Boolean] =
    ???

  def exists(p: Remote[Char] => Remote[Boolean]): Remote[Boolean] =
    self.toList.exists(p)

  def filter(p: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.filter(p).mkString

  def filterNot(p: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.filterNot(p).mkString

  def find(p: Remote[Char] => Remote[Boolean]): Remote[Option[Char]] =
    self.toList.find(p)

  def flatMap(f: Remote[Char] => Remote[String]): Remote[String] =
    self.toList.flatMap((ch: Remote[Char]) => f(ch).toList).mkString

  def fold[A1 >: Char](z: Remote[A1])(op: (Remote[A1], Remote[A1]) => Remote[A1]): Remote[A1] =
    self.toList.fold(z)(op)

  def foldLeft[B](z: Remote[B])(op: (Remote[B], Remote[Char]) => Remote[B]): Remote[B] =
    self.toList.foldLeft(z)(op)

  def foldRight[B](z: Remote[B])(op: (Remote[Char], Remote[B]) => Remote[B]): Remote[B] =
    self.toList.foldRight(z)(op)

  def forall(p: Remote[Char] => Remote[Boolean]): Remote[Boolean] =
    self.toList.forall(p)

  // TODO: groupBy if we have support for Remote[Map[K, V]]

  def grouped(n: Remote[Int]): Remote[List[String]] =
    ???

  def head: Remote[Char] =
    self.toList.head

  def headOption: Remote[Option[Char]] =
    self.toList.headOption

  def indexOf(ch: Remote[Char]): Remote[Int] =
    self.toList.indexOf(ch)

  def indexOf(ch: Remote[Char], from: Remote[Int]): Remote[Int] =
    self.toList.indexOf(ch, from)

  def indexOf(s: Remote[String]): Remote[Int] =
    self.toList.indexOfSlice(s.toList)

  def indexOf(s: Remote[String], from: Remote[Int]): Remote[Int] =
    self.toList.indexOfSlice(s.toList, from)

  def indexWhere(p: Remote[Char] => Remote[Boolean], from: Remote[Int] = Remote(0)): Remote[Int] =
    self.toList.indexWhere(p, from)

  def init: Remote[String] =
    self.toList.init.mkString

  def inits: Remote[List[String]] =
    self.toList.inits.map(_.mkString)

  def intersect(other: Remote[String]): Remote[String] =
    self.toList.intersect(other.toList).mkString

  def isEmpty: Remote[Boolean] =
    self.length === 0

  def knownSize: Remote[Int] = self.length

  def last: Remote[Char] =
    self.toList.last

  def lastIndexOf(ch: Remote[Char]): Remote[Int] =
    self.toList.lastIndexOf(ch)

  def lastIndexOf(ch: Remote[Char], from: Remote[Int]): Remote[Int] =
    self.toList.lastIndexOf(ch, from)

  def lastIndexOf(s: Remote[String]): Remote[Int] =
    self.toList.lastIndexOfSlice(s.toList)

  def lastIndexOf(s: Remote[String], from: Remote[Int]): Remote[Int] =
    self.toList.lastIndexOfSlice(s.toList, from)

  def lastIndexWhere(p: Remote[Char] => Remote[Boolean], from: Remote[Int] = Remote(0)): Remote[Int] =
    self.toList.lastIndexWhere(p, from)

  def lastOption: Remote[Option[Char]] =
    self.toList.lastOption

  def length: Remote[Int] =
    Remote.Length(self)

  def map(f: Remote[Char] => Remote[Char]): Remote[String] =
    self.toList.map(f).mkString

  def mkString: Remote[String] = self
  def mkString(sep: Remote[String]): Remote[String] =
    self.toList.mkString(sep)

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String]): Remote[String] =
    self.toList.mkString(start, sep, end)

  def nonEmpty: Remote[Boolean] =
    self.length > 0

  def padTo(len: Remote[Int], elem: Remote[Char]): Remote[String] =
    self.toList.padTo(len, elem).mkString

  def partition(p: Remote[Char] => Remote[Boolean]): (Remote[String], Remote[String]) = {
    val tuple = self.toList.partition(p)
    (tuple._1.mkString, tuple._2.mkString)
  }

  def partitionMap(p: Remote[Char] => Remote[Either[Char, Char]]): (Remote[String], Remote[String]) = {
    val tuple = self.toList.partitionMap(p)
    (tuple._1.mkString, tuple._2.mkString)
  }

  def patch(from: Remote[Int], other: Remote[String], replaced: Remote[Int]): Remote[String] =
    self.toList.patch(from, other.toList, replaced).mkString

  def permutations: Remote[List[String]] =
    self.toList.permutations.map(_.mkString)

  def prepended(c: Remote[Char]): Remote[String] =
    c +: self

  def prependedAll(prefix: Remote[String]): Remote[String] =
    prefix ++: self

  // TODO: convert to regex once remote regex is supported

  def replace(oldChar: Remote[Char], newChar: Remote[Char]): Remote[String] =
    ???

  def replaceAll(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    ???

  def replaceFirst(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    ???

  def reverse: Remote[String] =
    self.toList.reverse.mkString

  def size: Remote[Int] =
    self.length

  def slice(from: Remote[Int], until: Remote[Int]): Remote[String] =
    self.toList.slice(from, until).mkString

  def sliding(size: Remote[Int], step: Remote[Int] = Remote(1)): Remote[List[String]] =
    self.toList.sliding(size, step).map(_.mkString)

  // TODO: sortBy/sortWith/sorted when list supports sort

  def span(p: Remote[Char] => Remote[Boolean]): (Remote[String], Remote[String]) = {
    val tuple = self.toList.span(p)
    (tuple._1.mkString, tuple._2.mkString)
  }

  def split(separators: Remote[List[Char]]): Remote[List[String]] =
    ???

  def split(separator: Remote[Char]): Remote[List[String]] =
    ???

  def splitAt(n: Remote[Int]): (Remote[String], Remote[String]) = {
    val tuple = self.toList.splitAt(n)
    (tuple._1.mkString, tuple._2.mkString)
  }

  def startsWith(prefix: Remote[String]): Remote[Boolean] =
    ???

  def stripLineEnd: Remote[String] =
    ???

  def stripMargin: Remote[String] =
    ???

  def stripPrefix(prefix: Remote[String]): Remote[String] =
    ???

  def stripSuffix(suffix: Remote[String]): Remote[String] =
    ???

  def substring(begin: Remote[Int], end: Remote[Int]): Remote[String] =
    self.toList.slice(begin, end).mkString

  def tail: Remote[String] =
    self.toList.tail.mkString

  def tails: Remote[List[String]] =
    self.toList.tails.map(_.mkString)

  def take(n: Remote[Int]): Remote[String] =
    self.toList.take(n).mkString

  def takeRight(n: Remote[Int]): Remote[String] =
    self.toList.takeRight(n).mkString

  def takeWhile(p: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.takeWhile(p).mkString

  def toBoolean: Remote[Boolean] =
    ???

  def toBooleanOption: Remote[Option[Boolean]] =
    ???

  def toByte: Remote[Byte] =
    ???

  def toByteOption: Remote[Option[Byte]] =
    ???

  def toDouble: Remote[Double] =
    ???

  def toDoubleOption: Remote[Option[Double]] =
    ???

  def toFloat: Remote[Float] =
    ???

  def toFloatOption: Remote[Option[Float]] =
    ???

  def toInt: Remote[Int] =
    ???

  def toIntOption: Remote[Option[Int]] =
    ???

  def toList: Remote[List[Char]] =
    Remote.StringToCharList(self)

  def toLong: Remote[Long] =
    ???

  def toLongOption: Remote[Option[Long]] =
    ???

  def toLowerCase: Remote[String] =
    self.toList.map(_.toLower).mkString

  def toShort: Remote[Short] =
    ???

  def toShortOption: Remote[Option[Short]] =
    ???

  def toUpperCase: Remote[String] =
    self.toList.map(_.toUpper).mkString

  def trim: Remote[String] =
    ???
}

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
import zio.flow.remote.numeric._

import scala.annotation.nowarn

final class RemoteStringSyntax(val self: Remote[String]) extends AnyVal {

  def *(n: Remote[Int]): Remote[String] =
    Remote.recurse[(Int, String), String]((n, self)) { (input, rec) =>
      val remaining = input._1
      val current   = input._2
      (remaining === 1).ifThenElse(
        ifTrue = current,
        ifFalse = rec((remaining - 1, current + self))
      )
    }

  def +(other: Remote[String]): Remote[String] =
    (self.toList ++ other.toList).mkString

  def ++(other: Remote[String]): Remote[String] =
    self + other

  def ++:(other: Remote[String]): Remote[String] =
    (self.toList.++:(other.toList)).mkString

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

  @nowarn def combinations(n: Remote[Int]): Remote[List[String]] =
    Remote.fail(s"TODO: not implemented") // TODO

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

  def distinctBy[B](f: Remote[Char] => Remote[B]): Remote[String] =
    self.toList.distinctBy(f).mkString

  def drop(n: Remote[Int]): Remote[String] =
    self.toList.drop(n).mkString

  def dropRight(n: Remote[Int]): Remote[String] =
    self.toList.dropRight(n).mkString

  def dropWhile(predicate: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.dropWhile(predicate).mkString

  def endsWith(suffix: Remote[String]): Remote[Boolean] =
    self.toList.endsWith(suffix.toList)

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

  @nowarn def grouped(n: Remote[Int]): Remote[List[String]] =
    Remote.fail(s"TODO: not implemented") // TODO

  def head: Remote[Char] =
    self.toList.head

  def headOption: Remote[Option[Char]] =
    self.toList.headOption

  def indexOf[X](value: Remote[X])(implicit ev: RemoteTypeEither[X, Char, String]): Remote[Int] =
    ev.fold(
      ch => self.toList.indexOf(ch),
      s => self.toList.indexOfSlice(s.toList)
    )(value)

  def indexOf[X](value: Remote[X], from: Remote[Int])(implicit ev: RemoteTypeEither[X, Char, String]): Remote[Int] =
    ev.fold(
      ch => self.toList.indexOf(ch, from),
      s => self.toList.indexOfSlice(s.toList, from)
    )(value)

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

  def lastIndexOf[X](value: Remote[X])(implicit ev: RemoteTypeEither[X, Char, String]): Remote[Int] =
    ev.fold(
      ch => self.toList.lastIndexOf(ch),
      s => self.toList.lastIndexOfSlice(s.toList)
    )(value)

  def lastIndexOf[X](value: Remote[X], from: Remote[Int])(implicit ev: RemoteTypeEither[X, Char, String]): Remote[Int] =
    ev.fold(
      ch => self.toList.lastIndexOf(ch, from),
      s => self.toList.lastIndexOfSlice(s.toList, from)
    )(value)

  def lastIndexWhere(p: Remote[Char] => Remote[Boolean]): Remote[Int] =
    self.toList.lastIndexWhere(p)

  def lastIndexWhere(p: Remote[Char] => Remote[Boolean], from: Remote[Int]): Remote[Int] =
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

  def partition(p: Remote[Char] => Remote[Boolean]): Remote[(String, String)] = {
    val tuple = self.toList.partition(p)
    Remote.tuple2((tuple._1.mkString, tuple._2.mkString))
  }

  def partitionMap(p: Remote[Char] => Remote[Either[Char, Char]]): Remote[(String, String)] = {
    val tuple = self.toList.partitionMap(p)
    Remote.tuple2((tuple._1.mkString, tuple._2.mkString))
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
    self.map(ch => (ch === oldChar).ifThenElse(newChar, ch))

  @nowarn def replaceAll(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    Remote.fail("TODO: built-in regex replace support")

  @nowarn def replaceFirst(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    Remote.fail("TODO: built-in regex replace support")

  def reverse: Remote[String] =
    self.toList.reverse.mkString

  def size: Remote[Int] =
    self.length

  def slice(from: Remote[Int], until: Remote[Int]): Remote[String] =
    self.toList.slice(from, until).mkString

  def sliding(size: Remote[Int], step: Remote[Int] = Remote(1)): Remote[List[String]] =
    self.toList.sliding(size, step).map(_.mkString)

  // TODO: sortBy/sortWith/sorted when list supports sort

  def span(p: Remote[Char] => Remote[Boolean]): Remote[(String, String)] = {
    val tuple = self.toList.span(p)
    Remote.tuple2((tuple._1.mkString, tuple._2.mkString))
  }

  def split[X](separators: Remote[X])(implicit ev: RemoteTypeEither[X, Char, List[Char]]): Remote[List[String]] =
    ev.fold(
      ch => self.split(Remote.list(ch)),
      chs =>
        Remote.recurse[String, List[String]](self) { (remaining, rec) =>
          val tuple = remaining.toList.span(!chs.contains(_))
          val next  = tuple._2.drop(1).mkString
          (tuple._1.mkString :: next.isEmpty.ifThenElse(Remote.nil[String], rec(tuple._2.drop(1).mkString)))
        }
    )(separators)

  def splitAt(n: Remote[Int]): Remote[(String, String)] = {
    val tuple = self.toList.splitAt(n)
    Remote.tuple2((tuple._1.mkString, tuple._2.mkString))
  }

  def startsWith(prefix: Remote[String]): Remote[Boolean] =
    self.toList.startsWith(prefix.toList)

  def strip(): Remote[String] =
    self.stripLeading().stripTrailing()

  def stripLeading(): Remote[String] =
    self.dropWhile(_.isWhitespace)

  def stripLineEnd: Remote[String] =
    self.reverse.dropWhile(ch => (ch === '\n') || (ch === '\r')).reverse

  def stripMargin(char: Remote[Char]): Remote[String] =
    self
      .split('\n')
      .map(line => line.dropWhile(ch => ch.isWhitespace && (ch !== char)).drop(1))
      .mkString("\n")

  def stripPrefix(prefix: Remote[String]): Remote[String] =
    (self
      .startsWith(prefix))
      .ifThenElse(
        ifTrue = self.drop(prefix.length),
        ifFalse = self
      )

  def stripTrailing(): Remote[String] =
    self.reverse.dropWhile(_.isWhitespace).reverse

  def stripSuffix(suffix: Remote[String]): Remote[String] =
    (self
      .endsWith(suffix))
      .ifThenElse(
        ifTrue = self.take(self.length - suffix.length),
        ifFalse = self
      )

  def substring(begin: Remote[Int]): Remote[String] =
    self.toList.drop(begin).mkString

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
    toBooleanOption.fold(Remote.fail("Invalid boolean"))(n => n)

  def toBooleanOption: Remote[Option[Boolean]] =
    (self === "true").ifThenElse(
      ifTrue = Remote.some(true),
      ifFalse = (self === "false").ifThenElse(
        ifTrue = Remote.some(false),
        ifFalse = Remote.none[Boolean]
      )
    )

  def toByte: Remote[Byte] =
    Remote.fail("TODO: byte not supported")

  def toByteOption: Remote[Option[Byte]] =
    Remote.fail("TODO: byte not supported")

  def toDouble: Remote[Double] =
    toDoubleOption.fold(Remote.fail("Invalid double"))(n => n)

  def toDoubleOption: Remote[Option[Double]] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericDouble)))

  def toFloat: Remote[Float] =
    toFloatOption.fold(Remote.fail("Invalid float"))(n => n)

  def toFloatOption: Remote[Option[Float]] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericFloat)))

  def toInt: Remote[Int] =
    toIntOption.fold(Remote.fail("Invalid int"))(n => n)

  def toIntOption: Remote[Option[Int]] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericInt)))

  def toList: Remote[List[Char]] =
    Remote.StringToCharList(self)

  def toLong: Remote[Long] =
    toLongOption.fold(Remote.fail("Invalid long"))(n => n)

  def toLongOption: Remote[Option[Long]] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericLong)))

  def toLowerCase: Remote[String] =
    self.toList.map(_.toLower).mkString

  def toShort: Remote[Short] =
    toShortOption.fold(Remote.fail("Invalid short"))(n => n)

  def toShortOption: Remote[Option[Short]] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericShort)))

  def toUpperCase: Remote[String] =
    self.toList.map(_.toUpper).mkString

  def trim(): Remote[String] =
    self.strip()
}

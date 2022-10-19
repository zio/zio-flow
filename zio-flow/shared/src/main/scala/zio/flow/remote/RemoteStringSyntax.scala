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

import zio.flow.Remote
import zio.flow.debug.TrackRemotes
import zio.flow.remote.numeric._
import zio.flow.remote.text.UnaryStringOperator

import scala.annotation.nowarn

final class RemoteStringSyntax(self: Remote[String], trackingEnabled: Boolean) {
  implicit private val remoteTracking: InternalRemoteTracking = InternalRemoteTracking(trackingEnabled)
  private val syntax                                          = TrackRemotes.ifEnabled
  import syntax._

  def *(n: Remote[Int]): Remote[String] =
    Remote
      .recurse[(Int, String), String]((n, self)) { (input, rec) =>
        val remaining = input._1
        val current   = input._2
        (remaining === 1).ifThenElse(
          ifTrue = current,
          ifFalse = rec((remaining - 1, current + self))
        )
      }
      .trackInternal("String#*")

  def +(other: Remote[String]): Remote[String] =
    (self.toList ++ other.toList).mkString.trackInternal("String#+")

  def ++(other: Remote[String]): Remote[String] =
    self + other

  def ++:(other: Remote[String]): Remote[String] =
    (self.toList.++:(other.toList)).mkString.trackInternal("String#++:")

  def +:(c: Remote[Char]): Remote[String] =
    (c +: self.toList).mkString.trackInternal("String#+:")

  def :+(c: Remote[Char]): Remote[String] =
    (self.toList :+ c).mkString.trackInternal("String#:+")

  def :++(other: Remote[String]): Remote[String] =
    (self.toList :++ other.toList).mkString.trackInternal("String#:++")

  def appended(c: Remote[Char]): Remote[String] =
    self :+ c

  def appendedAll(other: Remote[String]): Remote[String] =
    self :++ other

  def apply(ix: Remote[Int]): Remote[Char] =
    self.toList.apply(ix).trackInternal("String#apply")

  def capitalize: Remote[String] =
    self.headOption
      .fold(
        Remote("")
      )(head => head.toUpper +: self.tail)
      .trackInternal("String#capitalize")

  def charAt(index: Remote[Int]): Remote[Char] =
    apply(index)

  @nowarn def combinations(n: Remote[Int]): Remote[List[String]] =
    Remote.fail(s"TODO: not implemented") // TODO

  def concat(suffix: Remote[String]): Remote[String] =
    self :++ suffix

  def contains(elem: Remote[Char]): Remote[Boolean] =
    self.toList.contains(elem).trackInternal("String#contains")

  def count(p: Remote[Char] => Remote[Boolean]): Remote[Int] =
    self.toList.count(p).trackInternal("String#count")

  def diff[B >: Char](that: Remote[List[B]]): Remote[String] =
    self.toList.diff(that).mkString.trackInternal("String#diff")

  def distinct: Remote[String] =
    self.toList.distinct.mkString.trackInternal("String#distinct")

  def distinctBy[B](f: Remote[Char] => Remote[B]): Remote[String] =
    self.toList.distinctBy(f).mkString.trackInternal("String#distinctBy")

  def drop(n: Remote[Int]): Remote[String] =
    self.toList.drop(n).mkString.trackInternal("String#drop")

  def dropRight(n: Remote[Int]): Remote[String] =
    self.toList.dropRight(n).mkString.trackInternal("String#dropRight")

  def dropWhile(predicate: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.dropWhile(predicate).mkString.trackInternal("String#dropWhile")

  def endsWith(suffix: Remote[String]): Remote[Boolean] =
    self.toList.endsWith(suffix.toList).trackInternal("String#endsWith")

  def exists(p: Remote[Char] => Remote[Boolean]): Remote[Boolean] =
    self.toList.exists(p).trackInternal("String#exists")

  def filter(p: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.filter(p).mkString.trackInternal("String#filter")

  def filterNot(p: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.filterNot(p).mkString.trackInternal("String#filterNot")

  def find(p: Remote[Char] => Remote[Boolean]): Remote[Option[Char]] =
    self.toList.find(p).trackInternal("String#find")

  def flatMap(f: Remote[Char] => Remote[String]): Remote[String] =
    self.toList.flatMap((ch: Remote[Char]) => f(ch).toList).mkString.trackInternal("String#flatMap")

  def fold[A1 >: Char](z: Remote[A1])(op: (Remote[A1], Remote[A1]) => Remote[A1]): Remote[A1] =
    self.toList.fold(z)(op).trackInternal("String#fold")

  def foldLeft[B](z: Remote[B])(op: (Remote[B], Remote[Char]) => Remote[B]): Remote[B] =
    self.toList.foldLeft(z)(op).trackInternal("String#foldLeft")

  def foldRight[B](z: Remote[B])(op: (Remote[Char], Remote[B]) => Remote[B]): Remote[B] =
    self.toList.foldRight(z)(op).trackInternal("String#foldRight")

  def forall(p: Remote[Char] => Remote[Boolean]): Remote[Boolean] =
    self.toList.forall(p).trackInternal("String#forall")

  // TODO: groupBy if we have support for Remote[Map[K, V]]

  @nowarn def grouped(n: Remote[Int]): Remote[List[String]] =
    Remote.fail(s"TODO: not implemented") // TODO

  def head: Remote[Char] =
    self.toList.head.trackInternal("String#head")

  def headOption: Remote[Option[Char]] =
    self.toList.headOption.trackInternal("String#headOption")

  def indexOf[X](value: Remote[X])(implicit ev: RemoteTypeEither[X, Char, String]): Remote[Int] =
    ev.fold(
      ch => self.toList.indexOf(ch),
      s => self.toList.indexOfSlice(s.toList)
    )(value)
      .trackInternal("String#indexOf")

  def indexOf[X](value: Remote[X], from: Remote[Int])(implicit ev: RemoteTypeEither[X, Char, String]): Remote[Int] =
    ev.fold(
      ch => self.toList.indexOf(ch, from),
      s => self.toList.indexOfSlice(s.toList, from)
    )(value)
      .trackInternal("String#indexOf")

  def indexWhere(p: Remote[Char] => Remote[Boolean], from: Remote[Int] = Remote(0)): Remote[Int] =
    self.toList.indexWhere(p, from).trackInternal("String#indexWhere")

  def init: Remote[String] =
    self.toList.init.mkString.trackInternal("String#init")

  def inits: Remote[List[String]] =
    self.toList.inits.map(_.mkString).trackInternal("String#inits")

  def intersect(other: Remote[String]): Remote[String] =
    self.toList.intersect(other.toList).mkString.trackInternal("String#intersect")

  def isEmpty: Remote[Boolean] =
    (self.length === 0).trackInternal("String#isEmpty")

  def knownSize: Remote[Int] = self.length

  def last: Remote[Char] =
    self.toList.last.trackInternal("String#last")

  def lastIndexOf[X](value: Remote[X])(implicit ev: RemoteTypeEither[X, Char, String]): Remote[Int] =
    ev.fold(
      ch => self.toList.lastIndexOf(ch),
      s => self.toList.lastIndexOfSlice(s.toList)
    )(value)
      .trackInternal("String#lastIndexOf")

  def lastIndexOf[X](value: Remote[X], from: Remote[Int])(implicit ev: RemoteTypeEither[X, Char, String]): Remote[Int] =
    ev.fold(
      ch => self.toList.lastIndexOf(ch, from),
      s => self.toList.lastIndexOfSlice(s.toList, from)
    )(value)
      .trackInternal("String#lastIndexOf")

  def lastIndexWhere(p: Remote[Char] => Remote[Boolean]): Remote[Int] =
    self.toList.lastIndexWhere(p).trackInternal("String#lastIndexWhere")

  def lastIndexWhere(p: Remote[Char] => Remote[Boolean], from: Remote[Int]): Remote[Int] =
    self.toList.lastIndexWhere(p, from).trackInternal("String#lastIndexWhere")

  def lastOption: Remote[Option[Char]] =
    self.toList.lastOption.trackInternal("String#lastOption")

  def length: Remote[Int] =
    self.toList.length.trackInternal("String#length")

  def map(f: Remote[Char] => Remote[Char]): Remote[String] =
    self.toList.map(f).mkString.trackInternal("String#map")

  def mkString: Remote[String] = self

  def mkString(sep: Remote[String]): Remote[String] =
    self.toList.mkString(sep).trackInternal("String#mkString")

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String]): Remote[String] =
    self.toList.mkString(start, sep, end).trackInternal("String#mkString")

  def nonEmpty: Remote[Boolean] =
    (self.length > 0).trackInternal("String#nonEmpty")

  def padTo(len: Remote[Int], elem: Remote[Char]): Remote[String] =
    self.toList.padTo(len, elem).mkString.trackInternal("String#padTo")

  def partition(p: Remote[Char] => Remote[Boolean]): Remote[(String, String)] = {
    Remote.bind(self.toList.partition(p)) { tuple =>
      Remote.tuple2((tuple._1.mkString, tuple._2.mkString))
    }
  }.trackInternal("String#partition")

  def partitionMap(p: Remote[Char] => Remote[Either[Char, Char]]): Remote[(String, String)] = {
    Remote.bind(self.toList.partitionMap(p)) { tuple =>
      Remote.tuple2((tuple._1.mkString, tuple._2.mkString))
    }
  }.trackInternal("String#partitionMap")

  def patch(from: Remote[Int], other: Remote[String], replaced: Remote[Int]): Remote[String] =
    self.toList.patch(from, other.toList, replaced).mkString.trackInternal("String#patch")

  def permutations: Remote[List[String]] =
    self.toList.permutations.map(_.mkString).trackInternal("String#permutations")

  def prepended(c: Remote[Char]): Remote[String] =
    c +: self

  def prependedAll(prefix: Remote[String]): Remote[String] =
    prefix ++: self

  // TODO: convert to regex once remote regex is supported

  def replace(oldChar: Remote[Char], newChar: Remote[Char]): Remote[String] =
    self.map(ch => (ch === oldChar).ifThenElse(newChar, ch)).trackInternal("String#replace")

  @nowarn def replaceAll(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    Remote.fail("TODO: built-in regex replace support")

  @nowarn def replaceFirst(regex: Remote[String], replacement: Remote[String]): Remote[String] =
    Remote.fail("TODO: built-in regex replace support")

  def reverse: Remote[String] =
    self.toList.reverse.mkString.trackInternal("String#reverse")

  def size: Remote[Int] =
    self.length

  def slice(from: Remote[Int], until: Remote[Int]): Remote[String] =
    self.toList.slice(from, until).mkString.trackInternal("String#slice")

  def sliding(size: Remote[Int], step: Remote[Int] = Remote(1)): Remote[List[String]] =
    self.toList.sliding(size, step).map(_.mkString).trackInternal("String#sliding")

  // TODO: sortBy/sortWith/sorted when list supports sort

  def span(p: Remote[Char] => Remote[Boolean]): Remote[(String, String)] = {
    Remote.bind(self.toList.span(p)) { tuple =>
      Remote.tuple2((tuple._1.mkString, tuple._2.mkString))
    }
  }.trackInternal("String#span")

  def split[X](separators: Remote[X])(implicit ev: RemoteTypeEither[X, Char, List[Char]]): Remote[List[String]] =
    ev.fold(
      ch => self.split(Remote.list(ch)),
      chs =>
        Remote.recurse[String, List[String]](self) { (remaining, rec) =>
          Remote.bind(remaining.toList.span(!chs.contains(_))) { tuple =>
            Remote.bind(tuple._2.drop(1).mkString) { next =>
              (tuple._1.mkString :: next.isEmpty.ifThenElse(Remote.nil[String], rec(tuple._2.drop(1).mkString)))
            }
          }
        }
    )(separators)
      .trackInternal("String#split")

  def splitAt(n: Remote[Int]): Remote[(String, String)] = {
    val tuple = self.toList.splitAt(n)
    Remote.tuple2((tuple._1.mkString, tuple._2.mkString))
  }.trackInternal("String#splitAt")

  def startsWith(prefix: Remote[String]): Remote[Boolean] =
    self.toList.startsWith(prefix.toList).trackInternal("String#startsWith")

  def strip(): Remote[String] =
    self.stripLeading().stripTrailing().trackInternal("String#strip")

  def stripLeading(): Remote[String] =
    self.dropWhile(_.isWhitespace).trackInternal("String#stripLeading")

  def stripLineEnd: Remote[String] =
    self.reverse.dropWhile(ch => (ch === '\n') || (ch === '\r')).reverse.trackInternal("String#stripLineEnd")

  def stripMargin(char: Remote[Char]): Remote[String] =
    self
      .split('\n')
      .map(line => line.dropWhile(ch => ch.isWhitespace && (ch !== char)).drop(1))
      .mkString("\n")
      .trackInternal("String#stripMargin")

  def stripPrefix(prefix: Remote[String]): Remote[String] =
    (self
      .startsWith(prefix))
      .ifThenElse(
        ifTrue = self.drop(prefix.length),
        ifFalse = self
      )
      .trackInternal("String#stripPrefix")

  def stripTrailing(): Remote[String] =
    self.reverse.dropWhile(_.isWhitespace).reverse.trackInternal("String#stripTrailing")

  def stripSuffix(suffix: Remote[String]): Remote[String] =
    (self
      .endsWith(suffix))
      .ifThenElse(
        ifTrue = self.take(self.length - suffix.length),
        ifFalse = self
      )
      .trackInternal("String#stripSuffix")

  def substring(begin: Remote[Int]): Remote[String] =
    self.toList.drop(begin).mkString.trackInternal("String#substring")

  def substring(begin: Remote[Int], end: Remote[Int]): Remote[String] =
    self.toList.slice(begin, end).mkString.trackInternal("String#substring")

  def tail: Remote[String] =
    self.toList.tail.mkString.trackInternal("String#tail")

  def tails: Remote[List[String]] =
    self.toList.tails.map(_.mkString).trackInternal("String#tails")

  def take(n: Remote[Int]): Remote[String] =
    self.toList.take(n).mkString.trackInternal("String#take")

  def takeRight(n: Remote[Int]): Remote[String] =
    self.toList.takeRight(n).mkString.trackInternal("String#takeRight")

  def takeWhile(p: Remote[Char] => Remote[Boolean]): Remote[String] =
    self.toList.takeWhile(p).mkString.trackInternal("String#takeWhilee")

  def toBase64: Remote[String] =
    Remote.Unary(self, UnaryOperators(UnaryStringOperator.Base64))

  def toBoolean: Remote[Boolean] =
    toBooleanOption.fold(Remote.fail("Invalid boolean"))(n => n).trackInternal("String#toBoolean")

  def toBooleanOption: Remote[Option[Boolean]] =
    (self === "true")
      .ifThenElse(
        ifTrue = Remote.some(true),
        ifFalse = (self === "false").ifThenElse(
          ifTrue = Remote.some(false),
          ifFalse = Remote.none[Boolean]
        )
      )
      .trackInternal("String#toBooleanOption")

  def toByte: Remote[Byte] =
    Remote.fail("TODO: byte not supported")

  def toByteOption: Remote[Option[Byte]] =
    Remote.fail("TODO: byte not supported")

  def toDouble: Remote[Double] =
    toDoubleOption.fold(Remote.fail("Invalid double"))(n => n).trackInternal("String#toDouble")

  def toDoubleOption: Remote[Option[Double]] =
    Remote
      .Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericDouble)))
      .trackInternal("String#toDoubleOption")

  def toFloat: Remote[Float] =
    toFloatOption.fold(Remote.fail("Invalid float"))(n => n).trackInternal("String#toFloat")

  def toFloatOption: Remote[Option[Float]] =
    Remote
      .Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericFloat)))
      .trackInternal("String#toFloatOption")

  def toInt: Remote[Int] =
    toIntOption.fold(Remote.fail("Invalid int"))(n => n).trackInternal("String#toInt")

  def toIntOption: Remote[Option[Int]] =
    Remote
      .Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericInt)))
      .trackInternal("String#toIntOption")

  def toList: Remote[List[Char]] =
    Remote.StringToCharList(self).trackInternal("String#toList")

  def toLong: Remote[Long] =
    toLongOption.fold(Remote.fail("Invalid long"))(n => n).trackInternal("String#toLong")

  def toLongOption: Remote[Option[Long]] =
    Remote
      .Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericLong)))
      .trackInternal("String#toLongOption")

  def toLowerCase: Remote[String] =
    self.toList.map(_.toLower).mkString.trackInternal("String#toLowerCase")

  def toShort: Remote[Short] =
    toShortOption.fold(Remote.fail("Invalid short"))(n => n).trackInternal("String#toShort")

  def toShortOption: Remote[Option[Short]] =
    Remote
      .Unary(self, UnaryOperators.Conversion(RemoteConversions.StringToNumeric(Numeric.NumericShort)))
      .trackInternal("String#toShortOption")

  def toUpperCase: Remote[String] =
    self.toList.map(_.toUpper).mkString.trackInternal("String#toUpperCase")

  def trim(): Remote[String] =
    self.strip().trackInternal("String#trim")
}

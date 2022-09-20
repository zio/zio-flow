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

import zio.flow.Remote.UnboundRemoteFunction
import zio.flow._
import zio.flow.internal._
import zio.flow.remote.numeric._
import zio.schema.Schema

final class RemoteListSyntax[A](val self: Remote[List[A]]) extends AnyVal {

  def ++(suffix: Remote[List[A]]): Remote[List[A]] =
    reverse.foldLeft(suffix)((l, a) => Remote.Cons(l, a))

  def ++:(prefix: Remote[List[A]]): Remote[List[A]] =
    prefix ++ self

  def +:[B >: A](elem: Remote[B]): Remote[List[B]] =
    elem :: self

  def :+[B >: A](elem: Remote[B]): Remote[List[B]] =
    Remote.Cons(reverse.widen[List[B]], elem).reverse

  def :++[B >: A](suffix: Remote[List[B]]): Remote[List[B]] =
    self.widen[List[B]] ++ suffix

  def ::[B >: A](elem: Remote[B]): Remote[List[B]] =
    Remote.Cons(self.widen[List[B]], elem)

  def :::[B >: A](prefix: Remote[List[B]]): Remote[List[B]] =
    prefix ++ self.widen[List[B]]

  def appended[B >: A](elem: Remote[B]): Remote[List[B]] =
    self :+ elem

  def appendedAll[B >: A](suffix: Remote[List[B]]): Remote[List[B]] =
    self :++ suffix

  def apply(n: Remote[Int]): Remote[A] =
    self.drop(n).headOption.get

  def concat[B >: A](suffix: Remote[List[B]]): Remote[List[B]] =
    self :++ suffix

  def contains[A1 >: A](elem: Remote[A1]): Remote[Boolean] =
    Remote
      .recurse((Remote(false), self.widen[List[A1]])) { case (input, rec) =>
        Remote
          .UnCons(input._2)
          .fold((Remote(false), Remote.nil[A1]))((tuple: Remote[(A1, List[A1])]) =>
            (tuple._1 === elem).ifThenElse(
              ifTrue = (Remote(true), Remote.nil[A1]),
              ifFalse = rec((Remote(false), tuple._2))
            )
          )
      }
      ._1

  def containsSlice[B >: A](that: Remote[List[B]]): Remote[Boolean] =
    Remote
      .recurse((Remote(false), that.length, self.widen[List[B]])) { case (input, rec) =>
        val len     = input._2
        val current = input._3
        Remote
          .UnCons(current)
          .fold((Remote(false), len, Remote.nil[B]))((tuple: Remote[(B, List[B])]) =>
            (current.take(len) === that).ifThenElse(
              ifTrue = (Remote(true), len, Remote.nil[B]),
              ifFalse = rec((Remote(false), len, tuple._2))
            )
          )
      }
      ._1

  def corresponds[B](that: Remote[List[B]])(p: (Remote[A], Remote[B]) => Remote[Boolean]): Remote[Boolean] =
    (self.length === that.length) &&
      self.zip(that).forall((tuple: Remote[(A, B)]) => p(tuple._1, tuple._2))

  def count(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    foldLeft(Remote(0)) { case (total, elem) =>
      p(elem).ifThenElse(
        ifTrue = total + 1,
        ifFalse = total
      )
    }

  def diff[B >: A](other: Remote[List[B]]): Remote[List[A]] =
    filter(!other.contains(_))

  def distinct: Remote[List[A]] =
    self.toSet.toList

  def distinctBy[B](f: Remote[A] => Remote[B]): Remote[List[A]] =
    self.filterNot(self.map(f).contains(_))

  def drop(n: Remote[Int]): Remote[List[A]] =
    Remote
      .recurse((Remote(0), self)) { case (input, rec) =>
        Remote
          .UnCons(input._2)
          .fold((Remote(0), Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            (input._1 < n).ifThenElse(
              ifTrue = rec((input._1 + 1, tuple._2)),
              ifFalse = (Remote(0), tuple._2)
            )
          )
      }
      ._2

  def dropWhile(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    Remote
      .recurse(self) { case (input, rec) =>
        Remote
          .UnCons(input)
          .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) =>
            (predicate(tuple._1)).ifThenElse(
              ifTrue = rec(tuple._2),
              ifFalse = tuple._2
            )
          )
      }

  def endsWith[B >: A](that: Remote[List[B]]): Remote[Boolean] =
    (self.length >= that.length).ifThenElse(
      ifTrue = self.drop(self.length - that.length).widen[List[B]] === that,
      ifFalse = Remote(false)
    )

  def exists(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    Remote
      .recurse((Remote(false), self)) { case (input, rec) =>
        Remote
          .UnCons(input._2)
          .fold((Remote(false), Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            (p(tuple._1)).ifThenElse(
              ifTrue = (Remote(true), Remote.nil[A]),
              ifFalse = rec((Remote(false), tuple._2))
            )
          )
      }
      ._1

  def filter(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    foldLeft[List[A]](Remote.nil[A])((a2: Remote[List[A]], a1: Remote[A]) =>
      predicate(a1).ifThenElse(Remote.Cons(a2, a1), a2)
    )

  def filterNot(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    foldLeft[List[A]](Remote.nil[A])((a2: Remote[List[A]], a1: Remote[A]) =>
      predicate(a1).ifThenElse(a2, Remote.Cons(a2, a1))
    )

  def find(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    Remote
      .recurse((Remote.none[A], self)) { case (input, rec) =>
        Remote
          .UnCons(input._2)
          .fold((Remote.none[A], Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            (p(tuple._1)).ifThenElse(
              ifTrue = (Remote.some(tuple._1), Remote.nil[A]),
              ifFalse = rec((Remote.none[A], tuple._2))
            )
          )
      }
      ._1

  def findLast(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    self.reverse.find(p)

  def flatMap[B](f: Remote[A] => Remote[List[B]]): Remote[List[B]] =
    self.foldLeft(Remote.nil[B])((lst, elem) => lst ++ f(elem))

  def flatten[B](implicit ev: A <:< List[B]): Remote[List[B]] =
    self.flatMap((elem: Remote[A]) => elem.asInstanceOf[Remote[List[B]]])

  def foldLeft[B](initial: Remote[B])(
    f: (Remote[B], Remote[A]) => Remote[B]
  ): Remote[B] =
    Remote.Fold(self, initial, UnboundRemoteFunction.make((tuple: Remote[(B, A)]) => f(tuple._1, tuple._2)))

  def foldRight[B](initial: Remote[B])(
    f: (Remote[A], Remote[B]) => Remote[B]
  ): Remote[B] =
    Remote.Fold(self.reverse, initial, UnboundRemoteFunction.make((tuple: Remote[(B, A)]) => f(tuple._2, tuple._1)))

  def forall(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    Remote
      .recurse((Remote(true), self)) { case (input, rec) =>
        Remote
          .UnCons(input._2)
          .fold((Remote(true), Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            (p(tuple._1)).ifThenElse(
              ifTrue = rec((Remote(true), tuple._2)),
              ifFalse = (Remote(false), Remote.nil[A])
            )
          )
      }
      ._1

  // TODO: groupBy etc if we have support for Remote[Map[K, V]]

  def head: Remote[A] =
    self.headOption.fold(Remote.fail(s"List is empty"))((h: Remote[A]) => h)

  def headOption: Remote[Option[A]] =
    Remote
      .UnCons(self)
      .fold[Option[A]](Remote.none[A])(tuple => Remote.RemoteSome(tuple._1))

  def indexOf[B >: A](elem: Remote[B]): Remote[Int] =
    self.zipWithIndex.find(tuple => tuple._1.widen[B] === elem).map(_._2).getOrElse(Remote(-1))

  def indexOf[B >: A](elem: Remote[B], from: Remote[Int]): Remote[Int] =
    self.zipWithIndex.drop(from).find(tuple => tuple._1.widen[B] === elem).map(_._2).getOrElse(Remote(-1))

  def indexOfSlice[B >: A](that: Remote[List[B]]): Remote[Int] =
    indexOfSlice(that, 0)

  def indexOfSlice[B >: A](that: Remote[List[B]], from: Remote[Int]): Remote[Int] =
    Remote
      .recurse((Remote(-1), that.length, self.widen[List[B]].drop(from), from)) { case (input, rec) =>
        val len          = input._2
        val current      = input._3
        val currentIndex = input._4
        Remote
          .UnCons(current)
          .fold((Remote(-1), len, Remote.nil[B], currentIndex))((tuple: Remote[(B, List[B])]) =>
            (current.take(len) === that).ifThenElse(
              ifTrue = (currentIndex, len, Remote.nil[B], currentIndex),
              ifFalse = rec((Remote(-1), len, tuple._2, currentIndex + 1))
            )
          )
      }
      ._1

  def indexWhere(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    self.zipWithIndex.find(tuple => p(tuple._1)).map(_._2).getOrElse(Remote(-1))

  def indexWhere(p: Remote[A] => Remote[Boolean], from: Remote[Int]): Remote[Int] =
    self.zipWithIndex.drop(from).find(tuple => p(tuple._1)).map(_._2).getOrElse(Remote(-1))

  // TODO: indices if there is support for Remote[Range]

  def init: Remote[List[A]] =
    Remote.UnCons(self.reverse).fold(Remote.fail("List is empty"))(_._2)

  def inits: Remote[List[List[A]]] =
    Remote
      .recurse((Remote.nil[List[A]], self)) { case (input, rec) =>
        val result      = input._1
        val currentSelf = input._2

        Remote
          .UnCons(currentSelf)
          .fold((result, Remote.nil[A])) { tupleSelf: Remote[(A, List[A])] =>
            rec((currentSelf :: result, tupleSelf._2))
          }
      }
      ._1
      .reverse

  def intersect[B >: A](that: Remote[List[B]]): Remote[List[A]] =
    self.filter(that.contains(_))

  def isDefinedAt(x: Remote[Int]): Remote[Boolean] =
    (x >= 0) && (x < self.length)

  def isEmpty: Remote[Boolean] =
    self.headOption.isNone.ifThenElse(Remote(true), Remote(false))

  def last: Remote[A] =
    self.reverse.head

  def lastIndexOf[B >: A](elem: Remote[B]): Remote[Int] =
    self.length - 1 - self.reverse.indexOf(elem)

  def lastIndexOf[B >: A](elem: Remote[B], end: Remote[Int]): Remote[Int] =
    self.length - 1 - self.reverse.indexOf(elem, self.length - 1 - end)

  def lastIndexOfSlice[B >: A](that: Remote[List[B]]): Remote[Int] =
    self.length - 1 - self.reverse.indexOfSlice(that)

  def lastIndexOfSlice[B >: A](that: Remote[List[B]], end: Remote[Int]): Remote[Int] =
    self.length - 1 - self.reverse.indexOfSlice(that, self.length - 1 - end)

  def lastIndexWhere(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    self.length - 1 - self.reverse.indexWhere(p)

  def lastIndexWhere(p: Remote[A] => Remote[Boolean], end: Remote[Int]): Remote[Int] =
    self.length - 1 - self.reverse.indexWhere(p, self.length - 1 - end)

  def lastOption: Remote[Option[A]] =
    self.reverse.headOption

  def length: Remote[Int] =
    self.foldLeft[Int](0)((len, _) => len + 1)

  def map[B](f: Remote[A] => Remote[B]): Remote[List[B]] =
    foldLeft[List[B]](Remote.nil[B])((res, elem) => f(elem) :: res)

  def max(implicit schema: Schema[A]): Remote[A] =
    Remote
      .recurse((self.head, self)) { case (input, rec) =>
        val current   = input._1
        val remaining = input._2
        Remote
          .UnCons(remaining)
          .fold((current, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (
                (current <= tuple._1).ifThenElse(ifTrue = tuple._1, ifFalse = current),
                remaining
              )
            )
          )
      }
      ._1

  def maxBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    Remote
      .recurse((self.head, f(self.head), self)) { case (input, rec) =>
        val current       = input._1
        val currentMapped = input._2
        val remaining     = input._3
        Remote
          .UnCons(remaining)
          .fold((current, currentMapped, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (currentMapped <= f(tuple._1)).ifThenElse(
                ifTrue = (tuple._1, f(tuple._1), remaining),
                ifFalse = (current, currentMapped, remaining)
              )
            )
          )
      }
      ._1

  def maxByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    Remote
      .recurse((self.headOption, self.headOption.map(f), self)) { case (input, rec) =>
        val current       = input._1
        val currentMapped = input._2
        val remaining     = input._3
        Remote
          .UnCons(remaining)
          .fold((current, currentMapped, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (currentMapped.getOrElse(f(tuple._1)) <= f(tuple._1)).ifThenElse(
                ifTrue = (Remote.some[A](tuple._1), Remote.some[B](f(tuple._1)), remaining),
                ifFalse = (current, currentMapped, remaining)
              )
            )
          )
      }
      ._1

  def maxOption(implicit schema: Schema[A]): Remote[Option[A]] =
    Remote
      .recurse((self.headOption, self)) { case (input, rec) =>
        val current   = input._1
        val remaining = input._2
        Remote
          .UnCons(remaining)
          .fold((current, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (
                (current.getOrElse(tuple._1) <= tuple._1).ifThenElse(ifTrue = Remote.some(tuple._1), ifFalse = current),
                remaining
              )
            )
          )
      }
      ._1

  def min(implicit schema: Schema[A]): Remote[A] =
    Remote
      .recurse((self.head, self)) { case (input, rec) =>
        val current   = input._1
        val remaining = input._2
        Remote
          .UnCons(remaining)
          .fold((current, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (
                (current >= tuple._1).ifThenElse(ifTrue = tuple._1, ifFalse = current),
                remaining
              )
            )
          )
      }
      ._1

  def minBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    Remote
      .recurse((self.head, f(self.head), self)) { case (input, rec) =>
        val current       = input._1
        val currentMapped = input._2
        val remaining     = input._3
        Remote
          .UnCons(remaining)
          .fold((current, currentMapped, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (currentMapped >= f(tuple._1)).ifThenElse(
                ifTrue = (tuple._1, f(tuple._1), remaining),
                ifFalse = (current, currentMapped, remaining)
              )
            )
          )
      }
      ._1

  def minByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    Remote
      .recurse((self.headOption, self.headOption.map(f), self)) { case (input, rec) =>
        val current       = input._1
        val currentMapped = input._2
        val remaining     = input._3
        Remote
          .UnCons(remaining)
          .fold((current, currentMapped, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (currentMapped.getOrElse(f(tuple._1)) >= f(tuple._1)).ifThenElse(
                ifTrue = (Remote.some[A](tuple._1), Remote.some[B](f(tuple._1)), remaining),
                ifFalse = (current, currentMapped, remaining)
              )
            )
          )
      }
      ._1

  def minOption(implicit schema: Schema[A]): Remote[Option[A]] =
    Remote
      .recurse((self.headOption, self)) { case (input, rec) =>
        val current   = input._1
        val remaining = input._2
        Remote
          .UnCons(remaining)
          .fold((current, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (
                (current.getOrElse(tuple._1) >= tuple._1).ifThenElse(ifTrue = Remote.some(tuple._1), ifFalse = current),
                remaining
              )
            )
          )
      }
      ._1

  def mkString(implicit ev: A =:= String): Remote[String] =
    Remote.ListToString(self.asInstanceOf[Remote[List[String]]], Remote(""), Remote(""), Remote(""))

  def mkString(implicit ev: A =:!= String, schema: Schema[A]): Remote[String] =
    Remote.ListToString(self.map(_.toString), Remote(""), Remote(""), Remote(""))

  def mkString(sep: Remote[String])(implicit ev: A =:= String): Remote[String] =
    Remote.ListToString(self.asInstanceOf[Remote[List[String]]], Remote(""), sep, Remote(""))

  def mkString(sep: Remote[String])(implicit ev: A =:!= String, schema: Schema[A]): Remote[String] =
    Remote.ListToString(self.map(_.toString), Remote(""), sep, Remote(""))

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String])(implicit
    ev: A =:= String
  ): Remote[String] =
    Remote.ListToString(self.asInstanceOf[Remote[List[String]]], start, sep, end)

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String])(implicit
    ev: A =:!= String,
    schema: Schema[A]
  ): Remote[String] =
    Remote.ListToString(self.map(_.toString), start, sep, end)

  def nonEmpty: Remote[Boolean] =
    !self.isEmpty

  def padTo[B >: A](len: Remote[Int], elem: Remote[B]): Remote[List[B]] = {
    val count = math.max(0, len - self.length)
    List.fill(count)(elem)
  }

  def product(implicit numeric: Numeric[A]): Remote[A] =
    foldLeft[A](numeric.fromLong(1L))(_ * _)

  def reverse: Remote[List[A]] =
    foldLeft(Remote.nil[A])((l, a) => Remote.Cons(l, a))

  def size: Remote[Int] =
    self.length

  def sum(implicit numeric: Numeric[A]): Remote[A] =
    foldLeft[A](numeric.fromLong(0L))(_ + _)

  def take(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue: Remote[List[A]] =
      Remote
        .UnCons(self.widen[List[A]])
        .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) => Remote.Cons(tuple._2.take(num - Remote(1)), tuple._1))
    (num > Remote(0)).ifThenElse(ifTrue, Remote.nil[A])
  }

  def takeWhile(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    Remote
      .UnCons(self)
      .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) =>
        predicate(tuple._1).ifThenElse(Remote.Cons(tuple._2.takeWhile(predicate), tuple._1), Remote.nil)
      )

  def toSet: Remote[Set[A]] =
    Remote.ListToSet(self)

  def zip[B](that: Remote[List[B]]): Remote[List[(A, B)]] =
    Remote
      .recurse((Remote.nil[(A, B)], self, that)) { case (input, rec) =>
        val result      = input._1
        val currentSelf = input._2
        val currentThat = input._3

        Remote
          .UnCons(currentSelf)
          .fold((result, Remote.nil[A], Remote.nil[B])) { tupleSelf: Remote[(A, List[A])] =>
            Remote
              .UnCons(currentThat)
              .fold((result, Remote.nil[A], Remote.nil[B])) { tupleThat: Remote[(B, List[B])] =>
                rec(((tupleSelf._1, tupleThat._1) :: result, tupleSelf._2, tupleThat._2))
              }
          }
      }
      ._1
      .reverse

  def zipWithIndex: Remote[List[(A, Int)]] =
    Remote
      .recurse((Remote.nil[(A, Int)], self, Remote(0))) { case (input, rec) =>
        val result       = input._1
        val currentSelf  = input._2
        val currentIndex = input._3

        Remote
          .UnCons(currentSelf)
          .fold((result, Remote.nil[A], Remote(0))) { tupleSelf: Remote[(A, List[A])] =>
            rec(((tupleSelf._1, currentIndex) :: result, tupleSelf._2, currentIndex + 1))
          }
      }
      ._1
      .reverse
}

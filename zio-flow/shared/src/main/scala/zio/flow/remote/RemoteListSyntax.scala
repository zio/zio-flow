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
    self.reverse.foldLeft(suffix)((l, a) => Remote.Cons(l, a))

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
      .recurseSimple((Remote(false), self.widen[List[A1]])) { case (input, rec) =>
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
      .recurseSimple((Remote(false), that.length, self.widen[List[B]])) { case (input, rec) =>
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
    self
      .foldLeft((Remote.nil[A], self.map(f).distinct)) { (tuple, elem) =>
        val result     = tuple._1
        val allowed    = tuple._2
        val mappedElem = f(elem)
        allowed
          .contains(mappedElem)
          .ifThenElse(
            ifTrue = (elem :: result, allowed.filterNot(_ === mappedElem)),
            ifFalse = tuple
          )
      }
      ._1
      .reverse

  def drop(n: Remote[Int]): Remote[List[A]] =
    Remote
      .recurseSimple((Remote(0), self)) { case (input, rec) =>
        Remote
          .UnCons(input._2)
          .fold((Remote(0), Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            (input._1 < n).ifThenElse(
              ifTrue = rec((input._1 + 1, tuple._2)),
              ifFalse = (Remote(0), input._2)
            )
          )
      }
      ._2

  def dropWhile(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    Remote
      .recurseSimple(self) { case (input, rec) =>
        Remote
          .UnCons(input)
          .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) =>
            (predicate(tuple._1)).ifThenElse(
              ifTrue = rec(tuple._2),
              ifFalse = input
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
      .recurseSimple((Remote(false), self)) { case (input, rec) =>
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
    self.reverse.foldLeft[List[A]](Remote.nil[A])((a2: Remote[List[A]], a1: Remote[A]) =>
      predicate(a1).ifThenElse(Remote.Cons(a2, a1), a2)
    )

  def filterNot(
    predicate: Remote[A] => Remote[Boolean]
  ): Remote[List[A]] =
    self.reverse.foldLeft[List[A]](Remote.nil[A])((a2: Remote[List[A]], a1: Remote[A]) =>
      predicate(a1).ifThenElse(a2, Remote.Cons(a2, a1))
    )

  def find(p: Remote[A] => Remote[Boolean]): Remote[Option[A]] =
    Remote
      .recurseSimple((Remote.none[A], self)) { case (input, rec) =>
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
      .recurseSimple((Remote(true), self)) { case (input, rec) =>
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
      .recurseSimple((Remote(-1), that.length, self.widen[List[B]].drop(from), from)) { case (input, rec) =>
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
    Remote.UnCons(self.reverse).fold(Remote.fail("List is empty"))(_._2).reverse

  def inits: Remote[List[List[A]]] =
    Remote
      .recurseSimple((Remote.nil[List[A]], self.reverse)) { case (input, rec) =>
        val result      = input._1
        val currentSelf = input._2

        Remote
          .UnCons(currentSelf)
          .fold((result, Remote.nil[A])) { tupleSelf: Remote[(A, List[A])] =>
            rec((currentSelf.reverse :: result, tupleSelf._2))
          }
      }
      ._1
      .reverse ++ Remote.list(Remote.nil[A])

  def intersect[B >: A](that: Remote[List[B]]): Remote[List[A]] =
    self.filter(that.contains(_))

  def isDefinedAt(x: Remote[Int]): Remote[Boolean] =
    (x >= 0) && (x < self.length)

  def isEmpty: Remote[Boolean] =
    self.headOption.isNone.ifThenElse(Remote(true), Remote(false))

  def last: Remote[A] =
    self.reverse.head

  def lastIndexOf[B >: A](elem: Remote[B]): Remote[Int] = {
    val ridx = self.reverse.indexOf(elem)
    (ridx === -1).ifThenElse(
      ifTrue = Remote(-1),
      ifFalse = self.length - 1 - ridx
    )
  }

  def lastIndexOf[B >: A](elem: Remote[B], end: Remote[Int]): Remote[Int] = {
    val ridx = self.reverse.indexOf(elem, self.length - 1 - end)
    (ridx === -1).ifThenElse(
      ifTrue = Remote(-1),
      ifFalse = self.length - 1 - ridx
    )
  }

  def lastIndexOfSlice[B >: A](that: Remote[List[B]]): Remote[Int] = {
    val ridx = self.reverse.indexOfSlice(that.reverse)
    (ridx === -1).ifThenElse(
      ifTrue = Remote(-1),
      ifFalse = self.length - ridx - that.length
    )
  }

  def lastIndexOfSlice[B >: A](that: Remote[List[B]], end: Remote[Int]): Remote[Int] = {
    val ridx = self.reverse.indexOfSlice(that.reverse, self.length - 1 - end)
    (ridx === -1).ifThenElse(
      ifTrue = Remote(-1),
      ifFalse = self.length - ridx - that.length
    )
  }

  def lastIndexWhere(p: Remote[A] => Remote[Boolean]): Remote[Int] = {
    val ridx = self.reverse.indexWhere(p)
    (ridx === -1).ifThenElse(
      ifTrue = Remote(-1),
      ifFalse = self.length - 1 - ridx
    )
  }

  def lastIndexWhere(p: Remote[A] => Remote[Boolean], end: Remote[Int]): Remote[Int] = {
    val ridx = self.reverse.indexWhere(p, self.length - 1 - end)
    (ridx === -1).ifThenElse(
      ifTrue = Remote(-1),
      ifFalse = self.length - 1 - ridx
    )
  }

  def lastOption: Remote[Option[A]] =
    self.reverse.headOption

  def length: Remote[Int] =
    self.foldLeft[Int](0)((len, _) => len + 1)

  def map[B](f: Remote[A] => Remote[B]): Remote[List[B]] =
    foldLeft[List[B]](Remote.nil[B])((res, elem) => f(elem) :: res).reverse

  def max(implicit schema: Schema[A]): Remote[A] =
    Remote
      .recurseSimple((self.head, self)) { case (input, rec) =>
        val current   = input._1
        val remaining = input._2
        Remote
          .UnCons(remaining)
          .fold((current, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (
                (current <= tuple._1).ifThenElse(ifTrue = tuple._1, ifFalse = current),
                tuple._2
              )
            )
          )
      }
      ._1

  def maxBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    Remote
      .recurseSimple((self.head, f(self.head), self)) { case (input, rec) =>
        val current       = input._1
        val currentMapped = input._2
        val remaining     = input._3
        Remote
          .UnCons(remaining)
          .fold((current, currentMapped, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (currentMapped <= f(tuple._1)).ifThenElse(
                ifTrue = (tuple._1, f(tuple._1), tuple._2),
                ifFalse = (current, currentMapped, tuple._2)
              )
            )
          )
      }
      ._1

  def maxByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    Remote
      .recurseSimple((self.headOption, self.headOption.map(f), self)) { case (input, rec) =>
        val current       = input._1
        val currentMapped = input._2
        val remaining     = input._3
        Remote
          .UnCons(remaining)
          .fold((current, currentMapped, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (currentMapped.getOrElse(f(tuple._1)) <= f(tuple._1)).ifThenElse(
                ifTrue = (Remote.some[A](tuple._1), Remote.some[B](f(tuple._1)), tuple._2),
                ifFalse = (current, currentMapped, tuple._2)
              )
            )
          )
      }
      ._1

  def maxOption(implicit schema: Schema[A]): Remote[Option[A]] =
    Remote
      .recurseSimple((self.headOption, self)) { case (input, rec) =>
        val current   = input._1
        val remaining = input._2
        Remote
          .UnCons(remaining)
          .fold((current, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (
                (current.getOrElse(tuple._1) <= tuple._1).ifThenElse(ifTrue = Remote.some(tuple._1), ifFalse = current),
                tuple._2
              )
            )
          )
      }
      ._1

  def min(implicit schema: Schema[A]): Remote[A] =
    Remote
      .recurseSimple((self.head, self)) { case (input, rec) =>
        val current   = input._1
        val remaining = input._2
        Remote
          .UnCons(remaining)
          .fold((current, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (
                (current >= tuple._1).ifThenElse(ifTrue = tuple._1, ifFalse = current),
                tuple._2
              )
            )
          )
      }
      ._1

  def minBy[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[A] =
    Remote
      .recurseSimple((self.head, f(self.head), self)) { case (input, rec) =>
        val current       = input._1
        val currentMapped = input._2
        val remaining     = input._3
        Remote
          .UnCons(remaining)
          .fold((current, currentMapped, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (currentMapped >= f(tuple._1)).ifThenElse(
                ifTrue = (tuple._1, f(tuple._1), tuple._2),
                ifFalse = (current, currentMapped, tuple._2)
              )
            )
          )
      }
      ._1

  def minByOption[B](f: Remote[A] => Remote[B])(implicit schema: Schema[B]): Remote[Option[A]] =
    Remote
      .recurseSimple((self.headOption, self.headOption.map(f), self)) { case (input, rec) =>
        val current       = input._1
        val currentMapped = input._2
        val remaining     = input._3
        Remote
          .UnCons(remaining)
          .fold((current, currentMapped, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (currentMapped.getOrElse(f(tuple._1)) >= f(tuple._1)).ifThenElse(
                ifTrue = (Remote.some[A](tuple._1), Remote.some[B](f(tuple._1)), tuple._2),
                ifFalse = (current, currentMapped, tuple._2)
              )
            )
          )
      }
      ._1

  def minOption(implicit schema: Schema[A]): Remote[Option[A]] =
    Remote
      .recurseSimple((self.headOption, self)) { case (input, rec) =>
        val current   = input._1
        val remaining = input._2
        Remote
          .UnCons(remaining)
          .fold((current, Remote.nil[A]))((tuple: Remote[(A, List[A])]) =>
            rec(
              (
                (current.getOrElse(tuple._1) >= tuple._1).ifThenElse(ifTrue = Remote.some(tuple._1), ifFalse = current),
                tuple._2
              )
            )
          )
      }
      ._1

  def mkString(implicit ev: A =:= String): Remote[String] =
    Remote.ListToString(self.asInstanceOf[Remote[List[String]]], Remote(""), Remote(""), Remote(""))

  // TODO: this should be mkString but collides with the string version
  def mkStringS(implicit ev: A =:!= String, schema: Schema[A]): Remote[String] =
    Remote.ListToString(self.map(_.toString), Remote(""), Remote(""), Remote(""))

  def mkString(sep: Remote[String])(implicit ev: A =:= String): Remote[String] =
    Remote.ListToString(self.asInstanceOf[Remote[List[String]]], Remote(""), sep, Remote(""))

  // TODO: this should be mkString but collides with the string version
  def mkStringS(sep: Remote[String])(implicit ev: A =:!= String, schema: Schema[A]): Remote[String] =
    Remote.ListToString(self.map(_.toString), Remote(""), sep, Remote(""))

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String])(implicit
    ev: A =:= String
  ): Remote[String] =
    Remote.ListToString(self.asInstanceOf[Remote[List[String]]], start, sep, end)

  // TODO: this should be mkString but collides with the string version
  def mkStringS(start: Remote[String], sep: Remote[String], end: Remote[String])(implicit
    ev: A =:!= String,
    schema: Schema[A]
  ): Remote[String] =
    Remote.ListToString(self.map(_.toString), start, sep, end)

  def nonEmpty: Remote[Boolean] =
    !self.isEmpty

  def padTo[B >: A](len: Remote[Int], elem: Remote[B]): Remote[List[B]] = {
    val count = math.max(0, len - self.length)
    self ::: List.fill(count)(elem)
  }

  def partition(p: Remote[A] => Remote[Boolean]): Remote[(List[A], List[A])] =
    Remote
      .recurseSimple(Remote.tuple2((Remote.tuple2((Remote.nil[A], Remote.nil[A])), self))) { (input, rec) =>
        val satisfies      = input._1._1
        val doesNotSatisfy = input._1._2
        val remaining      = input._2

        Remote
          .UnCons(remaining)
          .fold(
            Remote.tuple2((Remote.tuple2((satisfies.reverse, doesNotSatisfy.reverse)), Remote.nil[A]))
          ) { tuple =>
            p(tuple._1).ifThenElse(
              ifTrue = rec(Remote.tuple2((Remote.tuple2((tuple._1 :: satisfies, doesNotSatisfy)), tuple._2))),
              ifFalse = rec(Remote.tuple2((Remote.tuple2((satisfies, tuple._1 :: doesNotSatisfy)), tuple._2)))
            )
          }
      }
      ._1

  def partitionMap[A1, A2](p: Remote[A] => Remote[Either[A1, A2]]): Remote[(List[A1], List[A2])] =
    Remote
      .recurseSimple(Remote.tuple2((Remote.tuple2((Remote.nil[A1], Remote.nil[A2])), self))) { (input, rec) =>
        val leftList  = input._1._1
        val rightList = input._1._2
        val remaining = input._2

        Remote
          .UnCons(remaining)
          .fold(
            Remote.tuple2((Remote.tuple2((leftList.reverse, rightList.reverse)), Remote.nil[A]))
          ) { tuple =>
            p(tuple._1).fold(
              left => rec(Remote.tuple2((Remote.tuple2((left :: leftList, rightList)), tuple._2))),
              right => rec(Remote.tuple2((Remote.tuple2((leftList, right :: rightList)), tuple._2)))
            )
          }
      }
      ._1

  def patch[B >: A](from: Remote[Int], other: Remote[List[B]], replaced: Remote[Int]): Remote[List[B]] = {
    val safeFrom     = math.min(self.length, math.max(0, from))
    val safeTo       = math.min(self.length, math.max(0, from + replaced))
    val safeReplaced = safeTo - safeFrom
    val selfB        = self.widen[List[B]]
    selfB
      .take(safeFrom)
      .concat(other.take(safeReplaced))
      .concat(selfB.drop(safeTo))
  }

  def permutations: Remote[List[List[A]]] =
    Remote
      .recurse[List[A], List[List[A]]](self) { (input, rec) =>
        val length = input.length
        (length >= 2).ifThenElse[List[List[A]]](
          ifFalse = Remote.list(input),
          ifTrue = {
            input.zipWithIndex.flatMap { tuple =>
              val elem: Remote[A] = tuple._1
              val idx             = tuple._2

              val subpermutations: Remote[List[List[A]]] =
                rec((input.take(idx) ++ input.drop(idx + 1)))

              subpermutations.map[List[A]] { (lst: Remote[List[A]]) =>
                (elem :: lst)
              }
            }
          }
        )
      }

  def prepended[B >: A](elem: Remote[B]): Remote[List[B]] =
    elem :: self

  def prependedAll[B >: A](prefix: Remote[List[B]]): Remote[List[B]] =
    prefix ::: self

  def product(implicit numeric: Numeric[A]): Remote[A] =
    self.foldLeft[A](numeric.fromLong(1L))(_ * _)

  def reduce[B >: A](op: (Remote[B], Remote[B]) => Remote[B]): Remote[B] =
    self.reduceLeft(op)

  def reduceLeft[B >: A](op: (Remote[B], Remote[A]) => Remote[B]): Remote[B] =
    self.foldLeft[B](self.widen[List[B]].head)(op)

  def reduceLeftOption[B >: A](op: (Remote[B], Remote[A]) => Remote[B]): Remote[Option[B]] =
    self.isEmpty.ifThenElse(
      ifTrue = Remote.none,
      ifFalse = Remote.some(self.reduceLeft(op))
    )

  def reduceOption[B >: A](op: (Remote[B], Remote[B]) => Remote[B]): Remote[Option[B]] =
    self.reduceLeftOption(op)

  def reduceRight[B >: A](op: (Remote[A], Remote[B]) => Remote[B]): Remote[B] =
    self.foldRight[B](self.widen[List[B]].head)(op)

  def reduceRightOption[B >: A](op: (Remote[A], Remote[B]) => Remote[B]): Remote[Option[B]] =
    self.isEmpty.ifThenElse(ifTrue = Remote.none, ifFalse = Remote.some(self.reduceRight(op)))

  def reverse: Remote[List[A]] =
    self.foldLeft(Remote.nil[A])((l, a) => Remote.Cons(l, a))

  def reverse_:::[B >: A](prefix: Remote[List[B]]): Remote[List[B]] =
    prefix.reverse ::: self

  def sameElements[B >: A](other: Remote[List[B]]): Remote[Boolean] =
    self.widen[List[B]].zip(other).forall(tuple => tuple._1 === tuple._2)

  def scan[B >: A](z: Remote[B])(op: (Remote[B], Remote[B]) => Remote[B]): Remote[List[B]] =
    scanLeft(z)(op)

  def scanLeft[B >: A](z: Remote[B])(op: (Remote[B], Remote[A]) => Remote[B]): Remote[List[B]] =
    self
      .foldLeft((z, Remote.list(z))) { case (tuple, elem) =>
        val agg  = tuple._1
        val lst  = tuple._2
        val agg2 = op(agg, elem)
        (agg2, agg2 :: lst)
      }
      ._2

  def scanRight[B >: A](z: Remote[B])(op: (Remote[A], Remote[B]) => Remote[B]): Remote[List[B]] =
    self
      .foldRight((z, Remote.list(z))) { case (elem, tuple) =>
        val agg  = tuple._1
        val lst  = tuple._2
        val agg2 = op(elem, agg)
        (agg2, agg2 :: lst)
      }
      ._2

  def segmentLength(p: Remote[A] => Remote[Boolean]): Remote[Int] =
    self.segmentLength(p, 0)
  def segmentLength(p: Remote[A] => Remote[Boolean], from: Remote[Int]): Remote[Int] =
    Remote.recurse[(Int, Int, List[A]), Int](
      (
        Remote(0),
        Remote(-1),
        self.drop(from)
      )
    ) { (input, rec) =>
      val currentMax = input._1
      val currentLen = input._2
      val remaining  = input._3

      Remote
        .UnCons(remaining)
        .fold(
          math.max(currentMax, currentLen + 1)
        ) { tuple =>
          val head = tuple._1
          val tail = tuple._2

          p(head).ifThenElse(
            ifTrue = rec((currentMax, currentLen + 1, tail)),
            ifFalse = rec((math.max(currentMax, currentLen + 1), Remote(-1), tail))
          )
        }
    }

  def size: Remote[Int] =
    self.length

  def slice(from: Remote[Int], until: Remote[Int]): Remote[List[A]] =
    self.drop(from).take(until - from)

  def sliding(size: Remote[Int], step: Remote[Int]): Remote[List[List[A]]] =
    Remote
      .recurse[List[A], List[List[A]]](self) { (remaining, rec) =>
        val next  = remaining.drop(step)
        val slice = remaining.take(size)

        next.isEmpty.ifThenElse(
          ifTrue = Remote.list(slice),
          ifFalse = slice :: rec(next)
        )
      }
      .reverse

  def sliding(size: Remote[Int]): Remote[List[List[A]]] =
    self.sliding(size, 1)

  // TODO: sortBy (sorted, sortWith) as native remote op?

  def span(p: Remote[A] => Remote[Boolean]): Remote[(List[A], List[A])] =
    (self.takeWhile(p), self.dropWhile(p))

  def splitAt(n: Remote[Int]): Remote[(List[A], List[A])] =
    (self.take(n), self.drop(n))

  def startsWith[B >: A](that: Remote[List[B]], offset: Remote[Int] = Remote(0)): Remote[Boolean] =
    (self.length >= that.length).ifThenElse(
      ifTrue = self.widen[List[B]].drop(offset).take(that.length) === that,
      ifFalse = Remote(false)
    )

  def sum(implicit numeric: Numeric[A]): Remote[A] =
    foldLeft[A](numeric.fromLong(0L))(_ + _)

  def tail: Remote[List[A]] =
    Remote
      .UnCons(self)
      .fold(
        Remote.fail("List is empty")
      )(_._2)

  def tails: Remote[List[List[A]]] =
    Remote
      .recurse[List[A], List[List[A]]](self) { (input, rec) =>
        input :: rec(self.tail)
      }
      .reverse

  def take(n: Remote[Int]): Remote[List[A]] =
    Remote
      .recurse[(Int, List[A]), List[A]]((n, self)) { case (input, rec) =>
        val count     = input._1
        val remaining = input._2

        Remote
          .UnCons(remaining)
          .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) =>
            (count === 0).ifThenElse(
              ifTrue = Remote.nil[A],
              ifFalse = tuple._1 :: rec((count - 1, tuple._2))
            )
          )
      }

  def takeRight(n: Remote[Int]): Remote[List[A]] =
    self.reverse.take(n)

  def takeWhile(p: Remote[A] => Remote[Boolean]): Remote[List[A]] =
    Remote
      .recurse[List[A], List[A]](self) { case (remaining, rec) =>
        Remote
          .UnCons(remaining)
          .fold(Remote.nil[A])((tuple: Remote[(A, List[A])]) =>
            p(tuple._1).ifThenElse(
              ifFalse = Remote.nil[A],
              ifTrue = tuple._1 :: rec(tuple._2)
            )
          )
      }

  def toList: Remote[List[A]] = self

  def toSet: Remote[Set[A]] =
    Remote.ListToSet(self)

  def unzip[A1, A2](implicit ev: A =:= (A1, A2)): Remote[(List[A1], List[A2])] =
    Remote.recurse[List[A], (List[A1], List[A2])](self) { (remaining, rec) =>
      Remote
        .UnCons(remaining)
        .fold(
          (Remote.nil[A1], Remote.nil[A2])
        ) { tuple =>
          val head         = tuple._1.asInstanceOf[Remote.Tuple2[A1, A2]]
          val tail         = tuple._2
          val unzippedTail = rec(tail)

          (head._1 :: unzippedTail._1, head._2 :: unzippedTail._2)
        }
    }

  def unzip3[A1, A2, A3](implicit ev: A =:= (A1, A2, A3)): Remote[(List[A1], List[A2], List[A3])] =
    Remote.recurse[List[A], (List[A1], List[A2], List[A3])](self) { (remaining, rec) =>
      Remote
        .UnCons(remaining)
        .fold(
          (Remote.nil[A1], Remote.nil[A2], Remote.nil[A3])
        ) { tuple =>
          val head         = tuple._1.asInstanceOf[Remote.Tuple3[A1, A2, A3]]
          val tail         = tuple._2
          val unzippedTail = rec(tail)

          (head._1 :: unzippedTail._1, head._2 :: unzippedTail._2, head._3 :: unzippedTail._3)
        }
    }

  def zip[B](that: Remote[List[B]]): Remote[List[(A, B)]] =
    Remote
      .recurseSimple((Remote.nil[(A, B)], self, that)) { case (input, rec) =>
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

  def zipAll[B](that: Remote[List[B]], thisElem: Remote[A], thatElem: Remote[B]): Remote[List[(A, B)]] =
    (self.length === that.length).ifThenElse(
      ifTrue = self.zip(that),
      ifFalse = (self.length < that.length).ifThenElse(
        ifTrue = self.zip(that) ++ that.drop(self.length).map(thatElem => (thisElem, thatElem)),
        ifFalse = self.zip(that) ++ self.drop(that.length).map(thisElem => (thisElem, thatElem))
      )
    )

  def zipWithIndex: Remote[List[(A, Int)]] =
    Remote
      .recurseSimple((Remote.nil[(A, Int)], self, Remote(0))) { case (input, rec) =>
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

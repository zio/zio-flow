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
import zio.flow.debug.TrackRemotes
import zio.schema.Schema

final class RemoteMapSyntax[K, V](val self: Remote[Map[K, V]], trackingEnabled: Boolean) {
  implicit private val remoteTracking: InternalRemoteTracking = InternalRemoteTracking(trackingEnabled)
  private val syntax                                          = TrackRemotes.ifEnabled
  import syntax._

  def +[V1 >: V](pair: Remote[(K, V)]): Remote[Map[K, V1]] =
    updated(pair._1, pair._2).trackInternal("Map#+")

  def ++[V2 >: V](xs: Remote[Map[K, V2]]): Remote[Map[K, V2]] =
    concat(xs).trackInternal("Map#++")

  def -(key: Remote[K]): Remote[Map[K, V]] =
    removed(key).trackInternal("Map#-")

  def --(keys: Remote[List[K]]): Remote[Map[K, V]] =
    removedAll(keys).trackInternal("Map#--")

  def apply(key: Remote[K]): Remote[V] =
    get(key).get.trackInternal("Map#apply")

  def applyOrElse[K1 <: K, V1 >: V](x: Remote[K1], default: Remote[K1] => Remote[V1]): Remote[V1] =
    get(x).getOrElse(default(x)).trackInternal("Map#applyOrElse")

  def concat[V2 >: V](xs: Remote[Map[K, V2]]): Remote[Map[K, V2]] =
    toList.concat(xs.toList).toMap.trackInternal("Map#concat")

  def contains(key: Remote[K]): Remote[Boolean] =
    toList.exists(_._1 === key).trackInternal("Map#contains")

  def corresponds[B](that: Remote[List[B]])(p: (Remote[(K, V)], Remote[B]) => Remote[Boolean]): Remote[Boolean] =
    toList.corresponds(that)(p).trackInternal("Map#corresponds")

  def count(p: Remote[(K, V)] => Remote[Boolean]): Remote[Int] =
    toList.count(p).trackInternal("Map#count")

  def drop(n: Remote[Int]): Remote[Map[K, V]] =
    toList.drop(n).toMap.trackInternal("Map#drop")

  def dropRight(n: Remote[Int]): Remote[Map[K, V]] =
    toList.dropRight(n).toMap.trackInternal("Map#dropRight")

  def dropWhile(p: Remote[(K, V)] => Remote[Boolean]): Remote[Map[K, V]] =
    toList.dropWhile(p).toMap.trackInternal("Map#dropWhile")

  def empty: Remote[Map[K, V]] =
    Remote.emptyMap.trackInternal("Map#empty")

  def exists(p: Remote[(K, V)] => Remote[Boolean]): Remote[Boolean] =
    toList.exists(p).trackInternal("Map#exists")

  def filter(pred: Remote[(K, V)] => Remote[Boolean]): Remote[Map[K, V]] =
    toList.filter(pred).toMap.trackInternal("Map#filter")

  def filterNot(pred: Remote[(K, V)] => Remote[Boolean]): Remote[Map[K, V]] =
    toList.filterNot(pred).toMap.trackInternal("Map#filterNot")

  def find(p: Remote[(K, V)] => Remote[Boolean]): Remote[Option[(K, V)]] =
    toList.find(p).trackInternal("Map#find")

  def flatMap[K2, V2](f: Remote[(K, V)] => Remote[Map[K2, V2]]): Remote[Map[K2, V2]] =
    toList.flatMap(pair => f(pair).toList).toMap.trackInternal("Map#flatMap")

  def fold[A1 >: (K, V)](z: Remote[A1])(op: (Remote[A1], Remote[A1]) => Remote[A1]): Remote[A1] =
    toList.fold(z)(op).trackInternal("Map#fold")

  def foldLeft[B](z: Remote[B])(op: (Remote[B], Remote[(K, V)]) => Remote[B]): Remote[B] =
    toList.foldLeft(z)(op).trackInternal("Map#foldLeft")

  def foldRight[B](z: Remote[B])(op: (Remote[(K, V)], Remote[B]) => Remote[B]): Remote[B] =
    toList.foldRight(z)(op).trackInternal("Map#foldRight")

  def forall(p: Remote[(K, V)] => Remote[Boolean]): Remote[Boolean] =
    toList.forall(p).trackInternal("Map#forall")

  def get(key: Remote[K]): Remote[Option[V]] =
    toList.find(_._1 === key).map(_._2).trackInternal("Map#get")

  def getOrElse[V1 >: V](key: Remote[K], default: Remote[V1]): Remote[V1] =
    get(key).getOrElse(default).trackInternal("Map#getOrElse")

  def groupBy[K2](f: Remote[(K, V)] => Remote[K2]): Remote[Map[K2, Map[K, V]]] =
    ??? // TODO

  def groupMap[K2, B](key: Remote[(K, V)] => K2)(f: Remote[(K, V)] => Remote[B]): Remote[Map[K2, List[B]]] =
    ??? // TODO

  def groupMapReduce[K2, B](key: Remote[(K, V)] => K2)(f: Remote[(K, V)] => Remote[B])(
    reduce: (Remote[B], Remote[B]) => Remote[B]
  ): Remote[Map[K2, B]] =
    ??? // TODO

  def grouped(size: Remote[Int]): Remote[List[Map[K, V]]] =
    toList.grouped(size).map(_.toMap).trackInternal("Map#grouped")

  def head: Remote[(K, V)] =
    toList.head.trackInternal("Map#head")

  def headOption: Remote[Option[(K, V)]] =
    toList.headOption.trackInternal("Map#headOption")

  def init: Remote[Map[K, V]] =
    toList.init.toMap.trackInternal("Map#init")

  def inits: Remote[List[Map[K, V]]] =
    toList.inits.map(_.toMap).trackInternal("Map#inits")

  def isDefinedAt(key: Remote[K]): Remote[Boolean] =
    contains(key).trackInternal("Map#isDefinedAt")

  def isEmpty: Remote[Boolean] =
    toList.isEmpty.trackInternal("Map#isEmpty")

  def keySet: Remote[Set[K]] =
    toList.map(_._1).toSet.trackInternal("Map#keySet")

  def keys: Remote[List[K]] =
    toList.map(_._1).trackInternal("Map#keys")

  def last: Remote[(K, V)] =
    toList.last.trackInternal("Map#last")

  def lastOption: Remote[Option[(K, V)]] =
    toList.lastOption.trackInternal("Map#lastOption")

  def lift: Remote[K] => Remote[Option[V]] =
    (key: Remote[K]) => self.get(key)

  def map[K2, V2](f: Remote[(K, V)] => Remote[(K2, V2)]): Remote[Map[K2, V2]] =
    toList.map(f).toMap.trackInternal("Map#map")

  def mkString(implicit schemaK: Schema[K], schemaV: Schema[V]): Remote[String] =
    toList.mkString.trackInternal("Map#mkString")

  def mkString(sep: Remote[String])(implicit schemaK: Schema[K], schemaV: Schema[V]) =
    toList.mkString(sep).trackInternal("Map#mkString")

  def mkString(start: Remote[String], sep: Remote[String], end: Remote[String])(implicit
    schemaK: Schema[K],
    schemaV: Schema[V]
  ): Remote[String] =
    toList.mkString(start, sep, end).trackInternal("Map#mkString")

  def nonEmpty: Remote[Boolean] =
    toList.nonEmpty.trackInternal("Map#nonEmpty")

  def partition(p: Remote[(K, V)] => Remote[Boolean]): Remote[(Map[K, V], Map[K, V])] =
    Remote
      .bind(toList.partition(p)) { pair =>
        (pair._1.toMap, pair._2.toMap)
      }
      .trackInternal("Map#partition")

  def partitionMap[A1, A2](p: Remote[(K, V)] => Remote[Either[A1, A2]]): Remote[(List[A1], List[A2])] =
    toList.partitionMap(p).trackInternal("Map#partitionMap")

  def reduce[B >: (K, V)](op: (Remote[B], Remote[B]) => Remote[B]): Remote[B] =
    toList.reduce(op).trackInternal("Map#reduce")

  def reduceLeft[B >: (K, V)](op: (Remote[B], Remote[(K, V)]) => Remote[B]): Remote[B] =
    toList.reduceLeft(op).trackInternal("Map#reduceLeft")

  def reduceLeftOption[B >: (K, V)](op: (Remote[B], Remote[(K, V)]) => Remote[B]): Remote[Option[B]] =
    toList.reduceLeftOption(op).trackInternal("Map#reduceLeftOption")

  def reduceOption[B >: (K, V)](op: (Remote[B], Remote[B]) => Remote[B]): Remote[Option[B]] =
    toList.reduceOption(op).trackInternal("Map#reduceOption")

  def reduceRight[B >: (K, V)](op: (Remote[(K, V)], Remote[B]) => Remote[B]): Remote[B] =
    toList.reduceRight(op).trackInternal("Map#reduceRight")

  def reduceRightOption[B >: (K, V)](op: (Remote[(K, V)], Remote[B]) => Remote[B]): Remote[Option[B]] =
    toList.reduceRightOption(op).trackInternal("Map#reduceRightOption")

  def removed(key: Remote[K]): Remote[Map[K, V]] =
    toList.filterNot(pair => pair._1 === key).toMap.trackInternal("Map#removed")

  def removedAll(keys: Remote[List[K]]): Remote[Map[K, V]] =
    keys.foldLeft(self)((remaining, key) => remaining.removed(key)).trackInternal("Map#removedAll")

  def scan[B >: (K, V)](z: Remote[B])(op: (Remote[B], Remote[B]) => Remote[B]): Remote[List[B]] =
    toList.scan(z)(op).trackInternal("Map#scan")

  def scanLeft[B >: (K, V)](z: Remote[B])(op: (Remote[B], Remote[(K, V)]) => Remote[B]): Remote[List[B]] =
    toList.scanLeft(z)(op).trackInternal("Map#scanLeft")

  def scanRight[B >: (K, V)](z: Remote[B])(op: (Remote[(K, V)], Remote[B]) => Remote[B]): Remote[List[B]] =
    toList.scanRight(z)(op).trackInternal("Map#scanRight")

  def size: Remote[Int] =
    toList.size.trackInternal("Map#size")

  def slice(from: Remote[Int], until: Remote[Int]): Remote[Map[K, V]] =
    toList.slice(from, until).toMap.trackInternal("Map#slice")

  def sliding(size: Remote[Int], step: Remote[Int]): Remote[List[Map[K, V]]] =
    toList.sliding(size, step).map(_.toMap).trackInternal("Map#sliding")

  def sliding(size: Remote[Int]): Remote[List[Map[K, V]]] =
    toList.sliding(size).map(_.toMap).trackInternal("Map#sliding")

  def span(p: Remote[(K, V)] => Remote[Boolean]): Remote[(Map[K, V], Map[K, V])] =
    Remote
      .bind(toList.span(p)) { pair =>
        (pair._1.toMap, pair._2.toMap)
      }
      .trackInternal("Map#span")

  def splitAt(n: Remote[Int]): Remote[(Map[K, V], Map[K, V])] =
    Remote
      .bind(toList.splitAt(n)) { pair =>
        (pair._1.toMap, pair._2.toMap)
      }
      .trackInternal("Map#splitAt")

  def tail: Remote[Map[K, V]] =
    toList.tail.toMap.trackInternal("Map#tail")

  def tails: Remote[List[Map[K, V]]] =
    toList.tails.map(_.toMap).trackInternal("Map#tails")

  def take(n: Remote[Int]): Remote[Map[K, V]] =
    toList.take(n).toMap.trackInternal("Map#take")

  def takeRight(n: Remote[Int]): Remote[Map[K, V]] =
    toList.takeRight(n).toMap.trackInternal("Map#takeRight")

  def updated[V1 >: V](key: Remote[K], value: Remote[V1]): Remote[Map[K, V1]] =
    ((key, value) :: removed(key).toList).toMap.trackInternal("Map#updated")

  def toList: Remote[List[(K, V)]] =
    Remote.MapToList(self).trackInternal("Map#toList")

  def toSet: Remote[Set[(K, V)]] =
    toList.toSet.trackInternal("Map#toSet")

  def unzip: Remote[(List[K], List[V])] =
    toList.unzip[K, V].trackInternal("Map#unzip")

  def values: Remote[List[V]] =
    toList.map(_._2).trackInternal("Map#values")

  def zip[B](that: Remote[List[B]]): Remote[List[((K, V), B)]] =
    toList.zip(that).trackInternal("Map#zip")

  def zipAll[B](that: Remote[List[B]], thisElem: Remote[(K, V)], thatElem: Remote[B]): Remote[List[((K, V), B)]] =
    toList.zipAll(that, thisElem, thatElem).trackInternal("Map#zipAll")
}

/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

package zio.flow

import zio.schema._
import zio.ZNothing
import zio.{Chunk, Duration}

import java.time.temporal.{ChronoField, ChronoUnit}
import scala.util.Try
import scala.util.matching.Regex

trait Schemas extends LowerPrioritySchemas {

  // NOTE: Schema.Fail would be more correct but that makes it unserializable currently
  implicit val schemaZNothing: Schema[ZNothing] =
    Schema[Unit].transformOrFail[ZNothing](_ => Left("nothing"), (_: ZNothing) => Left("nothing"))

  implicit val schemaDuration: Schema[Duration] = Schema.Primitive(StandardType.DurationType)

  implicit val schemaUri: Schema[java.net.URI] = Schema[String].transformOrFail(
    s => Try(new java.net.URI(s)).toEither.left.map(_.getMessage),
    uri => Right(uri.toString)
  )

  implicit lazy val schemaThrowable: Schema[Throwable] =
    Schema.CaseClass4[Option[Throwable], Option[String], Chunk[StackTraceElement], Chunk[Throwable], Throwable](
      TypeId.parse("java.lang.Throwable"),
      field01 = Schema.Field[Throwable, Option[Throwable]](
        "cause",
        Schema.defer(schemaThrowable.optional),
        get0 = throwable => {
          val cause = throwable.getCause
          if (cause == null || cause == throwable) None else Some(cause)
        },
        set0 = (_: Throwable, _: Option[Throwable]) => ???
      ),
      field02 = Schema.Field[Throwable, Option[String]](
        "message",
        Schema[Option[String]],
        get0 = throwable => Option(throwable.getMessage),
        set0 = (_: Throwable, _: Option[String]) => ???
      ),
      field03 = Schema.Field[Throwable, Chunk[StackTraceElement]](
        "stackTrace",
        Schema[Chunk[StackTraceElement]],
        get0 = throwable => Chunk.fromArray(throwable.getStackTrace),
        set0 = (_: Throwable, _: Chunk[StackTraceElement]) => ???
      ),
      field04 = Schema.Field[Throwable, Chunk[Throwable]](
        "suppressed",
        Schema.defer(Schema.chunk(schemaThrowable)),
        get0 = throwable => Chunk.fromArray(throwable.getSuppressed),
        set0 = (_: Throwable, _: Chunk[Throwable]) => ???
      ),
      construct0 = (
        cause: Option[Throwable],
        message: Option[String],
        stackTrace: Chunk[StackTraceElement],
        suppressed: Chunk[Throwable]
      ) => {
        val throwable = new Throwable(message.orNull, cause.orNull)
        throwable.setStackTrace(stackTrace.toArray)
        suppressed.foreach(throwable.addSuppressed)
        throwable
      }
    )

  implicit lazy val schemaStackTraceElement: Schema[StackTraceElement] =
    Schema.CaseClass4(
      TypeId.parse("java.lang.StackTraceElement"),
      field01 = Schema.Field(
        "declaringClass",
        Schema[String],
        get0 = stackTraceElement => stackTraceElement.getClassName,
        set0 = (_: StackTraceElement, _: String) => ???
      ),
      field02 = Schema.Field(
        "methodName",
        Schema[String],
        get0 = stackTraceElement => stackTraceElement.getMethodName,
        set0 = (_: StackTraceElement, _: String) => ???
      ),
      field03 = Schema.Field(
        "fileName",
        Schema[String],
        get0 = stackTraceElement => stackTraceElement.getFileName,
        set0 = (_: StackTraceElement, _: String) => ???
      ),
      field04 = Schema.Field(
        "lineNumber",
        Schema[Int],
        get0 = stackTraceElement => stackTraceElement.getLineNumber,
        set0 = (_: StackTraceElement, _: Int) => ???
      ),
      construct0 = (declaringClass: String, methodName: String, fileName: String, lineNumber: Int) =>
        new StackTraceElement(declaringClass, methodName, fileName, lineNumber)
    )

  implicit def schemaTry[A](implicit schema: Schema[A]): Schema[scala.util.Try[A]] =
    Schema.Enum2[scala.util.Failure[A], scala.util.Success[A], scala.util.Try[A]](
      TypeId.parse("scala.util.Try"),
      case1 = Schema.Case(
        "Failure",
        schemaFailure,
        _.asInstanceOf[scala.util.Failure[A]],
        x => x,
        _.isInstanceOf[scala.util.Failure[A]]
      ),
      case2 = Schema.Case(
        "Success",
        schemaSuccess,
        _.asInstanceOf[scala.util.Success[A]],
        x => x,
        _.isInstanceOf[scala.util.Success[A]]
      )
    )

  implicit def schemaFailure[A]: Schema[scala.util.Failure[A]] =
    Schema.CaseClass1(
      TypeId.parse("scala.util.Failure"),
      field0 = Schema.Field(
        "exception",
        Schema[Throwable],
        get0 = _.exception,
        set0 = (a: scala.util.Failure[A], v: Throwable) => a.copy(exception = v)
      ),
      defaultConstruct0 = (throwable: Throwable) => scala.util.Failure(throwable)
    )

  implicit def schemaSuccess[A](implicit schema: Schema[A]): Schema[scala.util.Success[A]] =
    Schema.CaseClass1(
      TypeId.parse("scala.util.Success"),
      field0 =
        Schema.Field("value", schema, get0 = _.value, set0 = (a: scala.util.Success[A], v: A) => a.copy(value = v)),
      defaultConstruct0 = (value: A) => scala.util.Success(value)
    )

  implicit val chronoUnitSchema: Schema[ChronoUnit] =
    Schema[String].transformOrFail(
      s => Try(ChronoUnit.valueOf(s)).toEither.left.map(_.getMessage),
      (unit: ChronoUnit) => Right(unit.name())
    )

  implicit val chronoFieldSchema: Schema[ChronoField] =
    Schema[String].transformOrFail(
      s => Try(ChronoField.valueOf(s)).toEither.left.map(_.getMessage),
      (unit: ChronoField) => Right(unit.name())
    )

  implicit val regexSchema: Schema[Regex] =
    Schema[String].transformOrFail(
      s => Try(s.r).toEither.left.map(_.getMessage),
      (r: Regex) => Right(r.regex)
    )
}

trait LowerPrioritySchemas {}

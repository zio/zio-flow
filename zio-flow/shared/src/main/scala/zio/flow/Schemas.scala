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

package zio.flow

import zio.schema._
import zio.ZNothing
import zio.{Chunk, Duration}

import java.time.temporal.{ChronoField, ChronoUnit}
import scala.util.Try

trait Schemas extends LowerPrioritySchemas with DefaultJavaTimeSchemas {

  // NOTE: Schema.Fail would be more correct but that makes it unserializable currently
  implicit val schemaZNothing: Schema[ZNothing] =
    Schema[Unit].transformOrFail[ZNothing](_ => Left("nothing"), (_: ZNothing) => Left("nothing"))

  implicit val schemaDuration: Schema[Duration] = Schema.Primitive(StandardType.DurationType)

  implicit val schemaUri: Schema[java.net.URI] = Schema[String].transformOrFail(
    s => Try(new java.net.URI(s)).toEither.left.map(_.getMessage),
    uri => Right(uri.toString)
  )

  implicit lazy val schemaThrowable: Schema[Throwable] =
    Schema.CaseClass4(
      TypeId.parse("java.lang.Throwable"),
      field1 = Schema.Field("cause", Schema.defer(Schema[Option[Throwable]])),
      field2 = Schema.Field("message", Schema[Option[String]]),
      field3 = Schema.Field("stackTrace", Schema[Chunk[StackTraceElement]]),
      field4 = Schema.Field("suppressed", Schema.defer(Schema[Chunk[Throwable]])),
      construct = (
        cause: Option[Throwable],
        message: Option[String],
        stackTrace: Chunk[StackTraceElement],
        suppressed: Chunk[Throwable]
      ) => {
        val throwable = new Throwable(message.orNull, cause.orNull)
        throwable.setStackTrace(stackTrace.toArray)
        suppressed.foreach(throwable.addSuppressed)
        throwable
      },
      extractField1 = throwable => {
        val cause = throwable.getCause
        if (cause == null || cause == throwable) None else Some(cause)
      },
      extractField2 = throwable => Option(throwable.getMessage),
      extractField3 = throwable => Chunk.fromArray(throwable.getStackTrace),
      extractField4 = throwable => Chunk.fromArray(throwable.getSuppressed)
    )

  implicit lazy val schemaStackTraceElement: Schema[StackTraceElement] =
    Schema.CaseClass4(
      TypeId.parse("java.lang.StackTraceElement"),
      field1 = Schema.Field("declaringClass", Schema[String]),
      field2 = Schema.Field("methodName", Schema[String]),
      field3 = Schema.Field("fileName", Schema[String]),
      field4 = Schema.Field("lineNumber", Schema[Int]),
      construct = (declaringClass: String, methodName: String, fileName: String, lineNumber: Int) =>
        new StackTraceElement(declaringClass, methodName, fileName, lineNumber),
      extractField1 = stackTraceElement => stackTraceElement.getClassName,
      extractField2 = stackTraceElement => stackTraceElement.getMethodName,
      extractField3 = stackTraceElement => stackTraceElement.getFileName,
      extractField4 = stackTraceElement => stackTraceElement.getLineNumber
    )

  implicit def schemaTry[A](implicit schema: Schema[A]): Schema[scala.util.Try[A]] =
    Schema.Enum2[scala.util.Failure[A], scala.util.Success[A], scala.util.Try[A]](
      TypeId.parse("scala.util.Try"),
      case1 = Schema.Case("Failure", schemaFailure, _.asInstanceOf[scala.util.Failure[A]]),
      case2 = Schema.Case("Success", schemaSuccess, _.asInstanceOf[scala.util.Success[A]])
    )

  implicit def schemaFailure[A]: Schema[scala.util.Failure[A]] =
    Schema.CaseClass1(
      TypeId.parse("scala.util.Failure"),
      field = Schema.Field("exception", Schema[Throwable]),
      construct = (throwable: Throwable) => scala.util.Failure(throwable),
      extractField = _.exception
    )

  implicit def schemaSuccess[A](implicit schema: Schema[A]): Schema[scala.util.Success[A]] =
    Schema.CaseClass1(
      TypeId.parse("scala.util.Success"),
      field = Schema.Field("value", schema),
      construct = (value: A) => scala.util.Success(value),
      extractField = _.value
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
}

trait LowerPrioritySchemas {}

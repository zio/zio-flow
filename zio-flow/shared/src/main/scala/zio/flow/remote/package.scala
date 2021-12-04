package zio.flow

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{ Duration, Instant }

import scala.language.implicitConversions

import zio.Chunk
import zio.schema.Schema.Primitive
import zio.schema.{ Schema, StandardType }

package object remote {
  type RemoteDuration    = Remote[Duration]
  type RemoteInstant     = Remote[Instant]
  type RemoteVariable[A] = Remote[Variable[A]]
  type SchemaOption[A]   = Schema.Optional[A]
  type SchemaList[A]     = Schema[List[A]]

  implicit val schemaDuration: Schema[Duration] = Primitive(StandardType.Duration(ChronoUnit.SECONDS))
  implicit val schemaInstant: Schema[Instant]   = Primitive(StandardType.Instant(DateTimeFormatter.BASIC_ISO_DATE))

  implicit lazy val schemaThrowable: Schema[Throwable] =
    Schema.CaseClass4(
      field1 = Schema.Field("cause", schemaThrowable),
      field2 = Schema.Field("message", Schema[String]),
      field3 = Schema.Field("stackTrace", Schema[Chunk[StackTraceElement]]),
      field4 = Schema.Field("suppressed", Schema[Chunk[Throwable]]),
      construct =
        (cause: Throwable, message: String, stackTrace: Chunk[StackTraceElement], suppressed: Chunk[Throwable]) => {
          val throwable = new Throwable(message, cause)
          throwable.setStackTrace(stackTrace.toArray)
          suppressed.foreach(throwable.addSuppressed)
          throwable
        },
      extractField1 = throwable => throwable.getCause,
      extractField2 = throwable => throwable.getMessage,
      extractField3 = throwable => Chunk.fromArray(throwable.getStackTrace),
      extractField4 = throwable => Chunk.fromArray(throwable.getSuppressed)
    )

  implicit lazy val schemaStackTraceElement: Schema[StackTraceElement] =
    Schema.CaseClass4(
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

  implicit def schemaEither[A, B](implicit aSchema: Schema[A], bSchema: Schema[B]): Schema[Either[A, B]] =
    Schema.EitherSchema(aSchema, bSchema)

  implicit def RemoteVariable[A](remote: Remote[Variable[A]]): RemoteVariableSyntax[A] = new RemoteVariableSyntax(
    remote
  )
  implicit def RemoteInstant(remote: Remote[Instant]): RemoteInstantSyntax             = new RemoteInstantSyntax(
    remote
  )
  implicit def RemoteDuration(remote: Remote[Duration]): RemoteDurationSyntax          = new RemoteDurationSyntax(
    remote
  )
  implicit def RemoteBoolean(remote: Remote[Boolean]): RemoteBooleanSyntax             = new RemoteBooleanSyntax(remote)

  implicit def RemoteEither[A, B](remote: Remote[Either[A, B]]): RemoteEitherSyntax[A, B] = new RemoteEitherSyntax(
    remote
  )
  implicit def RemoteTuple2[A, B](remote: Remote[(A, B)]): RemoteTuple2Syntax[A, B]       = new RemoteTuple2Syntax(remote)

  implicit def RemoteTuple3[A, B, C](remote: Remote[(A, B, C)]): RemoteTuple3Syntax[A, B, C]          = new RemoteTuple3Syntax(
    remote
  )
  implicit def RemoteTuple4[A, B, C, D](remote: Remote[(A, B, C, D)]): RemoteTuple4Syntax[A, B, C, D] =
    new RemoteTuple4Syntax(remote)

  implicit def RemoteTuple5[A, B, C, D, E](remote: Remote[(A, B, C, D, E)]): RemoteTuple5Syntax[A, B, C, D, E] =
    new RemoteTuple5Syntax(remote)

  implicit def RemoteList[A](remote: Remote[List[A]]): RemoteListSyntax[A] = new RemoteListSyntax[A](remote)

  implicit def RemoteOption[A](remote: Remote[Option[A]]): RemoteOptionSyntax[A] = new RemoteOptionSyntax[A](remote)

  implicit def RemoteString(remote: Remote[String]): RemoteStringSyntax = new RemoteStringSyntax(remote)

  implicit def RemoteExecutingFlow[A](remote: Remote[A]): RemoteExecutingFlowSyntax[A] =
    new RemoteExecutingFlowSyntax[A](remote)

  implicit def RemoteNumeric[A](remote: Remote[A]): RemoteNumericSyntax[A] = new RemoteNumericSyntax[A](remote)

  implicit def RemoteRelational[A](remote: Remote[A]): RemoteRelationalSyntax[A] = new RemoteRelationalSyntax[A](remote)

  implicit def RemoteFractional[A](remote: Remote[A]): RemoteFractionalSyntax[A] = new RemoteFractionalSyntax[A](remote)
}

package zio.flow

import zio.schema._
import zio.{Chunk, Duration}

import java.time.Instant
import java.time.format.DateTimeFormatter

trait SchemaOrNothing {
  type A
  val schema: Schema[A]

  override def toString: String = schema.toString
}

object SchemaOrNothing {
  type Aux[_A] = SchemaOrNothing {
    type A = _A
  }

  implicit def fromSchema[_A: Schema]: SchemaOrNothing.Aux[_A] = new SchemaOrNothing {
    override type A = _A
    override val schema: Schema[_A] = Schema[_A]
  }

  implicit def nothing: SchemaOrNothing.Aux[Nothing] = new SchemaOrNothing {
    override type A = Nothing
    override val schema: Schema[Nothing] = Schema.fail("nothing")
  }

  def apply[A: SchemaOrNothing.Aux]: SchemaOrNothing.Aux[A] = implicitly[SchemaOrNothing.Aux[A]]
}

trait Schemas extends LowerPrioritySchemas {

  implicit val schemaDuration: Schema[Duration] = Schema.Primitive(StandardType.DurationType)
  implicit val schemaInstant: Schema[Instant] =
    Schema.Primitive(StandardType.InstantType(DateTimeFormatter.BASIC_ISO_DATE))

  implicit lazy val schemaThrowable: Schema[Throwable] =
    Schema.CaseClass4(
      field1 = Schema.Field("cause", Schema.defer(Schema[Option[Throwable]])),
      field2 = Schema.Field("message", Schema[String]),
      field3 = Schema.Field("stackTrace", Schema[Chunk[StackTraceElement]]),
      field4 = Schema.Field("suppressed", Schema.defer(Schema[Chunk[Throwable]])),
      construct = (
        cause: Option[Throwable],
        message: String,
        stackTrace: Chunk[StackTraceElement],
        suppressed: Chunk[Throwable]
      ) => {
        val throwable = new Throwable(message, cause.orNull)
        throwable.setStackTrace(stackTrace.toArray)
        suppressed.foreach(throwable.addSuppressed)
        throwable
      },
      extractField1 = throwable => {
        val cause = throwable.getCause
        if (cause == null || cause == throwable) None else Some(cause)
      },
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

  implicit def schemaTry[A](implicit schema: Schema[A]): Schema[scala.util.Try[A]] =
    Schema.Enum2[scala.util.Failure[A], scala.util.Success[A], scala.util.Try[A]](
      case1 = Schema.Case("Failure", schemaFailure, _.asInstanceOf[scala.util.Failure[A]]),
      case2 = Schema.Case("Success", schemaSuccess, _.asInstanceOf[scala.util.Success[A]])
    )

  implicit def schemaFailure[A]: Schema[scala.util.Failure[A]] =
    Schema.CaseClass1(
      field = Schema.Field("exception", Schema[Throwable]),
      construct = (throwable: Throwable) => scala.util.Failure(throwable),
      extractField = _.exception
    )

  implicit def schemaSuccess[A](implicit schema: Schema[A]): Schema[scala.util.Success[A]] =
    Schema.CaseClass1(
      field = Schema.Field("value", schema),
      construct = (value: A) => scala.util.Success(value),
      extractField = _.value
    )

  implicit def schemaEither[A, B](implicit aSchema: Schema[A], bSchema: Schema[B]): Schema[Either[A, B]] =
    Schema.EitherSchema(aSchema, bSchema)
}

trait LowerPrioritySchemas {}

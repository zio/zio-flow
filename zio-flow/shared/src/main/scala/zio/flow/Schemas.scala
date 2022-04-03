package zio.flow

import zio.schema._
import zio.{Chunk, Duration}

import java.time.temporal.ChronoUnit
import scala.util.Try

// TODO: get rid of the whole thing
trait SchemaOrNothing {
  type A
  val schema: Schema[A]

  override def toString: String = schema.toString
}

object SchemaOrNothing {
  type Aux[_A] = SchemaOrNothing {
    type A = _A
  }

  // TODO: fromAst?

  implicit def fromSchema[_A: Schema]: SchemaOrNothing.Aux[_A] = new SchemaOrNothing {
    override type A = _A
    override val schema: Schema[_A] = Schema[_A]
  }

  implicit def nothing: SchemaOrNothing.Aux[Nothing] = new SchemaOrNothing {
    override type A = Nothing

    // NOTE: Schema.Fail would be more correct but that makes it unserializable currently
    override val schema: Schema[Nothing] =
      Schema[Unit].transformOrFail[Nothing](_ => Left("nothing"), (_: Nothing) => Left("nothing"))
  }

  def apply[A: SchemaOrNothing.Aux]: SchemaOrNothing.Aux[A] = implicitly[SchemaOrNothing.Aux[A]]
}

trait Schemas extends LowerPrioritySchemas with DefaultJavaTimeSchemas {

  implicit val schemaDuration: Schema[Duration] = Schema.Primitive(StandardType.DurationType)

  implicit val schemaUri: Schema[java.net.URI] = Schema[String].transformOrFail(
    s => Try(new java.net.URI(s)).toEither.left.map(_.getMessage),
    uri => Right(uri.toString)
  )

  implicit lazy val schemaThrowable: Schema[Throwable] =
    Schema.CaseClass4(
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

  implicit val chronoUnitSchema: Schema[ChronoUnit] =
    Schema[String].transformOrFail(
      s => Try(ChronoUnit.valueOf(s)).toEither.left.map(_.getMessage),
      (unit: ChronoUnit) => Right(unit.name())
    )
}

trait LowerPrioritySchemas {}

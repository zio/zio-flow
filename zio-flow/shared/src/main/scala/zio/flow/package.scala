package zio

import zio.flow.remote.{Remote, RemoteBooleanSyntax, RemoteDurationSyntax, RemoteEitherSyntax, RemoteExecutingFlowSyntax, RemoteFractionalSyntax, RemoteInstantSyntax, RemoteListSyntax, RemoteNumericSyntax, RemoteOptionSyntax, RemoteRelationalSyntax, RemoteStringSyntax, RemoteTuple2Syntax, RemoteTuple3Syntax, RemoteTuple4Syntax, RemoteVariableSyntax}

import java.time._
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.language.implicitConversions
import zio.schema.Schema.Primitive
import zio.schema.{Schema, StandardType}

package object flow {
  type ActivityError = Throwable

  type Variable[A]
  type ExecutingFlow[+E, +A]
}

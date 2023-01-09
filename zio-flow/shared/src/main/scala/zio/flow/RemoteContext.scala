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

import zio.ZIO
import zio.flow.runtime.{ExecutorError, Timestamp}
import zio.schema.{DynamicValue, Schema}

import java.util.UUID

trait RemoteContext {
  def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[Any, ExecutorError, Unit]
  def getVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[DynamicValue]]
  def getLatestTimestamp(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[Timestamp]]
  def dropVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Unit]

  def readConfig[A: Schema](key: ConfigKey): ZIO[Any, ExecutorError, Option[A]]
}

object RemoteContext {
  def generateFreshVariableName: RemoteVariableName =
    RemoteVariableName.unsafeMake(s"p_${UUID.randomUUID()}")

  def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[RemoteContext, ExecutorError, Unit] =
    ZIO.serviceWithZIO(_.setVariable(name, value))
  def getVariable(name: RemoteVariableName): ZIO[RemoteContext, ExecutorError, Option[DynamicValue]] =
    ZIO.serviceWithZIO(_.getVariable(name))
  def getLatestTimestamp(name: RemoteVariableName): ZIO[RemoteContext, ExecutorError, Option[Timestamp]] =
    ZIO.serviceWithZIO(_.getLatestTimestamp(name))
  def dropVariable(name: RemoteVariableName): ZIO[RemoteContext, ExecutorError, Unit] =
    ZIO.serviceWithZIO(_.dropVariable(name))
  def readConfig[A: Schema](key: ConfigKey): ZIO[RemoteContext, ExecutorError, Option[A]] =
    ZIO.serviceWithZIO(_.readConfig[A](key))

  def eval[A: Schema](remote: Remote[A]): ZIO[RemoteContext, ExecutorError, A] =
    evalDynamic(remote).flatMap(dyn =>
      ZIO
        .fromEither(dyn.toTypedValue(implicitly[Schema[A]]))
        .mapError(ExecutorError.TypeError("eval", _))
    )

  def evalDynamic[A](remote: Remote[A]): ZIO[RemoteContext, ExecutorError, DynamicValue] =
    (for {
      vars0 <- LocalContext.getAllVariables
      dyn   <- remote.evalDynamic.mapError(ExecutorError.RemoteEvaluationError.apply)
      vars1 <- LocalContext.getAllVariables
      vars   = vars1.diff(vars0)

      remote       = Remote.fromDynamic(dyn)
      usedByResult = remote.variableUsage.variables

      usedByVars <- ZIO.foldLeft(vars)(Set.empty[RemoteVariableName]) { case (set, variable) =>
                      for {
                        optDynVar <- RemoteContext.getVariable(variable.identifier)
                        result = optDynVar match {
                                   case Some(dynVar) =>
                                     val remoteVar = Remote.fromDynamic(dynVar)
                                     set union remoteVar.variableUsage.variables
                                   case None =>
                                     set
                                 }
                      } yield result
                    }
      toRemove = vars.map(_.identifier).diff(usedByResult union usedByVars)

      _ <- ZIO.foreachDiscard(toRemove)(RemoteContext.dropVariable)
    } yield dyn)
      .provideSomeLayer[RemoteContext](LocalContext.inMemory)
}

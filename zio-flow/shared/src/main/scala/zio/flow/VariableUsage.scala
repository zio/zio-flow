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

case class VariableUsage(
  variables: Set[RemoteVariableName],
  bindings: Set[BindingName]
) {
  def union(other: VariableUsage): VariableUsage =
    VariableUsage(
      variables union other.variables,
      bindings union other.bindings
    )

  def removeBinding(name: BindingName): VariableUsage =
    this.copy(bindings = this.bindings - name)
}

object VariableUsage {
  val none: VariableUsage = VariableUsage(Set.empty, Set.empty)

  def variable(name: RemoteVariableName): VariableUsage = VariableUsage(Set(name), Set.empty)
  def binding(name: BindingName): VariableUsage         = VariableUsage(Set.empty, Set(name))
}

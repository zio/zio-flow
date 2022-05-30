///*
// * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
package zio.flow.remote

import zio.flow.Remote
import zio.schema.Schema

final class RemoteBooleanSyntax(val self: Remote[Boolean]) extends AnyVal {

  def not: Remote[Boolean] = Remote.Not(self)

  def &&(that: Remote[Boolean]): Remote[Boolean] =
    Remote.And(self, that)

  def ||(that: Remote[Boolean]): Remote[Boolean] =
    (not && that.not).not

  def ifThenElse[B: Schema](ifTrue: Remote[B], ifFalse: Remote[B]): Remote[B] =
    Remote.Branch(self, Remote.suspend(ifTrue), Remote.suspend(ifFalse))

  def unary_! : Remote[Boolean] =
    not
}

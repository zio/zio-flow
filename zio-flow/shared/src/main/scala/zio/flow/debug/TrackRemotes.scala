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

package zio.flow.debug

import zio.Chunk
import zio.flow.remote.{
  InternalRemoteTracking,
  RemoteChunkSyntax,
  RemoteListSyntax,
  RemoteMapSyntax,
  RemoteStringSyntax
}
import zio.flow.{Remote, Syntax}

import scala.language.implicitConversions

object TrackRemotes extends Syntax {

  override implicit def RemoteList[A](remote: Remote[List[A]]): RemoteListSyntax[A] =
    new RemoteListSyntax(remote, trackingEnabled = true)

  override implicit def RemoteString(remote: Remote[String]): RemoteStringSyntax =
    new RemoteStringSyntax(remote, trackingEnabled = true)

  override implicit def RemoteChunk[A](remote: Remote[Chunk[A]]): RemoteChunkSyntax[A] =
    new RemoteChunkSyntax(remote, trackingEnabled = true)

  override implicit def RemoteMap[K, V](remote: Remote[Map[K, V]]): RemoteMapSyntax[K, V] =
    new RemoteMapSyntax(remote, trackingEnabled = true)

  def ifEnabled(implicit remoteTracking: InternalRemoteTracking): Syntax =
    if (remoteTracking.enabled)
      TrackRemotes
    else
      zio.flow.syntax
}

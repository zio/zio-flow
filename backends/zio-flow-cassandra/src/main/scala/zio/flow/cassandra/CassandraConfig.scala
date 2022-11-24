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

package zio.flow.cassandra

import zio.{Chunk, Config}

import java.net.InetSocketAddress
import scala.util.Try

final case class CassandraConfig(
  contactPoints: List[InetSocketAddress],
  kvStoreKeyspace: String,
  ixStoreKeyspace: String,
  localDatacenter: String
)

object CassandraConfig {
  private val inetSocketAddess: Config[InetSocketAddress] =
    Config.string.mapOrFail { s =>
      val parts = s.split(":")
      if (parts.length == 2) {
        val host = parts(0)
        Try(parts(1).toInt).toEither.left
          .map(error => Config.Error.InvalidData(Chunk("port"), error.getMessage))
          .flatMap { port =>
            Try(new InetSocketAddress(host, port)).toEither.left.map(error =>
              Config.Error.InvalidData(Chunk.empty, error.getMessage)
            )
          }
      } else Left(Config.Error.InvalidData(Chunk.empty, "contact point must be in host:port format"))
    }

  val config: Config[CassandraConfig] =
    (Config.listOf("contact-points", inetSocketAddess) ++
      Config.string("key-value-store-keyspace") ++
      Config.string("indexed-store-keyspace") ++
      Config.string("local-datacenter")).map {
      case (contactPoints, kvStoreKeyspace, ixStoreKeyspace, localDatacenter) =>
        CassandraConfig(contactPoints, kvStoreKeyspace, ixStoreKeyspace, localDatacenter)
    }
}

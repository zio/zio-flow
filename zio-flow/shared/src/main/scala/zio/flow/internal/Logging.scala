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

package zio.flow.internal

import zio.ZIO
import zio.flow.TransactionId

object Logging {

  def optionalTransactionId[R, E, A](transactionId: => Option[TransactionId])(f: ZIO[R, E, A]): ZIO[R, E, A] =
    optionalAnnotate("txId", transactionId.map(TransactionId.unwrap))(f)

  def optionalAnnotate[R, E, A](key: => String, value: => Option[String])(f: ZIO[R, E, A]): ZIO[R, E, A] =
    value match {
      case Some(value) => ZIO.logAnnotate(key, value)(f)
      case None        => f
    }
}

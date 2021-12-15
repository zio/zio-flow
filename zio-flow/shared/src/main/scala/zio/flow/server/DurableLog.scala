/*
 * Copyright 2021 John A. De Goes and the ZIO Contributors
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

package zio.flow.server

import java.io.IOException

import zio._
import zio.stream._

trait DurableLog {
  def append(topic: String, value: Chunk[Byte]): IO[IOException, Long]
  def subscribe(topic: String, position: Long): ZStream[Any, IOException, Chunk[Byte]]
}

object DurableLog {

  def append(topic: String, value: Chunk[Byte]): ZIO[Has[DurableLog], IOException, Long] =
    ZIO.serviceWith(_.append(topic, value))

  def subscribe(topic: String, position: Long): ZStream[Has[DurableLog], IOException, Chunk[Byte]] =
    ZStream.serviceWithStream(_.subscribe(topic, position))

  val live: ZLayer[Has[IndexedStore], Nothing, Has[DurableLog]] =
    ZLayer.fromManaged {
      for {
        indexedStore <- ZManaged.service[IndexedStore]
        topics       <- Ref.makeManaged[Map[String, Topic]](Map.empty)
        durableLog    = DurableLogLive(topics, indexedStore)
      } yield durableLog
    }

  private final case class DurableLogLive(
    topics: Ref[Map[String, Topic]],
    indexedStore: IndexedStore
  ) extends DurableLog {
    def append(topic: String, value: Chunk[Byte]): IO[IOException, Long] =
      getTopic(topic).flatMap { case Topic(hub, semaphore) =>
        semaphore.withPermit {
          indexedStore.put(topic, value).flatMap { position =>
            hub.publish(value -> position) *>
              ZIO.succeedNow(position)
          }
        }
      }
    def subscribe(topic: String, position: Long): ZStream[Any, IOException, Chunk[Byte]] =
      ZStream.unwrapManaged {
        getTopic(topic).toManaged_.flatMap { case Topic(hub, _) =>
          hub.subscribe.flatMap { subscription =>
            subscription.size.toManaged_.flatMap { size =>
              if (size > 0)
                subscription.take.toManaged_.map { case (value, index) =>
                  indexedStore.scan(topic, position, index) ++
                    ZStream(value) ++
                    ZStream.fromQueue(subscription).map(_._1)
                }
              else
                indexedStore.position(topic).toManaged_.map { currentPosition =>
                  indexedStore.scan(topic, position, currentPosition) ++
                    collectFrom(ZStream.fromQueue(subscription), currentPosition)
                }
            }
          }
        }
      }

    private def collectFrom(
      stream: ZStream[Any, IOException, (Chunk[Byte], Long)],
      position: Long
    ): ZStream[Any, IOException, Chunk[Byte]] =
      stream.collect { case (value, index) if index >= position => value }

    private def getTopic(topic: String): UIO[Topic] =
      topics.modify { topics =>
        topics.get(topic) match {
          case Some(Topic(hub, semaphore)) =>
            Topic(hub, semaphore) -> topics
          case None =>
            val hub       = unsafeMakeHub[(Chunk[Byte], Long)]()
            val semaphore = unsafeMakeSemaphore(1)
            Topic(hub, semaphore) -> topics.updated(topic, Topic(hub, semaphore))
        }
      }
  }

  private final case class Topic(hub: Hub[(Chunk[Byte], Long)], semaphore: Semaphore)

  private object Topic {
    def unsafeMake(): Topic =
      Topic(unsafeMakeHub[(Chunk[Byte], Long)](), unsafeMakeSemaphore(1))
  }

  private def unsafeMakeHub[A](): Hub[A] =
    Runtime.default.unsafeRun(Hub.unbounded[A])

  private def unsafeMakeSemaphore(permits: Long): Semaphore =
    Runtime.default.unsafeRun(Semaphore.make(permits))
}

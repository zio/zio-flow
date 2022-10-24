package zio.flow.runtime.internal

import zio.flow.runtime.KeyValueStore
import zio.flow.runtime.test.KeyValueStoreTests
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault}
import zio.{Scope, ZIO}

object InMemoryKeyValueStoreSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    KeyValueStoreTests("InMemoryKeyValueStore", ZIO.unit).tests
      .provideSomeLayer[TestEnvironment](KeyValueStore.inMemory)
}

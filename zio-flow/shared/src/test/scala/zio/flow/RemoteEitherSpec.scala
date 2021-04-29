package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteEitherSpec extends DefaultRunnableSpec {
  def spec = suite("RemoteEitherSpec")(
    testM("handleEither") {
      check(Gen.either(Gen.anyInt, Gen.boolean)) { either =>
        val expected = either.fold(_ * 2, if (_) 10 else 20)
        val result   = Remote(either).handleEither(_ * 2, _.ifThenElse(10, 20))
        result <-> expected
      }
    },
    testM("merge") {
      check(Gen.either(Gen.anyInt, Gen.anyInt)) { either =>
        Remote(either).merge <-> either.fold(identity, identity)
      }
    },
    testM("isRight") {
      check(Gen.either(Gen.anyInt, Gen.boolean)) { either =>
        Remote(either).isRight <-> either.isRight
      }
    },
    testM("handleEither") {
      check(Gen.either(Gen.anyInt, Gen.boolean)) { either =>
        Remote(either).isLeft <-> either.isLeft
      }
    }
  ) @@ TestAspect.ignore
}

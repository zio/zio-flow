package zio.flow.remote

import zio.{Scope, ZLayer}
import zio.flow._
import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test.{Spec, TestEnvironment}

object RemoteRegexSyntaxSpec extends RemoteSpecBase {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RemoteRegexSyntax")(
      remoteTest("findFirstIn")(
        Remote("[a-c]{3}".r).findFirstIn("d123ab45ccbd") <-> Some("ccb"),
        Remote("[a-c]{3}".r).findFirstIn("d123ab45cdbd") <-> None
      ),
      remoteTest("findMatches")(
        Remote("x([0-9]+)y([0-9]+)z".r).findMatches("x100y32z") <-> Some(List("100", "32")),
        Remote("x([0-9]+)y([0-9]+)z".r).findMatches("100y32z") <-> None
      ),
      remoteTest("matches")(
        Remote("[a-c]{3}".r).matches("bca") <-> true,
        Remote("[a-c]{3}".r).matches("def") <-> false
      ),
      remoteTest("replaceAllIn")(
        Remote("x[0-9]+".r).replaceAllIn("xc, x1, x111, y", "***") <-> "xc, ***, ***, y"
      ),
      remoteTest("replaceFirstIn")(
        Remote("x[0-9]+".r).replaceFirstIn("xc, x1, x111, y", "***") <-> "xc, ***, x111, y"
      ),
      remoteTest("split")(
        Remote("[,.;]".r).split("hello;world,1,2.3:4,5") <-> List("hello", "world", "1", "2", "3:4", "5")
      ),
      remoteTest("regex")(
        Remote("[a-b]*".r).regex <-> "[a-b]*"
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
}

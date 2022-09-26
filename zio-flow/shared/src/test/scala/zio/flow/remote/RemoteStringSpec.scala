package zio.flow.remote

import zio.ZLayer
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow._
import zio.test.{Spec, TestEnvironment}

object RemoteStringSpec extends RemoteSpecBase {
  val suite: Spec[TestEnvironment, Nothing] =
    suite("RemoteStringSpec")(
      test("asString") {
        val remoteString = Remote(List('a', 'b', 'c'))
        val lst          = remoteString.asString.toList.asString
        lst <-> "abc"
      },
      test("toList") {
        val remoteString = Remote("abcd")
        val lst          = remoteString.toList
        lst <-> List('a', 'b', 'c', 'd')
      },
      test("length") {
        val remoteString = Remote("abcdefghcdxyz")
        val ln           = remoteString.length
        ln <-> 13
      },
      test("charAt") {
        val remoteString = Remote("abcd")
        val remoteInt    = Remote(3)
        val ch           = remoteString.charAt(remoteInt)
        ch <-> Some('d')
      },
      test("substring") {
        val remoteString = Remote("abcdefghcdxyz")
        val remoteBegin  = Remote(3)
        val remoteEnd    = Remote(7)
        val ss           = remoteString.substring(remoteBegin, remoteEnd)
        ss <-> Some("defg")
      },
      test("isEmpty") {
        val remoteString = Remote("abcdefghcdxyz")
        val ie           = remoteString.isEmpty
        ie <-> false
      },
      test("each") {
        val remoteString = Remote("abc")
        val h            = remoteString.each(_ => "abc")
        h <-> "abcabcabc"
      },
      // java.lang.IllegalStateException: Cannot find schema for index 1 in tuple schema
      // test("Zip") {
      //   val remoteString1 = Remote("abcdefghcd")
      //   val remoteString2 = Remote("0123456789")
      //   val z = remoteString1 zip remoteString2
      //   z <-> List(('a', '0'))
      // },
      test("reverse") {
        val remoteString = Remote("abc")
        val h            = remoteString.reverse
        h <-> "cba"
      }
      // java.lang.StackOverflowError
      // takeWhile calls itself forever
      // test("takeWhile") {
      //   val remoteString = Remote("abc")
      //   val i = remoteString.takeWhile(_ => false)
      //   i <-> ""
      // },
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

  override def spec = suite("RemoteStringSpec")(suite)
}

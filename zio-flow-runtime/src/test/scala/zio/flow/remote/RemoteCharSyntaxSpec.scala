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

package zio.flow.remote

import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{LocalContext, Remote, RemoteChar}
import zio.test.{Gen, Spec, TestEnvironment, check}
import zio.{Scope, ZLayer}

object RemoteCharSyntaxSpec extends RemoteSpecBase {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RemoteCharSyntax")(
      test("asDigit")(
        check(Gen.char) { ch =>
          Remote(ch).asDigit <-> ch.asDigit
        }
      ),
      test("getDirectionality")(
        check(Gen.char) { ch =>
          Remote(ch).getDirectionality <-> ch.getDirectionality.toInt
        }
      ),
      test("getNumericValue")(
        check(Gen.char) { ch =>
          Remote(ch).getNumericValue <-> ch.getNumericValue
        }
      ),
      test("getType")(
        check(Gen.char) { ch =>
          Remote(ch).getType <-> ch.getType
        }
      ),
      test("isControl")(
        check(Gen.char) { ch =>
          Remote(ch).isControl <-> ch.isControl
        }
      ),
      test("isDigit")(
        check(Gen.char) { ch =>
          Remote(ch).isDigit <-> ch.isDigit
        }
      ),
      test("isHighSurrogate")(
        check(Gen.char) { ch =>
          Remote(ch).isHighSurrogate <-> ch.isHighSurrogate
        }
      ),
      test("isIdentifierIgnorable")(
        check(Gen.char) { ch =>
          Remote(ch).isIdentifierIgnorable <-> ch.isIdentifierIgnorable
        }
      ),
      test("isLetter")(
        check(Gen.char) { ch =>
          Remote(ch).isLetter <-> ch.isLetter
        }
      ),
      test("isLetterOrDigit")(
        check(Gen.char) { ch =>
          Remote(ch).isLetterOrDigit <-> ch.isLetterOrDigit
        }
      ),
      test("isLowSurrogate")(
        check(Gen.char) { ch =>
          Remote(ch).isLowSurrogate <-> ch.isLowSurrogate
        }
      ),
      test("isLower")(
        check(Gen.char) { ch =>
          Remote(ch).isLower <-> ch.isLower
        }
      ),
      test("isMirrored")(
        check(Gen.char) { ch =>
          Remote(ch).isMirrored <-> ch.isMirrored
        }
      ),
      test("isSpaceChar")(
        check(Gen.char) { ch =>
          Remote(ch).isSpaceChar <-> ch.isSpaceChar
        }
      ),
      test("isSurrogate")(
        check(Gen.char) { ch =>
          Remote(ch).isSurrogate <-> ch.isSurrogate
        }
      ),
      test("isTitleCase")(
        check(Gen.char) { ch =>
          Remote(ch).isTitleCase <-> ch.isTitleCase
        }
      ),
      test("isUnicodeIdentifierPart")(
        check(Gen.char) { ch =>
          Remote(ch).isUnicodeIdentifierPart <-> ch.isUnicodeIdentifierPart
        }
      ),
      test("isUnicodeIdentifierStart")(
        check(Gen.char) { ch =>
          Remote(ch).isUnicodeIdentifierStart <-> ch.isUnicodeIdentifierStart
        }
      ),
      test("isUpper")(
        check(Gen.char) { ch =>
          Remote(ch).isUpper <-> ch.isUpper
        }
      ),
      test("isWhitespace")(
        check(Gen.char) { ch =>
          Remote(ch).isWhitespace <-> ch.isWhitespace
        }
      ),
      test("reverseBytes")(
        check(Gen.char) { ch =>
          Remote(ch).reverseBytes <-> ch.reverseBytes
        }
      ),
      test("toLower")(
        check(Gen.char) { ch =>
          Remote(ch).toLower <-> ch.toLower
        }
      ),
      test("toTitleCase")(
        check(Gen.char) { ch =>
          Remote(ch).toTitleCase <-> ch.toTitleCase
        }
      ),
      test("toUpper")(
        check(Gen.char) { ch =>
          Remote(ch).toUpper <-> ch.toUpper
        }
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
}

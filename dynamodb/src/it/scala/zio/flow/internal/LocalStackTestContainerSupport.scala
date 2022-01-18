package zio.flow.internal

import com.dimafeng.testcontainers.LocalStackV2Container
import zio.ZIO.attemptBlocking
import zio.{ULayer, ZManaged}

/**
 * A helper module for test-containers integration. This facilitates spinning up
 * and down a LocalStack container.
 */
object LocalStackTestContainerSupport {

  val DockerImageTag: String = "0.13.3"

  def awsContainer(
    imageTag: String = DockerImageTag,
    awsServices: Seq[LocalStackV2Container.Service]
  ): ULayer[LocalStackV2Container] =
    ZManaged.acquireReleaseWith {
      attemptBlocking {
        val awsContainer = LocalStackV2Container(imageTag, awsServices)
        awsContainer.start()
        awsContainer
      }.orDie
    } { awsContainer =>
      attemptBlocking(
        awsContainer.stop()
      ).orDie
    }.toLayer
}

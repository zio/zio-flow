package zio.flow.dynamodb

import DynamoDbKeyValueStore.{keyColumnName, timestampName}
import LocalStackTestContainerSupport.awsContainer
import com.dimafeng.testcontainers.LocalStackV2Container
import zio.aws.core.config.AwsConfig
import zio.aws.dynamodb.DynamoDb
import zio.aws.dynamodb.model._
import org.testcontainers.containers.localstack.LocalStackContainer.{Service => AwsService}
import zio.aws.dynamodb.model.primitives.{KeySchemaAttributeName, PositiveLongObject, TableName}
import zio.aws.netty.NettyHttpClient
import zio.{&, RLayer, Scope, ULayer, ZIO, ZLayer}

/**
 * A helper module for LocalStack DynamoDB integration. It contains helper
 * methods to create/delete a DynamoDB table, construct ZLayers, etc.
 */
object DynamoDbSupport {

  type DynamoDB = ULayer[DynamoDb]

  lazy val dynamoDb: RLayer[AwsConfig & LocalStackV2Container, DynamoDb] =
    ZLayer.scoped {
      for {
        awsContainer <- ZIO.service[LocalStackV2Container]
        dynamoDbService <-
          DynamoDb.scoped(
            _.credentialsProvider(
              awsContainer.staticCredentialsProvider
            )
              .region(awsContainer.region)
              .endpointOverride(
                awsContainer.endpointOverride(AwsService.DYNAMODB)
              )
          )
      } yield dynamoDbService
    }

  lazy val dynamoDbLayer: DynamoDB =
    awsLayerFor(Seq(AwsService.DYNAMODB))

  def createDynamoDbTable(name: TableName): ZIO[DynamoDb with Scope, Throwable, TableDescription.ReadOnly] = {
    val createTableRequest =
      CreateTableRequest(
        tableName = name,
        attributeDefinitions = Seq(
          AttributeDefinition(
            KeySchemaAttributeName(keyColumnName),
            ScalarAttributeType.B
          ),
          AttributeDefinition(
            KeySchemaAttributeName(timestampName),
            ScalarAttributeType.N
          )
        ),
        keySchema = List(
          KeySchemaElement(
            KeySchemaAttributeName(keyColumnName),
            KeyType.HASH
          ),
          KeySchemaElement(
            KeySchemaAttributeName(timestampName),
            KeyType.RANGE
          )
        ),
        provisionedThroughput = Option(
          ProvisionedThroughput(
            readCapacityUnits = PositiveLongObject(16L),
            writeCapacityUnits = PositiveLongObject(16L)
          )
        )
      )

    ZIO
      .acquireRelease(
        for {
          tableData <- DynamoDb.createTable(createTableRequest)
          tableDesc <- tableData.getTableDescription
        } yield tableDesc
      )(
        _.getTableName.flatMap { tableName =>
          DynamoDb
            .deleteTable(
              DeleteTableRequest(tableName)
            )
            .unit
        }
          .catchAll(error => ZIO.die(error.toThrowable))
          .unit
      )
      .mapError(_.toThrowable)
  }

  private def awsLayerFor(
    awsServices: Seq[LocalStackV2Container.Service]
  ): DynamoDB = (
    (
      (NettyHttpClient.default >>> AwsConfig.default) ++
        awsContainer(awsServices = awsServices)
    ) >>> dynamoDb
  ).orDie
}

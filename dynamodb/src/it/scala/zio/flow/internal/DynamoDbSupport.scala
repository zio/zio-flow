package zio.flow.internal

import DynamoDbKeyValueStore.{keyColumnName, namespaceColumnName}
import LocalStackTestContainerSupport.awsContainer
import com.dimafeng.testcontainers.LocalStackV2Container
import zio.aws.core.config.AwsConfig
import zio.aws.dynamodb.DynamoDb
import zio.aws.dynamodb.model._
import org.testcontainers.containers.localstack.LocalStackContainer.{Service => AwsService}
import zio.aws.dynamodb.model.primitives.{KeySchemaAttributeName, PositiveLongObject, TableName}
import zio.aws.netty.NettyHttpClient
import zio.{&, RLayer, RManaged, ULayer, ZIO, ZManaged}

object DynamoDbSupport {

  type DynamoDB = ULayer[DynamoDb]

  lazy val dynamoDb: RLayer[AwsConfig & LocalStackV2Container, DynamoDb] = (
    for {
      awsContainer <- ZIO.service[LocalStackV2Container].toManaged
      dynamoDbService <-
        DynamoDb.managed(
          _.credentialsProvider(
            awsContainer.staticCredentialsProvider
          )
            .region(awsContainer.region)
            .endpointOverride(
              awsContainer.endpointOverride(AwsService.DYNAMODB)
            )
        )
    } yield dynamoDbService
  ).toLayer

  lazy val dynamoDbLayer: DynamoDB =
    awsLayerFor(Seq(AwsService.DYNAMODB))

  def createDynamoDbTable(name: String): RManaged[DynamoDb, TableDescription.ReadOnly] = {
    val createTableRequest =
      CreateTableRequest(
        tableName = TableName(name),
        attributeDefinitions = Seq(
          AttributeDefinition(
            KeySchemaAttributeName(namespaceColumnName),
            ScalarAttributeType.S
          ),
          AttributeDefinition(
            KeySchemaAttributeName(keyColumnName),
            ScalarAttributeType.B
          )
        ),
        keySchema = List(
          KeySchemaElement(
            KeySchemaAttributeName(namespaceColumnName),
            KeyType.HASH
          ),
          KeySchemaElement(
            KeySchemaAttributeName(keyColumnName),
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

    ZManaged
      .acquireReleaseWith(
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

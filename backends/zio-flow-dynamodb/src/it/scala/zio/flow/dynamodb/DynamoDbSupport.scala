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

package zio.flow.dynamodb

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

  def createKeyValueStoreTable(name: TableName): ZIO[DynamoDb with Scope, Throwable, TableDescription.ReadOnly] = {
    import DynamoDbKeyValueStore._

    val createTableRequest =
      CreateTableRequest(
        tableName = name,
        attributeDefinitions = Seq(
          AttributeDefinition(
            KeySchemaAttributeName(namespaceColumnName),
            ScalarAttributeType.S
          ),
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
        globalSecondaryIndexes = List(
          GlobalSecondaryIndex(
            primitives.IndexName("namespace_index"),
            keySchema = List(
              KeySchemaElement(
                KeySchemaAttributeName(namespaceColumnName),
                KeyType.HASH
              )
            ),
            Projection(
              projectionType = Option(ProjectionType.INCLUDE),
              nonKeyAttributes = Some(List(primitives.NonKeyAttributeName(valueColumnName)))
            ),
            provisionedThroughput = Option(
              ProvisionedThroughput(
                readCapacityUnits = PositiveLongObject(16L),
                writeCapacityUnits = PositiveLongObject(16L)
              )
            )
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

  def createIndexedStoreTable(name: TableName): ZIO[DynamoDb with Scope, Throwable, TableDescription.ReadOnly] = {
    import DynamoDbIndexedStore._

    val createTableRequest =
      CreateTableRequest(
        tableName = name,
        attributeDefinitions = Seq(
          AttributeDefinition(
            KeySchemaAttributeName(topicColumnName),
            ScalarAttributeType.S
          ),
          AttributeDefinition(
            KeySchemaAttributeName(indexColumnName),
            ScalarAttributeType.N
          )
        ),
        keySchema = List(
          KeySchemaElement(
            KeySchemaAttributeName(topicColumnName),
            KeyType.HASH
          ),
          KeySchemaElement(
            KeySchemaAttributeName(indexColumnName),
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

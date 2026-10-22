package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end coverage for the test-isolation clone/clear admin API: clone a baseline account's
 * data into a fresh throwaway account, then wipe it, across every service required for the
 * feature (dynamodb, s3, sqs, sns, ssm), plus request validation.
 */
@QuarkusTest
class AccountLifecycleControllerTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    private static final String SOURCE_ACCOUNT = "000000000001";
    private static final String TARGET_ACCOUNT = "000000000002";

    private static String auth(String service, String accountId) {
        return "AWS4-HMAC-SHA256 Credential=%s/20260215/us-east-1/%s/aws4_request, SignedHeaders=host, Signature=abc"
                .formatted(accountId, service);
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void cloneCopiesDynamoDbItemsAndRewritesTableArnToTargetAccount() {
        String tableName = "clone-lifecycle-table";
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .header("Authorization", auth("dynamodb", SOURCE_ACCOUNT))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s", "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                 "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                 "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5}}
                """.formatted(tableName))
        .when().post("/").then().statusCode(200);

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.PutItem")
            .header("Authorization", auth("dynamodb", SOURCE_ACCOUNT))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s", "Item": {"pk": {"S": "item-1"}, "value": {"S": "cloned-data"}}}
                """.formatted(tableName))
        .when().post("/").then().statusCode(200);

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "dynamodb");

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .header("Authorization", auth("dynamodb", TARGET_ACCOUNT))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s", "Key": {"pk": {"S": "item-1"}}}
                """.formatted(tableName))
        .when().post("/").then()
            .statusCode(200)
            .body("Item.value.S", equalTo("cloned-data"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .header("Authorization", auth("dynamodb", TARGET_ACCOUNT))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/").then()
            .statusCode(200)
            .body("Table.TableArn", containsString(":" + TARGET_ACCOUNT + ":"))
            .body("Table.TableArn", not(containsString(":" + SOURCE_ACCOUNT + ":")));

        clear(TARGET_ACCOUNT, "dynamodb");

        // clear wipes the whole "dynamodb" service for the account, including the table
        // definition itself, not just its items.
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .header("Authorization", auth("dynamodb", TARGET_ACCOUNT))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s", "Key": {"pk": {"S": "item-1"}}}
                """.formatted(tableName))
        .when().post("/").then()
            .statusCode(400);

        // Clearing the target must never affect the source.
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .header("Authorization", auth("dynamodb", SOURCE_ACCOUNT))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s", "Key": {"pk": {"S": "item-1"}}}
                """.formatted(tableName))
        .when().post("/").then()
            .statusCode(200)
            .body("Item.value.S", equalTo("cloned-data"));
    }

    @Test
    void cloneWorksForATableWithASortKeyAndAGsiWithASortKey() {
        // The clone's Jackson copy must leave the derived getSortKeyNames() lists alone; they are
        // only non-empty when the table or an index has a RANGE key.
        String tableName = "clone-lifecycle-sort-key-table";
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .header("Authorization", auth("dynamodb", SOURCE_ACCOUNT))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s",
                 "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "sk", "KeyType": "RANGE"}],
                 "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}, {"AttributeName": "sk", "AttributeType": "S"},
                                           {"AttributeName": "gsiSk", "AttributeType": "S"}],
                 "GlobalSecondaryIndexes": [{"IndexName": "gsi1",
                     "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "gsiSk", "KeyType": "RANGE"}],
                     "Projection": {"ProjectionType": "ALL"},
                     "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5}}],
                 "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5}}
                """.formatted(tableName))
        .when().post("/").then().statusCode(200);

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "dynamodb");

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .header("Authorization", auth("dynamodb", TARGET_ACCOUNT))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "%s"}
                """.formatted(tableName))
        .when().post("/").then()
            .statusCode(200)
            .body("Table.TableArn", containsString(":" + TARGET_ACCOUNT + ":"));
    }

    @Test
    void cloneRekeysDynamoDbBackupsUnderTheTargetAccountArn() {
        String tableName = "clone-lifecycle-backup-table";
        ddb(SOURCE_ACCOUNT, "CreateTable", simpleTableBody(tableName)).statusCode(200);
        String sourceBackupArn = ddb(SOURCE_ACCOUNT, "CreateBackup", """
                {"TableName": "%s", "BackupName": "baseline"}
                """.formatted(tableName))
            .statusCode(200)
            .extract().path("BackupDetails.BackupArn");

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "dynamodb");

        String targetBackupArn = sourceBackupArn.replace(":" + SOURCE_ACCOUNT + ":", ":" + TARGET_ACCOUNT + ":");
        ddb(TARGET_ACCOUNT, "ListBackups", "{\"TableName\": \"%s\"}".formatted(tableName))
            .statusCode(200)
            .body("BackupSummaries.BackupArn", equalTo(List.of(targetBackupArn)));
        ddb(TARGET_ACCOUNT, "DescribeBackup", "{\"BackupArn\": \"%s\"}".formatted(targetBackupArn))
            .statusCode(200)
            .body("BackupDescription.BackupDetails.BackupArn", equalTo(targetBackupArn))
            .body("BackupDescription.SourceTableDetails.TableArn", containsString(":" + TARGET_ACCOUNT + ":"));

        clear(TARGET_ACCOUNT, "dynamodb");

        ddb(TARGET_ACCOUNT, "ListBackups", "{\"TableName\": \"%s\"}".formatted(tableName))
            .statusCode(200)
            .body("BackupSummaries", empty());
        ddb(SOURCE_ACCOUNT, "DescribeBackup", "{\"BackupArn\": \"%s\"}".formatted(sourceBackupArn))
            .statusCode(200);
    }

    @Test
    void cloneCarriesDynamoDbPointInTimeHistoryIntoTheTargetAccount() {
        String tableName = "clone-lifecycle-pitr-table";
        ddb(SOURCE_ACCOUNT, "CreateTable", simpleTableBody(tableName)).statusCode(200);
        ddb(SOURCE_ACCOUNT, "UpdateContinuousBackups", """
                {"TableName": "%s", "PointInTimeRecoverySpecification": {"PointInTimeRecoveryEnabled": true}}
                """.formatted(tableName)).statusCode(200);
        ddb(SOURCE_ACCOUNT, "PutItem", """
                {"TableName": "%s", "Item": {"pk": {"S": "k1"}, "v": {"S": "before"}}}
                """.formatted(tableName)).statusCode(200);
        BigDecimal beforeUpdate = pause();
        ddb(SOURCE_ACCOUNT, "PutItem", """
                {"TableName": "%s", "Item": {"pk": {"S": "k1"}, "v": {"S": "after"}}}
                """.formatted(tableName)).statusCode(200);
        pause();

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "dynamodb");

        String restored = tableName + "-restored";
        ddb(TARGET_ACCOUNT, "RestoreTableToPointInTime", """
                {"SourceTableName": "%s", "TargetTableName": "%s", "RestoreDateTime": %s}
                """.formatted(tableName, restored, beforeUpdate.toPlainString())).statusCode(200);
        ddb(TARGET_ACCOUNT, "GetItem", """
                {"TableName": "%s", "Key": {"pk": {"S": "k1"}}}
                """.formatted(restored))
            .statusCode(200)
            .body("Item.v.S", equalTo("before"));

        clear(TARGET_ACCOUNT, "dynamodb");
    }

    @Test
    void cloneCarriesSqsFifoDeduplicationIntoTheTargetAccount() {
        String queueName = "clone-lifecycle-dedup.fifo";
        given()
            .header("Authorization", auth("sqs", SOURCE_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
            .formParam("Attribute.1.Name", "FifoQueue")
            .formParam("Attribute.1.Value", "true")
        .when().post("/").then().statusCode(200);
        String sourceMessageId = sendFifo(SOURCE_ACCOUNT, queueName, "first");

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "sqs");

        assertEquals(sourceMessageId, sendFifo(TARGET_ACCOUNT, queueName, "duplicate"),
                "a send inside the cloned deduplication window must be reported as the original message");
        given()
            .header("Authorization", auth("sqs", TARGET_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", "http://localhost:8081/" + TARGET_ACCOUNT + "/" + queueName)
            .formParam("AttributeName.1", "ApproximateNumberOfMessages")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<Value>1</Value>"));

        clear(TARGET_ACCOUNT, "sqs");
    }

    @Test
    void cloneCopiesS3ObjectAnnotations() {
        String bucket = "clone-lifecycle-annotations";
        given().header("Authorization", auth("s3", SOURCE_ACCOUNT))
            .when().put("/" + bucket).then().statusCode(200);
        given().header("Authorization", auth("s3", SOURCE_ACCOUNT)).body("object-body")
            .when().put("/" + bucket + "/key.txt").then().statusCode(200);
        given().header("Authorization", auth("s3", SOURCE_ACCOUNT)).body("cloned-annotation")
            .when().put("/" + bucket + "/key.txt?annotation&annotationName=labels").then().statusCode(200);

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "s3");

        given().header("Authorization", auth("s3", TARGET_ACCOUNT))
            .when().get("/" + bucket + "/key.txt?annotation&annotationName=labels").then()
            .statusCode(200)
            .body(equalTo("cloned-annotation"));

        clear(TARGET_ACCOUNT, "s3");
    }

    @Test
    void clearAbortsTheAccountsS3MultipartUploads() {
        String bucket = "clear-lifecycle-multipart";
        given().header("Authorization", auth("s3", TARGET_ACCOUNT))
            .when().put("/" + bucket).then().statusCode(200);
        given().header("Authorization", auth("s3", TARGET_ACCOUNT))
            .when().post("/" + bucket + "/upload.bin?uploads").then()
            .statusCode(200)
            .body(containsString("<UploadId>"));

        clear(TARGET_ACCOUNT, "s3");

        given().header("Authorization", auth("s3", TARGET_ACCOUNT))
            .when().put("/" + bucket).then().statusCode(200);
        given().header("Authorization", auth("s3", TARGET_ACCOUNT))
            .when().get("/" + bucket + "?uploads").then()
            .statusCode(200)
            .body(not(containsString("<Upload>")));

        clear(TARGET_ACCOUNT, "s3");
    }

    @Test
    void cloneCopiesS3ObjectBytes() {
        String bucket = "clone-lifecycle-bucket";
        given().header("Authorization", auth("s3", SOURCE_ACCOUNT))
            .when().put("/" + bucket).then().statusCode(200);
        given().header("Authorization", auth("s3", SOURCE_ACCOUNT)).body("cloned-object-data")
            .when().put("/" + bucket + "/key.txt").then().statusCode(200);

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "s3");

        given().header("Authorization", auth("s3", TARGET_ACCOUNT))
            .when().put("/" + bucket).then().statusCode(200);
        given().header("Authorization", auth("s3", TARGET_ACCOUNT))
            .when().get("/" + bucket + "/key.txt").then()
            .statusCode(200).body(containsString("cloned-object-data"));

        clear(TARGET_ACCOUNT, "s3");

        given().header("Authorization", auth("s3", TARGET_ACCOUNT))
            .when().get("/" + bucket + "/key.txt").then().statusCode(404);
    }

    @Test
    void cloneCopiesSqsMessagesAndRewritesQueueArnToTargetAccount() {
        String queueName = "clone-lifecycle-queue";
        given()
            .header("Authorization", auth("sqs", SOURCE_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
        .when().post("/").then().statusCode(200);

        given()
            .header("Authorization", auth("sqs", SOURCE_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:8081/" + SOURCE_ACCOUNT + "/" + queueName)
            .formParam("MessageBody", "cloned-message")
        .when().post("/").then().statusCode(200);

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "sqs");

        given()
            .header("Authorization", auth("sqs", TARGET_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", "http://localhost:8081/" + TARGET_ACCOUNT + "/" + queueName)
            .formParam("AttributeName.1", "QueueArn")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("arn:aws:sqs:us-east-1:" + TARGET_ACCOUNT + ":" + queueName));

        given()
            .header("Authorization", auth("sqs", TARGET_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", "http://localhost:8081/" + TARGET_ACCOUNT + "/" + queueName)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("WaitTimeSeconds", "0")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("cloned-message"));

        clear(TARGET_ACCOUNT, "sqs");

        given()
            .header("Authorization", auth("sqs", TARGET_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", "http://localhost:8081/" + TARGET_ACCOUNT + "/" + queueName)
            .formParam("AttributeName.1", "QueueArn")
        .when().post("/").then()
            // The queue itself is gone after clear, so the request now fails against a nonexistent queue.
            .statusCode(400);
    }

    @Test
    void cloneCopiesSnsTopicAndRewritesTopicArnToTargetAccount() {
        String topicName = "clone-lifecycle-topic";
        String sourceTopicArn = given()
            .header("Authorization", auth("sns", SOURCE_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", topicName)
        .when().post("/").then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "sns");

        String targetTopicArn = sourceTopicArn.replace(":" + SOURCE_ACCOUNT + ":", ":" + TARGET_ACCOUNT + ":");
        given()
            .header("Authorization", auth("sns", TARGET_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTopicAttributes")
            .formParam("TopicArn", targetTopicArn)
        .when().post("/").then()
            .statusCode(200);

        clear(TARGET_ACCOUNT, "sns");

        given()
            .header("Authorization", auth("sns", TARGET_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTopicAttributes")
            .formParam("TopicArn", targetTopicArn)
        .when().post("/").then()
            .statusCode(404);
    }

    @Test
    void cloneCopiesSsmParameterAndRewritesArnToTargetAccount() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .header("Authorization", auth("ssm", SOURCE_ACCOUNT))
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/clone-lifecycle/key", "Value": "cloned-value", "Type": "String"}
                """)
        .when().post("/").then().statusCode(200);

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "ssm");

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", auth("ssm", TARGET_ACCOUNT))
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/clone-lifecycle/key"}
                """)
        .when().post("/").then()
            .statusCode(200)
            .body("Parameter.Value", equalTo("cloned-value"))
            .body("Parameter.ARN", containsString(TARGET_ACCOUNT))
            .body("Parameter.ARN", not(containsString(SOURCE_ACCOUNT)));

        clear(TARGET_ACCOUNT, "ssm");

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", auth("ssm", TARGET_ACCOUNT))
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/clone-lifecycle/key"}
                """)
        .when().post("/").then().statusCode(400);
    }

    @Test
    void cloneRewritesTheArnOfAnSsmParameterWithoutALeadingSlash() {
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .header("Authorization", auth("ssm", SOURCE_ACCOUNT))
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "clone-lifecycle-plain", "Value": "cloned-value", "Type": "String"}
                """)
        .when().post("/").then().statusCode(200);

        clone(TARGET_ACCOUNT, SOURCE_ACCOUNT, "ssm");

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", auth("ssm", TARGET_ACCOUNT))
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "clone-lifecycle-plain"}
                """)
        .when().post("/").then()
            .statusCode(200)
            .body("Parameter.ARN", endsWith(":" + TARGET_ACCOUNT + ":parameter/clone-lifecycle-plain"));

        clear(TARGET_ACCOUNT, "ssm");
    }

    @Test
    void cloneRejectsAnEmptyServicesListWithoutTouchingAnything() {
        given()
            .contentType("application/json")
            .body("""
                {"source": "%s", "services": []}
                """.formatted(SOURCE_ACCOUNT))
        .when().post("/_floci/accounts/" + TARGET_ACCOUNT + "/clone")
        .then().statusCode(400);
    }

    @Test
    void cloneRejectsAnUnknownServiceNameAtomically() {
        given()
            .contentType("application/json")
            .body("""
                {"source": "%s", "services": ["dynamodb", "not-a-real-service"]}
                """.formatted(SOURCE_ACCOUNT))
        .when().post("/_floci/accounts/" + TARGET_ACCOUNT + "/clone")
        .then().statusCode(400);
    }

    @Test
    void cloneRejectsTheSourceAsItsOwnTargetWithoutTouchingIt() {
        String tableName = "self-clone-table";
        ddb(SOURCE_ACCOUNT, "CreateTable", simpleTableBody(tableName)).statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {"source": "%s", "services": ["dynamodb"]}
                """.formatted(SOURCE_ACCOUNT))
        .when().post("/_floci/accounts/" + SOURCE_ACCOUNT + "/clone")
        .then().statusCode(400);

        ddb(SOURCE_ACCOUNT, "DescribeTable", "{\"TableName\": \"%s\"}".formatted(tableName))
            .statusCode(200);
    }

    @Test
    void cloneRejectsATargetThatIsNotA12DigitAccountId() {
        for (String target : List.of("00000000002", "0000000000020", "not-an-account")) {
            given()
                .contentType("application/json")
                .body("""
                    {"source": "%s", "services": ["s3"]}
                    """.formatted(SOURCE_ACCOUNT))
            .when().post("/_floci/accounts/" + target + "/clone")
            .then().statusCode(400);
        }
    }

    @Test
    void cloneRejectsASourceThatIsNotA12DigitAccountId() {
        for (String source : List.of("../" + SOURCE_ACCOUNT, "00000000001", SOURCE_ACCOUNT + "/..")) {
            given()
                .contentType("application/json")
                .body("""
                    {"source": "%s", "services": ["s3"]}
                    """.formatted(source))
            .when().post("/_floci/accounts/" + TARGET_ACCOUNT + "/clone")
            .then().statusCode(400);
        }
    }

    @Test
    void clearRejectsAnAccountThatIsNotA12DigitAccountId() {
        for (String target : List.of("00000000002", "not-an-account")) {
            given()
                .contentType("application/json")
                .body("""
                    {"services": ["s3"]}
                    """)
            .when().post("/_floci/accounts/" + target + "/clear")
            .then().statusCode(400);
        }
    }

    @Test
    void cloneRejectsAStorageBackedServiceWithoutItsOwnCloneStepAtomically() {
        // IAM keeps its state behind StorageFactory; using it first makes sure its stores exist.
        given()
            .header("Authorization", auth("iam", SOURCE_ACCOUNT))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListUsers")
        .when().post("/").then().statusCode(200);
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .header("Authorization", auth("ssm", SOURCE_ACCOUNT))
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/clone-lifecycle/rejected-with-iam", "Value": "v", "Type": "String"}
                """)
        .when().post("/").then().statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {"source": "%s", "services": ["ssm", "iam"]}
                """.formatted(SOURCE_ACCOUNT))
        .when().post("/_floci/accounts/" + TARGET_ACCOUNT + "/clone")
        .then().statusCode(400);
        given()
            .contentType("application/json")
            .body("""
                {"services": ["iam"]}
                """)
        .when().post("/_floci/accounts/" + TARGET_ACCOUNT + "/clear")
        .then().statusCode(400);

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .header("Authorization", auth("ssm", TARGET_ACCOUNT))
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/clone-lifecycle/rejected-with-iam"}
                """)
        .when().post("/").then().statusCode(400);
    }

    @Test
    void cloneRejectsAMissingSource() {
        given()
            .contentType("application/json")
            .body("""
                {"services": ["dynamodb"]}
                """)
        .when().post("/_floci/accounts/" + TARGET_ACCOUNT + "/clone")
        .then().statusCode(400);
    }

    @Test
    void clearRejectsAnUnknownServiceName() {
        given()
            .contentType("application/json")
            .body("""
                {"services": ["not-a-real-service"]}
                """)
        .when().post("/_floci/accounts/" + TARGET_ACCOUNT + "/clear")
        .then().statusCode(400);
    }

    private static void clone(String target, String source, String... services) {
        given()
            .contentType("application/json")
            .body("""
                {"source": "%s", "services": %s}
                """.formatted(source, jsonArray(services)))
        .when().post("/_floci/accounts/" + target + "/clone")
        .then().statusCode(200);
    }

    private static void clear(String accountId, String... services) {
        given()
            .contentType("application/json")
            .body("""
                {"services": %s}
                """.formatted(jsonArray(services)))
        .when().post("/_floci/accounts/" + accountId + "/clear")
        .then().statusCode(200);
    }

    private static ValidatableResponse ddb(String accountId, String operation, String body) {
        return given()
            .header("X-Amz-Target", "DynamoDB_20120810." + operation)
            .header("Authorization", auth("dynamodb", accountId))
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body(body)
        .when().post("/").then();
    }

    private static String simpleTableBody(String tableName) {
        return """
            {"TableName": "%s", "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
             "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
             "BillingMode": "PAY_PER_REQUEST"}
            """.formatted(tableName);
    }

    /** Returns a time between the writes before and after the call, in epoch seconds with milliseconds. */
    private static BigDecimal pause() {
        long now = System.currentTimeMillis();
        while (System.currentTimeMillis() <= now) {
            Thread.onSpinWait();
        }
        return BigDecimal.valueOf(now, 3);
    }

    private static String sendFifo(String accountId, String queueName, String body) {
        return given()
            .header("Authorization", auth("sqs", accountId))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", "http://localhost:8081/" + accountId + "/" + queueName)
            .formParam("MessageBody", body)
            .formParam("MessageGroupId", "group")
            .formParam("MessageDeduplicationId", "dedup-1")
        .when().post("/").then()
            .statusCode(200)
            .extract().xmlPath().getString("SendMessageResponse.SendMessageResult.MessageId");
    }

    private static String jsonArray(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(values[i]).append("\"");
        }
        return sb.append("]").toString();
    }
}

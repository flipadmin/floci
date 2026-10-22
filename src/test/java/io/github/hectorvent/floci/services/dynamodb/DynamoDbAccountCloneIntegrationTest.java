package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A clone's effect on the live item maps, which no API call can show: a table the target owns and
 * the source lacks loses its definition in the clone, and every way of creating it again replaces
 * its item map, so the stale map would only ever be leaked memory.
 */
@QuarkusTest
class DynamoDbAccountCloneIntegrationTest {

    private static final String SOURCE_ACCOUNT = "000000000031";
    private static final String TARGET_ACCOUNT = "000000000032";

    @Inject
    DynamoDbService dynamoDbService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void cloneDropsTheItemMapsOfTablesOnlyTheTargetHad() throws Exception {
        ddb(SOURCE_ACCOUNT, "CreateTable", tableBody("clone-source-table"));
        ddb(TARGET_ACCOUNT, "CreateTable", tableBody("clone-target-only-table"));
        ddb(TARGET_ACCOUNT, "PutItem", """
                {"TableName": "clone-target-only-table", "Item": {"pk": {"S": "stale"}}}
                """);

        lifecycle("/_floci/accounts/" + TARGET_ACCOUNT + "/clone",
                "{\"source\": \"" + SOURCE_ACCOUNT + "\", \"services\": [\"dynamodb\"]}");

        assertEquals(Set.of(TARGET_ACCOUNT + "/us-east-1::clone-source-table"), itemMapKeys(TARGET_ACCOUNT));

        lifecycle("/_floci/accounts/" + TARGET_ACCOUNT + "/clear", "{\"services\": [\"dynamodb\"]}");
        lifecycle("/_floci/accounts/" + SOURCE_ACCOUNT + "/clear", "{\"services\": [\"dynamodb\"]}");
        assertEquals(Set.of(), itemMapKeys(TARGET_ACCOUNT));
    }

    private Set<String> itemMapKeys(String accountId) throws ReflectiveOperationException {
        DynamoDbService service = ClientProxy.unwrap(dynamoDbService);
        Field field = DynamoDbService.class.getDeclaredField("itemsByTable");
        field.setAccessible(true);
        Map<?, ?> itemsByTable = (Map<?, ?>) field.get(service);
        return itemsByTable.keySet().stream()
                .map(Object::toString)
                .filter(key -> key.startsWith(accountId + "/"))
                .collect(Collectors.toSet());
    }

    private static String tableBody(String tableName) {
        return """
            {"TableName": "%s", "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
             "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
             "BillingMode": "PAY_PER_REQUEST"}
            """.formatted(tableName);
    }

    private static void ddb(String accountId, String operation, String body) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810." + operation)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=%s/20260215/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=abc"
                    .formatted(accountId))
            .contentType("application/x-amz-json-1.0")
            .body(body)
        .when().post("/").then().statusCode(200);
    }

    private static void lifecycle(String path, String body) {
        given().contentType("application/json").body(body)
            .when().post(path).then().statusCode(200);
    }
}

package io.github.hectorvent.floci.lifecycle;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Account clone/clear with S3 object bytes kept in files, where a clone copies the source
 * account's directory over the target's.
 */
@QuarkusTest
@TestProfile(AccountLifecyclePersistentS3IntegrationTest.PersistentS3Profile.class)
class AccountLifecyclePersistentS3IntegrationTest {

    private static final String STORAGE_DIR = "target/account-lifecycle-persistent-s3-it";
    private static final String SOURCE_ACCOUNT = "000000000021";
    private static final String TARGET_ACCOUNT = "000000000022";
    private static final String BUCKET = "clone-into-non-empty-target";

    public static class PersistentS3Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.services.s3.mode", "persistent",
                    "floci.storage.persistent-path", STORAGE_DIR);
        }
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=%s/20260215/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc"
                .formatted(accountId);
    }

    private static Path objectFile(String accountId, String key) {
        return Path.of(STORAGE_DIR, "s3", ".accounts", accountId, BUCKET, key + ".s3data");
    }

    @Test
    void cloneReplacesTheTargetsObjectFilesWithTheSources() {
        putBucket(SOURCE_ACCOUNT);
        putObject(SOURCE_ACCOUNT, "shared.txt", "source-content");
        putBucket(TARGET_ACCOUNT);
        putObject(TARGET_ACCOUNT, "shared.txt", "target-content");
        putObject(TARGET_ACCOUNT, "target-only.txt", "target-only-content");
        assertTrue(Files.exists(objectFile(TARGET_ACCOUNT, "target-only.txt")));

        lifecycle("/_floci/accounts/" + TARGET_ACCOUNT + "/clone",
                "{\"source\": \"" + SOURCE_ACCOUNT + "\", \"services\": [\"s3\"]}");

        given().header("Authorization", auth(TARGET_ACCOUNT))
            .when().get("/" + BUCKET + "/shared.txt").then()
            .statusCode(200)
            .body(equalTo("source-content"));
        given().header("Authorization", auth(TARGET_ACCOUNT))
            .when().get("/" + BUCKET + "/target-only.txt").then()
            .statusCode(404);
        assertFalse(Files.exists(objectFile(TARGET_ACCOUNT, "target-only.txt")),
                "the target's own object file must not survive a clone");

        lifecycle("/_floci/accounts/" + TARGET_ACCOUNT + "/clear", "{\"services\": [\"s3\"]}");
        assertFalse(Files.exists(Path.of(STORAGE_DIR, "s3", ".accounts", TARGET_ACCOUNT)));
        lifecycle("/_floci/accounts/" + SOURCE_ACCOUNT + "/clear", "{\"services\": [\"s3\"]}");
    }

    private static void putBucket(String accountId) {
        given().header("Authorization", auth(accountId))
            .when().put("/" + BUCKET).then().statusCode(200);
    }

    private static void putObject(String accountId, String key, String body) {
        given().header("Authorization", auth(accountId)).body(body)
            .when().put("/" + BUCKET + "/" + key).then().statusCode(200);
    }

    private static void lifecycle(String path, String body) {
        given().contentType("application/json").body(body)
            .when().post(path).then().statusCode(200);
    }
}

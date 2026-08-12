package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAwareStorageBackendTest {

    @Test
    void scanAllAccountsRawAttributesLegacyUnprefixedKeysToDefaultAccount() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        // Simulates data persisted before multi-account support existed: no account
        // segment at all, unlike every key AccountAwareStorageBackend itself writes.
        raw.put("us-east-1::LegacyTable", "legacy-value");
        raw.put("111111111111/us-east-1::NewTable", "new-value");

        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");

        Map<String, String> result = aware.scanAllAccountsRaw();

        assertEquals(2, result.size());
        assertEquals("legacy-value", result.get("000000000000/us-east-1::LegacyTable"),
                "a pre-multi-account key must be attributed to the default account, not dropped");
        assertEquals("new-value", result.get("111111111111/us-east-1::NewTable"));
        assertTrue(result.keySet().stream().allMatch(k -> k.indexOf('/') >= 0),
                "every returned key must carry an account segment");
    }

    @Test
    void scanAllAccountsRawMigratesLegacyKeyIntoUnderlyingStorage() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("us-east-1::LegacyTable", "legacy-value");

        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        aware.scanAllAccountsRaw();

        assertEquals(Optional.empty(), raw.get("us-east-1::LegacyTable"),
                "the bare legacy key must not be left sitting in storage after being migrated");
        assertEquals(Optional.of("legacy-value"), raw.get("000000000000/us-east-1::LegacyTable"));
    }

    @Test
    void scanAllAccountsRawPrefersAlreadyPrefixedEntryOverStaleLegacyKeyAndDeletesTheStaleOne() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        // A legacy key already superseded by a real write under its proper prefix.
        raw.put("us-east-1::Orders", "stale-legacy-value");
        raw.put("000000000000/us-east-1::Orders", "current-value");

        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        Map<String, String> result = aware.scanAllAccountsRaw();

        assertEquals(1, result.size());
        assertEquals("current-value", result.get("000000000000/us-east-1::Orders"),
                "the already-prefixed, current entry must win over a stale legacy duplicate");
        assertEquals(Optional.empty(), raw.get("us-east-1::Orders"),
                "the superseded legacy key must be deleted, not left to collide again later");
    }

    @Test
    void cloneAccountCopiesEveryKeyUnderTheTargetAccount() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        aware.putForAccount("111111111111", "Orders", "orders-value");
        aware.putForAccount("111111111111", "Users", "users-value");
        aware.putForAccount("222222222222", "Orders", "unrelated-account-value");

        aware.cloneAccount("333333333333", "111111111111");

        assertEquals(Optional.of("orders-value"), aware.getForAccount("333333333333", "Orders"));
        assertEquals(Optional.of("users-value"), aware.getForAccount("333333333333", "Users"));
        assertEquals(Optional.of("orders-value"), aware.getForAccount("111111111111", "Orders"),
                "cloning must not mutate the source account");
        assertEquals(Optional.of("unrelated-account-value"), aware.getForAccount("222222222222", "Orders"),
                "cloning must not touch an unrelated account");
    }

    @Test
    void cloneAccountOverwritesAnyExistingTargetDataInsteadOfMerging() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        aware.putForAccount("111111111111", "Orders", "source-value");
        aware.putForAccount("222222222222", "StaleTable", "leftover-from-before");

        aware.cloneAccount("222222222222", "111111111111");

        assertEquals(Optional.of("source-value"), aware.getForAccount("222222222222", "Orders"));
        assertEquals(Optional.empty(), aware.getForAccount("222222222222", "StaleTable"),
                "clone must leave the target an exact copy of the source, not a merge with whatever was already there");
    }

    @Test
    void cloneAccountDeepCopiesSoMutatingTheSourceLaterDoesNotAffectTheTarget() {
        InMemoryStorage<String, Map<String, String>> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<Map<String, String>> aware = new AccountAwareStorageBackend<>(
                raw, null, "000000000000", value -> new HashMap<>(value));
        Map<String, String> sourceItem = new HashMap<>(Map.of("pk", "1", "name", "original"));
        aware.putForAccount("111111111111", "Orders", sourceItem);

        aware.cloneAccount("222222222222", "111111111111");
        sourceItem.put("name", "mutated-after-clone");

        assertEquals("original", aware.getForAccount("222222222222", "Orders").orElseThrow().get("name"),
                "the target's copy must be independent of the source's reference");
    }

    @Test
    void clearForAccountDeletesOnlyThatAccountsKeys() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        aware.putForAccount("111111111111", "Orders", "value");
        aware.putForAccount("222222222222", "Orders", "unrelated-value");

        aware.clearForAccount("111111111111");

        assertTrue(aware.keysForAccount("111111111111").isEmpty());
        assertEquals(Optional.of("unrelated-value"), aware.getForAccount("222222222222", "Orders"));
    }

    @Test
    void clearForAccountOnAlreadyEmptyAccountIsANoOp() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<String> aware = new AccountAwareStorageBackend<>(raw, null, "000000000000");

        aware.clearForAccount("111111111111");

        assertTrue(aware.keysForAccount("111111111111").isEmpty());
    }
}

package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Factory that creates {@link AccountAwareStorageBackend} instances based on configuration.
 * Every backend is wrapped in an account-aware decorator so resources are automatically
 * namespaced by the account ID of the calling credential.
 * Tracks all created backends for lifecycle management.
 */
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    private final EmulatorConfig config;
    private final ServiceConfigAccess serviceConfigAccess;
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    // A file path identifies one logical store: callers sharing a path are expected to agree on
    // its value type and storage mode. The first create() wins; repeat calls reuse that backend.
    private final Map<Path, StorageBackend<?, ?>> backendsByPath = new HashMap<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();
    // Grouped by the serviceName passed to create(), so account clone/clear can be scoped to
    // the services a caller actually asked for instead of touching every backend in the app.
    private final Map<String, List<AccountAwareStorageBackend<?>>> backendsByService = new HashMap<>();
    // The app-wide, jsr310-registered mapper — a plain `new ObjectMapper()` chokes on Instant
    // fields (e.g. TableDefinition.creationDateTime) when used as the clone deep-copier below.
    private final ObjectMapper objectMapper;

    @Inject
    Instance<RequestContext> requestContextInstance;

    @Inject
    public StorageFactory(EmulatorConfig config, ServiceConfigAccess serviceConfigAccess, ObjectMapper objectMapper) {
        this.config = config;
        this.serviceConfigAccess = serviceConfigAccess;
        this.objectMapper = objectMapper;
    }

    /** For tests that construct a StorageFactory directly, outside CDI. */
    public StorageFactory(EmulatorConfig config, ServiceConfigAccess serviceConfigAccess) {
        this(config, serviceConfigAccess,
                new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    }

    /**
     * Create an account-aware storage backend for the given service.
     * All keys are automatically prefixed with the current account ID derived from
     * the request credential. Async workers should use the {@code *ForAccount} overloads
     * on {@link AccountAwareStorageBackend} with the account ID stored on the resource model.
     *
     * @param serviceName   the service name (ssm, sqs, s3, …)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     */
    public synchronized <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                 TypeReference<Map<String, V>> typeReference) {
        String mode = resolveMode(serviceName);
        long flushInterval = resolveFlushInterval(serviceName);
        Path basePath = Path.of(config.storage().persistentPath());
        Path filePath = basePath.resolve(fileName);

        // Reuse an existing backend for the same file. Handing out a second backend bound to the
        // same path creates a duplicate in-memory store; on shutdown the stale duplicate flushes
        // after the active instance and clobbers persisted state (issue #1921).
        StorageBackend<?, ?> existing = backendsByPath.get(filePath);
        if (existing != null) {
            LOG.debugv("Reusing existing {0} storage for service {1} (file: {2})", mode, serviceName, filePath);
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<V> typed = (AccountAwareStorageBackend<V>) existing;
            registerForService(serviceName, typed);
            return typed;
        }

        LOG.debugv("Creating {0} storage for service {1} (file: {2})", mode, serviceName, filePath);

        StorageBackend<String, V> inner = switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(filePath, typeReference);
            case "hybrid" -> {
                var hybrid = new HybridStorage<>(filePath, typeReference, flushInterval);
                hybridBackends.add(hybrid);
                yield hybrid;
            }
            case "wal" -> {
                Path snapshotPath = basePath.resolve(fileName.replace(".json", "-snapshot.json"));
                Path walFilePath = basePath.resolve(fileName.replace(".json", ".wal"));
                long compactionInterval = config.storage().wal().compactionIntervalMs();
                var wal = new WalStorage<>(snapshotPath, walFilePath, typeReference, compactionInterval);
                walBackends.add(wal);
                yield wal;
            }
            default -> throw new IllegalArgumentException("Unknown storage mode: " + mode);
        };

        inner.load();

        JavaType valueType = objectMapper.getTypeFactory().constructType(typeReference).containedType(1);
        AccountAwareStorageBackend<V> backend = new AccountAwareStorageBackend<>(
                inner, requestContextInstance, config.defaultAccountId(),
                v -> objectMapper.convertValue(v, valueType));
        allBackends.add(backend);
        backendsByPath.put(filePath, backend);
        registerForService(serviceName, backend);
        return backend;
    }

    private void registerForService(String serviceName, AccountAwareStorageBackend<?> backend) {
        List<AccountAwareStorageBackend<?>> backends =
                backendsByService.computeIfAbsent(serviceName, k -> new ArrayList<>());
        if (!backends.contains(backend)) {
            backends.add(backend);
        }
    }

    /** Service names with a storage-backed clone/clear target, i.e. every name ever passed to {@link #create}. */
    public synchronized Set<String> knownServiceNames() {
        return backendsByService.keySet();
    }

    /**
     * Clones {@code sourceAccountId}'s data into {@code targetAccountId} for each requested service,
     * across every backend registered under that service name.
     */
    public synchronized void cloneAccount(String targetAccountId, String sourceAccountId, Set<String> services) {
        for (String service : services) {
            for (AccountAwareStorageBackend<?> backend : backendsByService.getOrDefault(service, List.of())) {
                backend.cloneAccount(targetAccountId, sourceAccountId);
            }
        }
    }

    /** Deletes {@code accountId}'s data for each requested service, across every backend registered under it. */
    public synchronized void clearAccount(String accountId, Set<String> services) {
        for (String service : services) {
            for (AccountAwareStorageBackend<?> backend : backendsByService.getOrDefault(service, List.of())) {
                backend.clearForAccount(accountId);
            }
        }
    }

    /** Load all storage backends from disk. */
    public synchronized void loadAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.load();
        }
    }

    /** Flush all storage backends to disk. */
    public synchronized void flushAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.flush();
        }
    }

    /** Clear all storage backends. */
    public synchronized void clearAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.clear();
        }
        flushAll();
    }

    /** Shutdown all managed backends (stop schedulers, close connections). */
    public synchronized void shutdownAll() {
        for (HybridStorage<?, ?> hybrid : hybridBackends) {
            hybrid.shutdown();
        }
        for (WalStorage<?, ?> wal : walBackends) {
            wal.shutdown();
        }
        flushAll();
    }

    private String resolveMode(String serviceName) {
        return serviceConfigAccess.storageMode(serviceName);
    }

    private long resolveFlushInterval(String serviceName) {
        return serviceConfigAccess.storageFlushInterval(serviceName);
    }
}

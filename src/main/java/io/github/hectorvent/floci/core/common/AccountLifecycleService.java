package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates account-scoped clone/clear for the services that implement {@link AccountCloneable}:
 * the generic {@code AccountAwareStorageBackend} copy via {@link StorageFactory} for the service's
 * stores, then the service's own step for what that copy can't handle (a bypass field outside
 * that layer, or an account-derived identifier baked into already-cloned data).
 *
 * <p>Only those services are supported. A service whose stores merely live behind
 * {@code StorageFactory} is not: its stored values may still name the source account, so a
 * generic copy alone would leave it inconsistent.
 *
 * <p>Both operations always take an explicit list of service names, with no "all" default,
 * and reject the whole call if any requested name is not supported, so a typo or an unsupported
 * service never results in a silently partial clone/clear.
 */
@ApplicationScoped
public class AccountLifecycleService {

    private final StorageFactory storageFactory;
    private final Instance<AccountCloneable> accountCloneables;

    @Inject
    public AccountLifecycleService(StorageFactory storageFactory, Instance<AccountCloneable> accountCloneables) {
        this.storageFactory = storageFactory;
        this.accountCloneables = accountCloneables;
    }

    // Asking each bean for its name also instantiates it, so its stores are registered with the
    // StorageFactory before the generic copy runs.
    public Set<String> knownServiceNames() {
        Set<String> names = new LinkedHashSet<>();
        for (AccountCloneable cloneable : accountCloneables) {
            names.add(cloneable.serviceName());
        }
        return names;
    }

    public void cloneAccount(String targetAccountId, String sourceAccountId, List<String> services) {
        Set<String> requested = validate(services);
        storageFactory.cloneAccount(targetAccountId, sourceAccountId, requested);
        for (AccountCloneable cloneable : accountCloneables) {
            if (requested.contains(cloneable.serviceName())) {
                cloneable.cloneAccountData(targetAccountId, sourceAccountId);
            }
        }
    }

    public void clearAccount(String accountId, List<String> services) {
        Set<String> requested = validate(services);
        storageFactory.clearAccount(accountId, requested);
        for (AccountCloneable cloneable : accountCloneables) {
            if (requested.contains(cloneable.serviceName())) {
                cloneable.clearAccountData(accountId);
            }
        }
    }

    private Set<String> validate(List<String> services) {
        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException("services must be a non-empty list of service names");
        }
        Set<String> known = knownServiceNames();
        Set<String> requested = new LinkedHashSet<>(services);
        for (String service : requested) {
            if (!known.contains(service)) {
                throw new IllegalArgumentException("Unknown or unsupported service: " + service);
            }
        }
        return requested;
    }
}

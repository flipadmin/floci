package io.github.hectorvent.floci.core.common;

/**
 * Implemented by services whose per-account state is not fully reachable through
 * {@code AccountAwareStorageBackend} alone — e.g. a plain in-memory field bypassing it, or a
 * stored value containing an account-derived identifier (ARN, URL) that must be rewritten to the
 * target account after a generic storage-level copy. Picked up via CDI {@code Instance<AccountCloneable>},
 * the same zero-registration pattern {@link Resettable} uses for the global state reset.
 */
public interface AccountCloneable {

    /** The service name this implementor covers, matching the name services pass to {@code StorageFactory.create}. */
    String serviceName();

    /**
     * Called after the generic storage-level clone has already copied this service's
     * {@code AccountAwareStorageBackend}-held data. Implementors handle whatever that step can't:
     * bypass fields holding real per-account data, and rewriting account-derived identifiers
     * embedded in already-cloned values.
     */
    void cloneAccountData(String targetAccountId, String sourceAccountId);

    /**
     * Called after the generic storage-level clear has already deleted this service's
     * {@code AccountAwareStorageBackend}-held data. Implementors clear whatever bypass fields
     * hold real per-account data outside that generic path.
     */
    void clearAccountData(String accountId);
}

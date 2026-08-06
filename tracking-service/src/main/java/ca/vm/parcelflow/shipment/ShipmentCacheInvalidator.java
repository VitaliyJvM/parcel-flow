package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.infrastructure.config.CacheConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops the cached tracking response after an event actually changed a shipment.
 *
 * <p><b>{@code AFTER_COMMIT}, not inside the transaction.</b> Evicting while the transaction is
 * still open leaves a window in which a concurrent reader misses the cache, reads the
 * <em>pre-commit</em> row, and writes that stale value back — producing an entry that is wrong and,
 * because nothing will evict it again, stays wrong until its TTL expires. Waiting for the commit
 * closes the window: any reader that repopulates after the eviction sees the new row.
 *
 * <p><b>Nothing here can fail the write.</b> The transaction has already committed by the time this
 * runs, so an exception could not roll anything back — it would only produce a confusing error for
 * work that succeeded. The cache error handler swallows Redis failures, and this catches anything
 * else, because a tracking event that is safely in PostgreSQL must never be reported as failed
 * because a cache eviction did not work.
 *
 * <p>Superseded events publish no event at all, so no eviction happens for them. Their cached entry
 * is still accurate — the shipment did not change — and evicting it would throw away a valid entry
 * to no purpose.
 */
@Component
public class ShipmentCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(ShipmentCacheInvalidator.class);

    private final CacheManager cacheManager;

    public ShipmentCacheInvalidator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipmentStatusAdvanced(ShipmentStatusAdvanced event) {
        try {
            var cache = cacheManager.getCache(CacheConfiguration.SHIPMENT_TRACKING_CACHE);
            if (cache != null) {
                cache.evict(event.shipmentId());
                log.debug("Evicted cached tracking response for shipment {} (now {})",
                        event.shipmentId(), event.currentStatus());
            }
        } catch (RuntimeException e) {
            log.warn("Could not evict the cached tracking response for shipment {}; it will serve "
                            + "stale data until its TTL expires",
                    event.shipmentId(), e);
        }
    }
}

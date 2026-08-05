package ca.vm.parcelflow.carrier;

/**
 * Carriers ParcelFlow can ingest events from.
 *
 * <p>These carriers are fictional. Using invented carriers keeps the project free of any real
 * carrier's trademarks and API semantics, and lets each carrier define its own event vocabulary
 * (added in Stage 2) without pretending to mirror a real integration.
 */
public enum CarrierCode {

    /** Fictional national parcel carrier. Sends dense scan events. */
    SWIFTPOST,

    /** Fictional regional carrier. Sends sparse events and no facility scans. */
    NORDEX,

    /** Fictional express carrier. Sends numeric status codes. */
    PACIFICA,

    /** Fictional metro courier. Sends same-day delivery events. */
    METROLINK
}

package com.gecopilot.client.trade;

/** A queued item (fill or offer event) tagged with the Coffer user it belongs to. */
public interface OwnedItem {
    /** Coffer user id, or null if captured with no known owner (or queued by an older build). */
    Long getOwnerId();
    void setOwnerId(Long id);
    long getTs();
}

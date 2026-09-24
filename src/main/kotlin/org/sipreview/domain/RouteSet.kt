package org.sipreview.domain

import kotlinx.serialization.Serializable

/**
 * A route set snapshot for a dialog: the ordered list of proxies a request must
 * traverse, plus the remote target. Derived from Record-Route + Contact.
 */
@Serializable
data class RouteSet(
    val dialogCanonical: String,
    val hops: List<String>,
    val remoteTarget: String?,
    val sourceObservationId: String
) {
    val fingerprint: String get() = Ids.short(listOf("routeset", dialogCanonical, hops.joinToString(">"), remoteTarget ?: ""))
}

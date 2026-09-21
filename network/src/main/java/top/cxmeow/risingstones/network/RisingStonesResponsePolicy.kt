package top.cxmeow.risingstones.network

/**
 * Acceptance rule used by the official web client's shared response interceptor.
 *
 * Acceptance does not validate a payload or grant any session capability. Callers must still
 * validate their endpoint's data and preserve endpoint-specific outcomes.
 */
object RisingStonesResponsePolicy {
    fun accepts(code: Int?): Boolean = code == 10000 || code == 10002
}

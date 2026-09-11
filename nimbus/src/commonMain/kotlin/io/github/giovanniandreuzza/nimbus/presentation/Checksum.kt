package io.github.giovanniandreuzza.nimbus.presentation

/**
 * A content digest of a downloaded file.
 *
 * @param algorithm the digest that produced [value].
 * @param value lowercase hex.
 * @author Giovanni Andreuzza
 */
public data class Checksum(
    public val algorithm: DigestAlgorithm,
    public val value: String
) {
    public companion object {
        /**
         * A [Checksum] from a hex string, normalised to lowercase.
         *
         * @throws IllegalArgumentException if [value] is not hex of the length [algorithm]
         * produces. A digest that is silently wrong is worse than no digest: a caller that
         * trusts it concludes the file is corrupt and re-downloads it forever.
         */
        public fun of(algorithm: DigestAlgorithm, value: String): Checksum {
            require(value.length == algorithm.hexLength) {
                "${algorithm.name} digests are ${algorithm.hexLength} hex characters, " +
                        "got ${value.length}"
            }
            require(value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "checksum must be hex, got '$value'"
            }
            return Checksum(algorithm, value.lowercase())
        }
    }
}

/**
 * Digest algorithms Nimbus can compute.
 *
 * An enum with one entry rather than a bare constant, so a second algorithm can be added
 * without a breaking change.
 *
 * @author Giovanni Andreuzza
 */
public enum class DigestAlgorithm(internal val hexLength: Int) {
    /**
     * SHA-256. Costs no new dependency for any consumer: `org.kotlincrypto.hash:sha2` is
     * already used to derive task ids.
     */
    SHA256(hexLength = 64)
}

/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.identity.util

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject
import java.security.cert.X509Certificate

// This code is based on https://github.com/google/android-key-attestation
class AndroidAttestationExtensionParser(cert: X509Certificate) {
    enum class SecurityLevel {
        SOFTWARE,
        TRUSTED_ENVIRONMENT,
        STRONG_BOX
    }

    enum class VerifiedBootState {
        UNKNOWN,
        GREEN,
        YELLOW,
        ORANGE,
        RED
    }

    val attestationSecurityLevel: SecurityLevel
    val attestationVersion: Int

    val keymasterSecurityLevel: SecurityLevel
    val keymasterVersion: Int

    val attestationChallenge: ByteArray
    val uniqueId: ByteArray

    val verifiedBootState: VerifiedBootState

    val applicationSignatureDigests: List<ByteArray>

    private val softwareEnforcedAuthorizations: Map<Int, ASN1Primitive?>
    private val teeEnforcedAuthorizations: Map<Int, ASN1Primitive?>

    init {
        val attestationExtensionBytes = cert.getExtensionValue(KEY_DESCRIPTION_OID)
        require(attestationExtensionBytes != null && attestationExtensionBytes.size > 0) {
            "Couldn't find keystore attestation extension."
        }
        var seq: ASN1Sequence
        ASN1InputStream(attestationExtensionBytes).use { asn1InputStream ->
            // The extension contains one object, a sequence, in the
            // Distinguished Encoding Rules (DER)-encoded form. Get the DER
            // bytes.
            val derSequenceBytes = (asn1InputStream.readObject() as ASN1OctetString).octets
            ASN1InputStream(derSequenceBytes).use { seqInputStream ->
                seq = seqInputStream.readObject() as ASN1Sequence
            }
        }

        attestationVersion = getIntegerFromAsn1(seq.getObjectAt(ATTESTATION_VERSION_INDEX))
        this.attestationSecurityLevel = securityLevelToEnum(
            getIntegerFromAsn1(
                seq.getObjectAt(ATTESTATION_SECURITY_LEVEL_INDEX)
            )
        )

        keymasterVersion = getIntegerFromAsn1(seq.getObjectAt(KEYMASTER_VERSION_INDEX))
        this.keymasterSecurityLevel = securityLevelToEnum(
            getIntegerFromAsn1(seq.getObjectAt(KEYMASTER_SECURITY_LEVEL_INDEX))
        )
        attestationChallenge =
            (seq.getObjectAt(ATTESTATION_CHALLENGE_INDEX) as ASN1OctetString).octets
        uniqueId = (seq.getObjectAt(UNIQUE_ID_INDEX) as ASN1OctetString).octets

        softwareEnforcedAuthorizations = getAuthorizationMap(
            (seq.getObjectAt(SW_ENFORCED_INDEX) as ASN1Sequence).toArray()
        )
        teeEnforcedAuthorizations = getAuthorizationMap(
            (seq.getObjectAt(TEE_ENFORCED_INDEX) as ASN1Sequence).toArray()
        )

        val rootOfTrustSeq = findAuthorizationListEntry(teeEnforcedAuthorizations, 704) as ASN1Sequence?
        verifiedBootState =
            if (rootOfTrustSeq != null) {
                when (getIntegerFromAsn1(rootOfTrustSeq.getObjectAt(2))) {
                    0 -> VerifiedBootState.GREEN
                    1 -> VerifiedBootState.YELLOW
                    2 -> VerifiedBootState.ORANGE
                    3 -> VerifiedBootState.RED
                    else -> VerifiedBootState.UNKNOWN
                }
            } else {
                VerifiedBootState.UNKNOWN
            }

        val encodedAttestationApplicationId = getSoftwareAuthorizationByteString(709)

        val attestationApplicationIdSeq =
            ASN1InputStream(encodedAttestationApplicationId).readObject() as ASN1Sequence

        val signatureDigestSet = attestationApplicationIdSeq.getObjectAt(1) as ASN1Set
        val digests = mutableListOf<ByteArray>()
        for (n in 0 until signatureDigestSet.size()) {
            val octetString = signatureDigestSet.getObjectAt(n) as ASN1OctetString
            digests.add(octetString.octets)
        }
        applicationSignatureDigests = digests
    }

    val softwareEnforcedAuthorizationTags: Set<Int>
        get() = softwareEnforcedAuthorizations.keys
    val teeEnforcedAuthorizationTags: Set<Int>
        get() = teeEnforcedAuthorizations.keys

    fun getSoftwareAuthorizationInteger(tag: Int): Int? {
        val entry = findAuthorizationListEntry(softwareEnforcedAuthorizations, tag)
        return entry?.let { getIntegerFromAsn1(it) }
    }

    fun getSoftwareAuthorizationLong(tag: Int): Long? {
        val entry = findAuthorizationListEntry(softwareEnforcedAuthorizations, tag)
        return entry?.let { getLongFromAsn1(it) }
    }

    fun getTeeAuthorizationInteger(tag: Int): Int? {
        val entry = findAuthorizationListEntry(teeEnforcedAuthorizations, tag)
        return entry?.let { getIntegerFromAsn1(it) }
    }

    fun getTeeAuthorizationLong(tag: Int): Long? {
        val entry = findAuthorizationListEntry(teeEnforcedAuthorizations, tag)
        return entry?.let { getLongFromAsn1(it) }
    }

    fun getSoftwareAuthorizationBoolean(tag: Int): Boolean {
        val entry = findAuthorizationListEntry(softwareEnforcedAuthorizations, tag)
        return entry != null
    }

    fun getTeeAuthorizationBoolean(tag: Int): Boolean {
        val entry = findAuthorizationListEntry(teeEnforcedAuthorizations, tag)
        return entry != null
    }

    fun getSoftwareAuthorizationByteString(tag: Int): ByteArray? {
        val entry =
            findAuthorizationListEntry(softwareEnforcedAuthorizations, tag) as ASN1OctetString?
        return entry?.octets
    }

    fun getTeeAuthorizationByteString(tag: Int): ByteArray? {
        val entry = findAuthorizationListEntry(teeEnforcedAuthorizations, tag) as ASN1OctetString?
        return entry?.octets
    }

    companion object {
        private const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"
        private const val ATTESTATION_VERSION_INDEX = 0
        private const val ATTESTATION_SECURITY_LEVEL_INDEX = 1
        private const val KEYMASTER_VERSION_INDEX = 2
        private const val KEYMASTER_SECURITY_LEVEL_INDEX = 3
        private const val ATTESTATION_CHALLENGE_INDEX = 4
        private const val UNIQUE_ID_INDEX = 5
        private const val SW_ENFORCED_INDEX = 6
        private const val TEE_ENFORCED_INDEX = 7

        // Some security values. The complete list is in this AOSP file:
        // hardware/libhardware/include/hardware/keymaster_defs.h
        private const val KM_SECURITY_LEVEL_SOFTWARE = 0
        private const val KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1
        private const val KM_SECURITY_LEVEL_STRONG_BOX = 2
        private fun findAuthorizationListEntry(
            authorizationMap: Map<Int, ASN1Primitive?>, tag: Int
        ): ASN1Primitive? {
            return authorizationMap.getOrDefault(tag, null)
        }

        private fun getBooleanFromAsn1(asn1Value: ASN1Encodable): Boolean {
            return if (asn1Value is ASN1Boolean) {
                asn1Value.isTrue
            } else {
                throw RuntimeException(
                    "Boolean value expected; found " + asn1Value.javaClass.name + " instead."
                )
            }
        }

        private fun getIntegerFromAsn1(asn1Value: ASN1Encodable): Int {
            return if (asn1Value is ASN1Integer) {
                asn1Value.value.toInt()
            } else if (asn1Value is ASN1Enumerated) {
                asn1Value.value.toInt()
            } else {
                throw IllegalArgumentException(
                    "Integer value expected; found " + asn1Value.javaClass.name + " instead."
                )
            }
        }

        private fun getLongFromAsn1(asn1Value: ASN1Encodable): Long {
            return if (asn1Value is ASN1Integer) {
                asn1Value.value.toLong()
            } else if (asn1Value is ASN1Enumerated) {
                asn1Value.value.toLong()
            } else {
                throw IllegalArgumentException(
                    "Integer value expected; found " + asn1Value.javaClass.name + " instead."
                )
            }
        }

        private fun getAuthorizationMap(
            authorizationList: Array<ASN1Encodable>
        ): Map<Int, ASN1Primitive?> {
            val authorizationMap: MutableMap<Int, ASN1Primitive?> = HashMap()
            for (entry in authorizationList) {
                val taggedEntry = entry as ASN1TaggedObject
                authorizationMap[taggedEntry.tagNo] = taggedEntry.baseObject as ASN1Primitive
            }
            return authorizationMap
        }

        private fun securityLevelToEnum(securityLevel: Int): SecurityLevel {
            return when (securityLevel) {
                KM_SECURITY_LEVEL_SOFTWARE -> SecurityLevel.SOFTWARE
                KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TRUSTED_ENVIRONMENT
                KM_SECURITY_LEVEL_STRONG_BOX -> SecurityLevel.STRONG_BOX
                else -> throw IllegalArgumentException("Invalid security level.")
            }
        }
    }
}

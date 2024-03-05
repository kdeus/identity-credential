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
package com.android.identity.mdoc.mso

import com.android.identity.cbor.Cbor
import com.android.identity.crypto.EcCurve
import com.android.identity.crypto.EcPublicKeyDoubleCoordinate
import com.android.identity.internal.Util
import com.android.identity.mdoc.TestVectors
import com.android.identity.util.Timestamp
import org.junit.Assert
import org.junit.Test

class MobileSecurityObjectParserTest {
    @Test
    fun testMSOParserWithVectors() {
        val deviceResponse =
            Cbor.decode(Util.fromHex(TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE))
        val documentDataItem = deviceResponse["documents"][0]
        val issuerSigned = documentDataItem["issuerSigned"]
        val issuerAuthDataItem = issuerSigned["issuerAuth"]
        val mobileSecurityObjectBytes = Cbor.decode(issuerAuthDataItem.asCoseSign1.payload!!)
        val mobileSecurityObject = mobileSecurityObjectBytes.asTaggedEncodedCbor
        val encodedMobileSecurityObject = Cbor.encode(mobileSecurityObject)

        // the response above and all the following constants are from ISO 18013-5 D.4.1.2 mdoc
        // response - the goal is to check that the parser returns the expected values
        val mso = MobileSecurityObjectParser(encodedMobileSecurityObject).parse()
        Assert.assertEquals("1.0", mso.version)
        Assert.assertEquals("SHA-256", mso.digestAlgorithm)
        Assert.assertEquals("org.iso.18013.5.1.mDL", mso.docType)
        Assert.assertEquals(
            setOf("org.iso.18013.5.1", "org.iso.18013.5.1.US"),
            mso.valueDigestNamespaces
        )
        Assert.assertNull(mso.getDigestIDs("abc"))
        val isoDigestIDs = mso.getDigestIDs("org.iso.18013.5.1")
        Assert.assertEquals(
            setOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L),
            isoDigestIDs!!.keys
        )
        Assert.assertEquals(
            "75167333b47b6c2bfb86eccc1f438cf57af055371ac55e1e359e20f254adcebf",
            Util.toHex(isoDigestIDs[0L]!!)
        )
        Assert.assertEquals(
            "67e539d6139ebd131aef441b445645dd831b2b375b390ca5ef6279b205ed4571",
            Util.toHex(isoDigestIDs[1L]!!)
        )
        Assert.assertEquals(
            "3394372ddb78053f36d5d869780e61eda313d44a392092ad8e0527a2fbfe55ae",
            Util.toHex(isoDigestIDs[2L]!!)
        )
        Assert.assertEquals(
            "2e35ad3c4e514bb67b1a9db51ce74e4cb9b7146e41ac52dac9ce86b8613db555",
            Util.toHex(isoDigestIDs[3L]!!)
        )
        Assert.assertEquals(
            "ea5c3304bb7c4a8dcb51c4c13b65264f845541341342093cca786e058fac2d59",
            Util.toHex(isoDigestIDs[4L]!!)
        )
        Assert.assertEquals(
            "fae487f68b7a0e87a749774e56e9e1dc3a8ec7b77e490d21f0e1d3475661aa1d",
            Util.toHex(isoDigestIDs[5L]!!)
        )
        Assert.assertEquals(
            "7d83e507ae77db815de4d803b88555d0511d894c897439f5774056416a1c7533",
            Util.toHex(isoDigestIDs[6L]!!)
        )
        Assert.assertEquals(
            "f0549a145f1cf75cbeeffa881d4857dd438d627cf32174b1731c4c38e12ca936",
            Util.toHex(isoDigestIDs[7L]!!)
        )
        Assert.assertEquals(
            "b68c8afcb2aaf7c581411d2877def155be2eb121a42bc9ba5b7312377e068f66",
            Util.toHex(isoDigestIDs[8L]!!)
        )
        Assert.assertEquals(
            "0b3587d1dd0c2a07a35bfb120d99a0abfb5df56865bb7fa15cc8b56a66df6e0c",
            Util.toHex(isoDigestIDs[9L]!!)
        )
        Assert.assertEquals(
            "c98a170cf36e11abb724e98a75a5343dfa2b6ed3df2ecfbb8ef2ee55dd41c881",
            Util.toHex(isoDigestIDs[10L]!!)
        )
        Assert.assertEquals(
            "b57dd036782f7b14c6a30faaaae6ccd5054ce88bdfa51a016ba75eda1edea948",
            Util.toHex(isoDigestIDs[11L]!!)
        )
        Assert.assertEquals(
            "651f8736b18480fe252a03224ea087b5d10ca5485146c67c74ac4ec3112d4c3a",
            Util.toHex(isoDigestIDs[12L]!!)
        )
        val isoUSDigestIDs = mso.getDigestIDs("org.iso.18013.5.1.US")
        Assert.assertEquals(setOf(0L, 1L, 2L, 3L), isoUSDigestIDs!!.keys)
        Assert.assertEquals(
            "d80b83d25173c484c5640610ff1a31c949c1d934bf4cf7f18d5223b15dd4f21c",
            Util.toHex(isoUSDigestIDs[0L]!!)
        )
        Assert.assertEquals(
            "4d80e1e2e4fb246d97895427ce7000bb59bb24c8cd003ecf94bf35bbd2917e34",
            Util.toHex(isoUSDigestIDs[1L]!!)
        )
        Assert.assertEquals(
            "8b331f3b685bca372e85351a25c9484ab7afcdf0d2233105511f778d98c2f544",
            Util.toHex(isoUSDigestIDs[2L]!!)
        )
        Assert.assertEquals(
            "c343af1bd1690715439161aba73702c474abf992b20c9fb55c36a336ebe01a87",
            Util.toHex(isoUSDigestIDs[3L]!!)
        )
        val deviceKeyFromVector = EcPublicKeyDoubleCoordinate(
            EcCurve.P256,
            Util.fromHex(TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X),
            Util.fromHex(TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y)
        )
        Assert.assertEquals(deviceKeyFromVector, mso.deviceKey)
        Assert.assertNull(mso.deviceKeyAuthorizedNameSpaces)
        Assert.assertNull(mso.deviceKeyAuthorizedDataElements)
        Assert.assertNull(mso.deviceKeyInfo)
        Assert.assertEquals(Timestamp.ofEpochMilli(1601559002000L), mso.signed)
        Assert.assertEquals(Timestamp.ofEpochMilli(1601559002000L), mso.validFrom)
        Assert.assertEquals(Timestamp.ofEpochMilli(1633095002000L), mso.validUntil)
        Assert.assertNull(mso.expectedUpdate)
    }
}
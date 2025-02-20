package com.android.identity.issuance

import com.android.identity.cbor.annotation.CborSerializable
import com.android.identity.flow.annotation.FlowException

@FlowException
@CborSerializable
class LandingUrlUnknownException(override val message: String) : Exception(message) {
    companion object
}
package com.android.identity.issuance

import com.android.identity.flow.client.FlowBase
import com.android.identity.flow.annotation.FlowInterface
import com.android.identity.flow.annotation.FlowMethod

@FlowInterface
interface WalletServer: FlowBase {
    /**
     * No need to call on client-side if using a [WalletServer] obtained from a
     * [WalletServerProvider].
     */
    @FlowMethod
    suspend fun authenticate(): AuthenticationFlow

    /**
     * Static information about the available Issuing Authorities.
     *
     * Queried from all issuing authorities at initialization time.
     */
    @FlowMethod
    suspend fun getIssuingAuthorityConfigurations(): List<IssuingAuthorityConfiguration>

    /**
     * Obtains interface to a particular Issuing Authority.
     *
     * Do not call this method directly. WalletServerProvider maintains a cache of the issuing
     * authority instances, to avoid creating unneeded instances (that can interfere with
     * notifications), go through WalletServerProvider.
     */
    @FlowMethod
    suspend fun getIssuingAuthority(identifier: String): IssuingAuthority

    /**
     * Waits until a notification is available for the client.
     *
     * A wallet should only use this if [WalletServerCapabilities.waitForNotificationSupported] is
     * set to `true`.
     *
     * This may error out if a notification wasn't available within a certain server-defined
     * timeframe.
     *
     * @return a [ByteArray] with the notification payload.
     */
    @FlowMethod
    suspend fun waitForNotification(): ByteArray
}

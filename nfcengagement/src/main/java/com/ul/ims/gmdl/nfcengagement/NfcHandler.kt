/*
 * Copyright (C) 2019 Google LLC
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

package com.ul.ims.gmdl.nfcengagement

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.ul.ims.gmdl.cbordata.deviceEngagement.transferMethods.BleDeviceRetrievalMethod
import com.ul.ims.gmdl.cbordata.deviceEngagement.transferMethods.WiFiAwareDeviceRetrievalMethod
import com.ul.ims.gmdl.nfcengagement.NfcConstants.Companion.createBLEStaticHandoverRecord
import com.ul.ims.gmdl.nfcengagement.NfcConstants.Companion.createNfcStaticHandoverRecord
import com.ul.ims.gmdl.nfcengagement.NfcConstants.Companion.createWiFiAwareStaticHandoverRecord
import com.ul.ims.gmdl.nfcengagement.NfcConstants.Companion.statusWordEndOfFileReached
import com.ul.ims.gmdl.nfcengagement.NfcConstants.Companion.statusWordFileNotFound
import com.ul.ims.gmdl.nfcengagement.NfcConstants.Companion.statusWordInstructionNotSupported
import com.ul.ims.gmdl.nfcengagement.NfcConstants.Companion.statusWordOK
import com.ul.ims.gmdl.nfcengagement.NfcConstants.Companion.statusWordWrongParameters
import com.ul.ims.gmdl.offlinetransfer.transportLayer.TransferChannels
import java.util.*

class NfcHandler : HostApduService() {

    companion object {
        const val EXTRA_DEVICE_ENGAGEMENT_PAYLOAD =
            "ul.ims.gmdl.mdlnfc.EXTRA_DEVICE_ENGAGEMENT_PAYLOAD"
        const val EXTRA_TRANSFER_METHOD = "ul.ims.gmdl.mdlnfc.EXTRA_TRANSFER_METHOD"
        const val EXTRA_BLE_OPTIONS = "ul.ims.gmdl.mdlnfc.EXTRA_BLE_OPTIONS"
        const val EXTRA_NFC_OPTIONS = "ul.ims.gmdl.mdlnfc.EXTRA_NFC_OPTIONS"
        const val EXTRA_WIFI_OPTIONS = "ul.ims.gmdl.mdlnfc.EXTRA_WIFI_OPTIONS"

        private const val TAG = "NfcHandler"
        const val ACTION_NFC_DEVICE_ENGAGEMENT_CALLBACK =
            "com.ul.ims.gmdl.ACTION_NFC_DEVICE_ENGAGEMENT_CALLBACK"
        const val PACKAGE_NFC_DEVICE_ENGAGEMENT_CALLBACK = "com.ul.ims.gmdl"
    }

    class File(val fileId: ByteArray, val content: ByteArray)

    enum class CommandType {
        SELECT_BY_AID,
        SELECT_FILE,
        READ_BINARY,
        UPDATE_BINARY,
        OTHER
    }

    private val capabilityContainerFileId = byteArrayOfInts(0xE1, 0x03)
    private val capabilityContainerFileContent = byteArrayOfInts(
        0x00, 0x0F, // size of capability container '00 0F' = 15 bytes
        0x20,       // mapping version v2.0
        0x7F, 0xFF, // maximum response data length '7F FF'
        0x7F, 0xFF, // maximum command data length '7F FF'
        0x04, 0x06, // NDEF File Control TLV
        0xE1, 0x04, // NDEF file identifier 'E1 04'
        0xFF, 0xFE, // maximum NDEF file size 'FF FE'
        0x00,       // file read access condition (allow read)
        0xFF        // file write access condition (do not write)
    )
    private val capabilityContainerFile =
        File(capabilityContainerFileId, capabilityContainerFileContent)

    private val ndefFileId = byteArrayOfInts(0xE1, 0x04)

    private var fileSystem: List<File> = listOf(capabilityContainerFile)

    private var selectedFile: File? = null


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { intentVal ->
            val deviceEngagementBytes = intentVal.getByteArrayExtra(EXTRA_DEVICE_ENGAGEMENT_PAYLOAD)
            val transferMethod =
                intentVal.getSerializableExtra(EXTRA_TRANSFER_METHOD) as? TransferChannels
            val bleOptions =
                intentVal.getSerializableExtra(EXTRA_BLE_OPTIONS) as? BleDeviceRetrievalMethod.BleOptions
            // NFC Options not used for now
//            val nfcOptions =
//                intentVal.getSerializableExtra(EXTRA_BLE_OPTIONS) as? NfcDeviceRetrievalMethod.NfcOptions
            val wifiOptions =
                intentVal.getSerializableExtra(EXTRA_BLE_OPTIONS) as? WiFiAwareDeviceRetrievalMethod.WifiOptions

            deviceEngagementBytes?.let { deviceEngagementPayload ->
                transferMethod?.let { channel ->
                    when (channel) {
                        TransferChannels.BLE -> {
                            setupBluetoothHandover(
                                deviceEngagementPayload,
                                bleOptions?.peripheralServer ?: false,
                                bleOptions?.centralClient ?: false,
                                bleOptions?.peripheralServerUUID ?: bleOptions?.centralClientUUID
                            )
                        }
                        TransferChannels.WiFiAware -> {
                            setupWiFiHandover(
                                deviceEngagementPayload,
                                wifiOptions?.passPhrase,
                                wifiOptions?.supportedBands
                            )
                        }
                        TransferChannels.NFC -> {
                            setupNfcHandover(
                                deviceEngagementPayload
                            )
                        }
                    }
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun setupBluetoothHandover(
        deviceEngagementPayload: ByteArray,
        blePeripheralMode: Boolean,
        bleCentralMode: Boolean,
        bleUUID: UUID?
    ) {
        val handoverRecord =
            createBLEStaticHandoverRecord(
                deviceEngagementPayload,
                blePeripheralMode,
                bleCentralMode,
                bleUUID
            )
        val ndefFileContent =
            intAsTwoBytes(handoverRecord.byteArrayLength).plus(handoverRecord.toByteArray())
        val ndefFile = File(ndefFileId, ndefFileContent)

        fileSystem = listOf(capabilityContainerFile, ndefFile)
    }

    private fun setupWiFiHandover(
        deviceEngagementPayload: ByteArray,
        passPhrase: String?,
        supportedBands: ByteArray?
    ) {
        val handoverRecord =
            createWiFiAwareStaticHandoverRecord(
                deviceEngagementPayload,
                passPhrase,
                supportedBands
            )
        val ndefFileContent =
            intAsTwoBytes(handoverRecord.byteArrayLength).plus(handoverRecord.toByteArray())
        val ndefFile = File(ndefFileId, ndefFileContent)

        fileSystem = listOf(capabilityContainerFile, ndefFile)
    }

    private fun setupNfcHandover(
        deviceEngagementPayload: ByteArray
    ) {
        val handoverRecord =
            createNfcStaticHandoverRecord(
                deviceEngagementPayload
            )
        val ndefFileContent =
            intAsTwoBytes(handoverRecord.byteArrayLength).plus(handoverRecord.toByteArray())
        val ndefFile = File(ndefFileId, ndefFileContent)

        fileSystem = listOf(capabilityContainerFile, ndefFile)
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        Log.d(TAG, "Command -> " + toHexString(commandApdu))

        val response = when (getCommandType(commandApdu)) {
            CommandType.SELECT_BY_AID -> handleSelectByAid(commandApdu)
            CommandType.SELECT_FILE -> handleSelectFile(commandApdu)
            CommandType.READ_BINARY -> handleReadBinary(commandApdu)
            CommandType.UPDATE_BINARY -> handleUpdateBinary()
            CommandType.OTHER -> statusWordInstructionNotSupported
        }

        Log.d(TAG, "Response -> " + toHexString(response))
        return response
    }

    private fun getCommandType(commandApdu: ByteArray?): CommandType {
        commandApdu?.let { apdu ->
            if (apdu.size < 3) {
                return CommandType.OTHER
            }

            val ins = toInt(apdu[1])
            val p1 = toInt(apdu[2])

            if (ins == 0xA4) {
                if (p1 == 0x04) {
                    return CommandType.SELECT_BY_AID
                } else if (p1 == 0x00) {
                    return CommandType.SELECT_FILE
                }
            } else if (ins == 0xB0) {
                return CommandType.READ_BINARY
            } else if (ins == 0xD6) {
                return CommandType.UPDATE_BINARY
            }
        }

        return CommandType.OTHER
    }

    private fun handleSelectByAid(commandApdu: ByteArray?): ByteArray {
        val aid = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        commandApdu?.let { apdu ->
            if (apdu.size < 12 || !apdu.copyOfRange(5, 12).contentEquals(aid)) {
                return statusWordFileNotFound
            }
            Intent().also { intent ->
                intent.action = ACTION_NFC_DEVICE_ENGAGEMENT_CALLBACK
                intent.setPackage(PACKAGE_NFC_DEVICE_ENGAGEMENT_CALLBACK)
                sendBroadcast(intent)
            }
            return statusWordOK
        }
        return statusWordFileNotFound
    }

    private fun handleSelectFile(commandApdu: ByteArray?): ByteArray {
        commandApdu?.let { apdu ->
            // Extract fileId from select command
            if (apdu.size < 7) {
                return statusWordFileNotFound
            }

            val fileId = apdu.copyOfRange(5, 7)

            for (f: File in fileSystem) {
                if (f.fileId.contentEquals(fileId)) {
                    selectedFile = f
                    return statusWordOK
                }
            }

            return statusWordFileNotFound
        }

        return statusWordFileNotFound
    }

    private fun handleReadBinary(commandApdu: ByteArray?): ByteArray {
        selectedFile?.let { file ->
            // Extract offset and data length from read binary
            if (commandApdu == null || commandApdu.size < 5) {
                return statusWordFileNotFound
            }

            val offset = twoBytesToInt(commandApdu.copyOfRange(2, 4))
            val responseSize = toInt(commandApdu[4])

            if (file.content.size < offset) {
                return statusWordWrongParameters
            } else if (file.content.size < offset + responseSize) {
                return statusWordEndOfFileReached
            }

            val responseData = file.content.copyOfRange(offset, offset + responseSize)

            return responseData.plus(statusWordOK)
        }

        return statusWordFileNotFound
    }

    private fun handleUpdateBinary(): ByteArray {
        return statusWordInstructionNotSupported
    }

    override fun onDeactivated(p0: Int) {
// ignore
    }
}

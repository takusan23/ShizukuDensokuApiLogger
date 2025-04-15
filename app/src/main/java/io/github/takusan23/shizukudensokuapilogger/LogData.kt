package io.github.takusan23.shizukudensokuapilogger

import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.PhysicalChannelConfig
import android.telephony.ServiceState
import android.telephony.SignalStrength

data class LogData(
    val time: Long = System.currentTimeMillis(),
    val logType: LogType
) {

    sealed interface LogType {

        data class CellInfoLog(val cellInfoList: List<CellInfo>) : LogType

        data class ServiceStateLog(val serviceState: ServiceState) : LogType

        data class SignalStrengthLog(val signalStrength: SignalStrength) : LogType

        data class NetworkScanLog(
            val status: NetworkScanStatus,
            val cellInfoList: List<CellInfo>? = null
        ) : LogType

        data class RegistrationFailedLog(
            val cellIdentity: CellIdentity,
            val chosenPlmn: String,
            val domain: Int,
            val causeCode: Int,
            val additionalCauseCode: Int
        ) : LogType

        data class BroadcastLog(
            val action: BroadcastAction,
            val keyValue: Map<String, String>
        ) : LogType

        data class PhysicalChannelConfigLog(val configs: List<PhysicalChannelConfig>) : LogType

    }

    enum class NetworkScanStatus {
        CALLBACK_RESTRICTED_SCAN_RESULTS,
        CALLBACK_SCAN_RESULTS,
        CALLBACK_SCAN_ERROR,
        CALLBACK_SCAN_COMPLETE
    }

    enum class BroadcastAction(val actionString: String) {
        ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE("ACTION_CARRIER_SIGNAL_DEFAULT_NETWORK_AVAILABLE"),
        ACTION_CARRIER_SIGNAL_PCO_VALUE("ACTION_CARRIER_SIGNAL_PCO_VALUE"),
        ACTION_CARRIER_SIGNAL_REDIRECTED("ACTION_CARRIER_SIGNAL_REDIRECTED"),
        ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED("ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED"),
        ACTION_CARRIER_SIGNAL_RESET("ACTION_CARRIER_SIGNAL_RESET"),
        ACTION_MULTI_SIM_CONFIG_CHANGED("ACTION_MULTI_SIM_CONFIG_CHANGED"),
        ACTION_NETWORK_COUNTRY_CHANGED("ACTION_NETWORK_COUNTRY_CHANGED"),
        ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED("ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED"),
        ACTION_SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED("ACTION_SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED")
    }

    data class BroadcastData(
        val action: BroadcastAction,
        val extra: List<String>
    )

}
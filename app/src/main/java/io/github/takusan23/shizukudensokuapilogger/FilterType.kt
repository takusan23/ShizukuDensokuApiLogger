package io.github.takusan23.shizukudensokuapilogger

import io.github.takusan23.shizukudensokuapilogger.LogData.LogType.BroadcastLog
import io.github.takusan23.shizukudensokuapilogger.LogData.LogType.CellInfoLog
import io.github.takusan23.shizukudensokuapilogger.LogData.LogType.PhysicalChannelConfigLog
import io.github.takusan23.shizukudensokuapilogger.LogData.LogType.RegistrationFailedLog
import io.github.takusan23.shizukudensokuapilogger.LogData.LogType.ServiceStateLog

enum class FilterType {
    CellInfoLog,
    SignalStrengthLog,
    ServiceStateLog,
    NetworkScanLog,
    RegistrationFailedLog,
    BroadcastLog,
    PhysicalChannelConfigLog
}

val LogData.LogType.convertFilterType: FilterType
    get() = when (this) {
        is BroadcastLog -> FilterType.BroadcastLog
        is CellInfoLog -> FilterType.CellInfoLog
        is PhysicalChannelConfigLog -> FilterType.PhysicalChannelConfigLog
        is RegistrationFailedLog -> FilterType.RegistrationFailedLog
        is ServiceStateLog -> FilterType.ServiceStateLog
        is LogData.LogType.SignalStrengthLog -> FilterType.SignalStrengthLog
        is LogData.LogType.NetworkScanLog -> FilterType.NetworkScanLog
    }
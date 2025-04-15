package io.github.takusan23.shizukudensokuapilogger

import android.app.Application
import android.app.IActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ServiceManager
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.ICellInfoCallback
import android.telephony.PhysicalChannelConfig
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
import com.android.internal.telephony.ITelephonyRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuBinderWrapper
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context
        get() = getApplication<Application>().applicationContext

    private val telephony: ITelephony
        get() = ITelephony.Stub.asInterface(
            ShizukuBinderWrapper(ServiceManager.getService("phone"))
        )

    private val telephonyRegistry: ITelephonyRegistry
        get() = ITelephonyRegistry.Stub.asInterface(
            ShizukuBinderWrapper(ServiceManager.getService("telephony.registry"))
        )

    private val subscription: ISub
        get() = ISub.Stub.asInterface(
            ShizukuBinderWrapper(ServiceManager.getService("isub"))
        )
    private val activityManager: IActivityManager
        get() = IActivityManager.Stub.asInterface(
            ShizukuBinderWrapper(ServiceManager.getService("service"))
        )

    private val defaultSubscriptionId: Int
        get() = SubscriptionManager.getActiveDataSubscriptionId()

    private val _logList = MutableStateFlow(emptyList<LogData>())
    private val _currentFilter = MutableStateFlow(FilterType.entries.toList())

    val currentFilter = _currentFilter.asStateFlow()
    val logList = combine(
        _logList,
        _currentFilter,
        ::Pair
    ).map { (logList, filter) ->
        logList.filter { log -> log.logType.convertFilterType in filter }
    }

    init {
        listen()
        registerBroadcast()
        updateCellInfo()
    }

    fun addFilter(filterType: FilterType) {
        _currentFilter.value += filterType
    }

    fun removeFilter(filterType: FilterType) {
        _currentFilter.value -= filterType
    }

    private fun updateCellInfo() {
        viewModelScope.launch {
            while (true) {
                delay(5_000L)

                val cellList = suspendCoroutine<List<CellInfo>?> {
                    telephony.requestCellInfoUpdate(defaultSubscriptionId, object : ICellInfoCallback.Stub() {
                        override fun onCellInfo(state: MutableList<CellInfo>?) {
                            it.resume(state)
                        }

                        override fun onError(errorCode: Int, exceptionName: String?, message: String?) {
                            it.resume(emptyList())
                        }
                    }, telephony.currentPackageName, context.attributionTag)
                }

                _logList.value = listOf(
                    LogData(
                        logType = LogData.LogType.CellInfoLog(
                            cellInfoList = cellList ?: emptyList()
                        )
                    )
                ) + _logList.value
            }
        }
    }

    private fun registerBroadcast() {
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context ?: return
                intent ?: return
                val broadcastAction = LogData.BroadcastAction.entries.firstOrNull { it.actionString == intent.action } ?: return
                val extra = intent.extras.let { bundle -> bundle?.keySet()?.associateWith { key -> bundle.get(key).toString() } } ?: emptyMap()

                _logList.value = listOf(
                    LogData(
                        logType = LogData.LogType.BroadcastLog(
                            action = broadcastAction,
                            keyValue = extra
                        )
                    )
                ) + _logList.value
            }
        }
        val intentFilter = IntentFilter().apply {
            LogData.BroadcastAction.entries.forEach { action ->
                addAction(action.actionString)
            }
        }
        ContextCompat.registerReceiver(context, broadcastReceiver, intentFilter, ContextCompat.RECEIVER_EXPORTED)
        addCloseable { context.unregisterReceiver(broadcastReceiver) }
    }

    private fun listen() {
        val callback = object : TelephonyCallback(), TelephonyCallback.PhysicalChannelConfigListener, TelephonyCallback.CellInfoListener, TelephonyCallback.ServiceStateListener, TelephonyCallback.RegistrationFailedListener, TelephonyCallback.SignalStrengthsListener {
            override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
                _logList.value = listOf(
                    LogData(
                        logType = LogData.LogType.PhysicalChannelConfigLog(
                            configs = configs
                        )
                    )
                ) + _logList.value
            }

            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                _logList.value = listOf(
                    LogData(
                        logType = LogData.LogType.CellInfoLog(
                            cellInfoList = cellInfo
                        )
                    )
                ) + _logList.value
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                _logList.value = listOf(
                    LogData(
                        logType = LogData.LogType.ServiceStateLog(
                            serviceState = serviceState
                        )
                    )
                ) + _logList.value
            }

            override fun onRegistrationFailed(cellIdentity: CellIdentity, chosenPlmn: String, domain: Int, causeCode: Int, additionalCauseCode: Int) {
                _logList.value = listOf(
                    LogData(
                        logType = LogData.LogType.RegistrationFailedLog(
                            cellIdentity = cellIdentity,
                            chosenPlmn = chosenPlmn,
                            domain = domain,
                            causeCode = causeCode,
                            additionalCauseCode = additionalCauseCode
                        )
                    )
                ) + _logList.value
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                _logList.value = listOf(
                    LogData(
                        logType = LogData.LogType.SignalStrengthLog(
                            signalStrength = signalStrength
                        )
                    )
                ) + _logList.value
            }
        }

        callback.init(context.mainExecutor)
        telephonyRegistry.listenWithEventList(
            true,
            true,
            defaultSubscriptionId,
            telephony.currentPackageName,
            context.attributionTag,
            callback.callback,
            intArrayOf(
                TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED,
                TelephonyCallback.EVENT_CELL_INFO_CHANGED,
                TelephonyCallback.EVENT_SERVICE_STATE_CHANGED,
                TelephonyCallback.EVENT_REGISTRATION_FAILURE,
                // なんで２つあるの？
                TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED,
                TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED
            ),
            true
        )

        addCloseable {
            telephonyRegistry.listenWithEventList(
                false,
                false,
                defaultSubscriptionId,
                telephony.currentPackageName,
                context.attributionTag,
                callback.callback,
                intArrayOf(0),
                false
            )
        }
    }
}
package io.github.takusan23.shizukudensokuapilogger

import android.app.Application
import android.app.IActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.os.ServiceManager
import android.telephony.AccessNetworkConstants.AccessNetworkType
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.ICellInfoCallback
import android.telephony.NetworkScan
import android.telephony.NetworkScanRequest
import android.telephony.PhoneCapability
import android.telephony.PhysicalChannelConfig
import android.telephony.RadioAccessSpecifier
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.TelephonyScanManager
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
import kotlin.time.Duration.Companion.seconds

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
        startPollingCellInfo()
        startPollingNetworkScan()
    }

    fun addFilter(filterType: FilterType) {
        _currentFilter.value += filterType
    }

    fun removeFilter(filterType: FilterType) {
        _currentFilter.value -= filterType
    }

    private fun startPollingNetworkScan() {
        val mLooper = HandlerThread("startNetworkScan").apply { start() }
        val mHandler = object : Handler(mLooper.looper) {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)

                when (msg.what) {
                    TelephonyScanManager.CALLBACK_RESTRICTED_SCAN_RESULTS,
                    TelephonyScanManager.CALLBACK_SCAN_RESULTS -> {
                        runCatching {
                            val bundle = msg.data
                            val parcelableArray = bundle.getParcelableArray("scanResult") ?: return
                            val cellInfo = parcelableArray.indices.map { i -> parcelableArray[i] as CellInfo }

                            _logList.value = listOf(
                                LogData(
                                    logType = LogData.LogType.NetworkScanLog(
                                        status = LogData.NetworkScanStatus.CALLBACK_SCAN_COMPLETE,
                                        cellInfoList = cellInfo
                                    )
                                )
                            ) + _logList.value
                        }
                    }

                    TelephonyScanManager.CALLBACK_SCAN_ERROR -> {
                        val errorCode = msg.arg1

                        _logList.value = listOf(
                            LogData(
                                logType = LogData.LogType.NetworkScanLog(
                                    status = LogData.NetworkScanStatus.CALLBACK_SCAN_ERROR,
                                    cellInfoList = null
                                )
                            )
                        ) + _logList.value
                    }

                    TelephonyScanManager.CALLBACK_SCAN_COMPLETE -> {
                        _logList.value = listOf(
                            LogData(
                                logType = LogData.LogType.NetworkScanLog(
                                    status = LogData.NetworkScanStatus.CALLBACK_SCAN_COMPLETE,
                                    cellInfoList = null
                                )
                            )
                        ) + _logList.value
                    }
                }
            }
        }
        val mMessenger = Messenger(mHandler)

        viewModelScope.launch {
            while (true) {
                val scan = telephony.requestNetworkScan(
                    defaultSubscriptionId,
                    true,
                    createNetworkScan(),
                    mMessenger,
                    Binder(),
                    telephony.currentPackageName,
                    context.attributionTag
                ).let { int -> NetworkScan(int, defaultSubscriptionId) }

                try {
                    delay(300.seconds)
                } finally {
                    scan.stopScan()
                }
            }
        }
    }

    /** Create network scan for allowed network types. */
    private fun createNetworkScan(): NetworkScanRequest {

        fun hasNrSaCapability(): Boolean {
            val phoneCapability = telephony.phoneCapability
            return PhoneCapability.DEVICE_NR_CAPABILITY_SA in phoneCapability.deviceNrCapabilities
        }

        fun getAllowedNetworkTypes(): List<Int> {
            val networkTypeBitmap3gpp = telephony.getAllowedNetworkTypesBitmask(defaultSubscriptionId).toLong() and TelephonyManager.NETWORK_STANDARDS_FAMILY_BITMASK_3GPP
            return buildList {
                // If the allowed network types are unknown or if they are of the right class, scan for
                // them; otherwise, skip them to save scan time and prevent users from being shown
                // networks that they can't connect to.
                if (networkTypeBitmap3gpp == 0L || networkTypeBitmap3gpp and TelephonyManager.NETWORK_CLASS_BITMASK_2G != 0L) {
                    add(AccessNetworkType.GERAN)
                }
                if (networkTypeBitmap3gpp == 0L || networkTypeBitmap3gpp and TelephonyManager.NETWORK_CLASS_BITMASK_3G != 0L) {
                    add(AccessNetworkType.UTRAN)
                }
                if (networkTypeBitmap3gpp == 0L || networkTypeBitmap3gpp and TelephonyManager.NETWORK_CLASS_BITMASK_4G != 0L) {
                    add(AccessNetworkType.EUTRAN)
                }                // If a device supports 5G stand-alone then the code below should be re-enabled; however
                // a device supporting only non-standalone mode cannot perform PLMN selection and camp
                // on a 5G network, which means that it shouldn't scan for 5G at the expense of battery
                // as part of the manual network selection process.
                //
                if (networkTypeBitmap3gpp == 0L || (networkTypeBitmap3gpp and TelephonyManager.NETWORK_CLASS_BITMASK_5G != 0L && hasNrSaCapability())) {
                    add(AccessNetworkType.NGRAN)
                }
            }
        }

        val allowedNetworkTypes = getAllowedNetworkTypes()
        val radioAccessSpecifiers = allowedNetworkTypes
            .map { RadioAccessSpecifier(it, null, null) }
            .toTypedArray()
        return NetworkScanRequest(
            NetworkScanRequest.SCAN_TYPE_ONE_SHOT,
            radioAccessSpecifiers,
            NetworkScanRequest.MIN_SEARCH_PERIODICITY_SEC, // one shot, not used
            300, // config_network_scan_helper_max_search_time_sec
            true,
            3, // INCREMENTAL_RESULTS_PERIODICITY_SEC
            null,
        )
    }

    private fun startPollingCellInfo() {
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
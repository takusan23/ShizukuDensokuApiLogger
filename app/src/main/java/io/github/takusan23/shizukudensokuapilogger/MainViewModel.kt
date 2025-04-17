package io.github.takusan23.shizukudensokuapilogger

import android.app.Application
import android.app.IActivityManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.os.ServiceManager
import android.provider.MediaStore
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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
import com.android.internal.telephony.ITelephonyRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _logList = MutableStateFlow(emptyList<LogData>())
    private val _currentFilter = MutableStateFlow(FilterType.entries.toList())

    private val _isNotificationMaybeFakeNetwork = MutableStateFlow(true)
    private val _maybeFakeNetworkDialogMessage = MutableStateFlow<String?>(null)

    val currentFilter = _currentFilter.asStateFlow()
    val logList = combine(
        _logList,
        _currentFilter,
        ::Pair
    ).map { (logList, filter) ->
        logList.filter { log -> log.logType.convertFilterType in filter }
    }

    val isNotificationMaybeFakeNetwork = _isNotificationMaybeFakeNetwork.asStateFlow()
    val maybeFakeNetworkDialogMessage = _maybeFakeNetworkDialogMessage.asStateFlow()

    init {
        registerBroadcast()

        // SIM カードの枚数分
        subscription.getActiveSubscriptionInfoList(telephony.currentPackageName, context.attributionTag, true).forEach { subscriptionInfo ->

            // 電波関連の API を Kotlin Flow にして merge
            viewModelScope.launch {
                listOf(
                    listen(subscriptionInfo.subscriptionId),
                    startPollingCellInfo(subscriptionInfo.subscriptionId),
                    startPollingNetworkScan(subscriptionInfo.subscriptionId)
                ).merge().collect { logData ->

                    // 不審な MCC があれば通知
                    // "440" が日本の MCC 番号
                    if (_isNotificationMaybeFakeNetwork.value) {

                        // ネットワークスキャンとセル情報から
                        val cellInfoList = when (val logType = logData.logType) {
                            is LogData.LogType.NetworkScanLog -> logType.cellInfoList
                            is LogData.LogType.CellInfoLog -> logType.cellInfoList
                            else -> null
                        } ?: emptyList()

                        // 440/441 以外の MCC 検出
                        val japanMcc = listOf("440", "441")
                        if (cellInfoList.mapNotNull { it.cellIdentity.mccString }.any { it !in japanMcc }) {
                            _maybeFakeNetworkDialogMessage.value += "440 以外の MCC を検出しました"
                        }

                        // 4G/5G 以外を検出
                        val allowList = listOf(NetworkGeneration.LTE, NetworkGeneration.NR)
                        if (cellInfoList.map { it.cellIdentity.generation }.any { it !in allowList }) {
                            _maybeFakeNetworkDialogMessage.value += "4G/5G 以外を検出しました"
                        }
                    }

                    // ログ履歴追加
                    _logList.value = listOf(logData) + _logList.value
                }
            }
        }
    }

    fun addFilter(filterType: FilterType) {
        _currentFilter.value += filterType
    }

    fun removeFilter(filterType: FilterType) {
        _currentFilter.value -= filterType
    }

    fun saveFile() {
        viewModelScope.launch(Dispatchers.IO) {
            val text = _logList.value.toString()

            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "Shizuku電波測定APIロガー_${System.currentTimeMillis()}.txt")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents")
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values) ?: return@launch
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(text)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteLog() {
        _logList.value = emptyList()
    }

    fun closeDialog() {
        _maybeFakeNetworkDialogMessage.value = null
    }

    fun setNotification(isEnable: Boolean) {
        _isNotificationMaybeFakeNetwork.value = isEnable
    }

    private fun startPollingNetworkScan(subscriptionId: Int) = callbackFlow {
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

                            trySend(
                                LogData(
                                    subscriptionId = subscriptionId,
                                    logType = LogData.LogType.NetworkScanLog(
                                        status = LogData.NetworkScanStatus.CALLBACK_SCAN_RESULTS,
                                        cellInfoList = cellInfo
                                    )
                                )
                            )
                        }
                    }

                    TelephonyScanManager.CALLBACK_SCAN_ERROR -> {
                        val errorCode = msg.arg1

                        trySend(
                            LogData(
                                subscriptionId = subscriptionId,
                                logType = LogData.LogType.NetworkScanLog(
                                    status = LogData.NetworkScanStatus.CALLBACK_SCAN_ERROR,
                                    cellInfoList = null
                                )
                            )
                        )
                    }

                    TelephonyScanManager.CALLBACK_SCAN_COMPLETE -> {
                        trySend(
                            LogData(
                                subscriptionId = subscriptionId,
                                logType = LogData.LogType.NetworkScanLog(
                                    status = LogData.NetworkScanStatus.CALLBACK_SCAN_COMPLETE,
                                    cellInfoList = null
                                )
                            )
                        )
                    }
                }
            }
        }
        val mMessenger = Messenger(mHandler)

        val job = launch {
            while (true) {
                val scan = telephony.requestNetworkScan(
                    subscriptionId,
                    false,
                    createNetworkScan(subscriptionId),
                    mMessenger,
                    Binder(),
                    telephony.currentPackageName,
                    context.attributionTag
                ).let { int -> NetworkScan(int, subscriptionId) }

                try {
                    delay(30.seconds)
                } finally {
                    scan.stopScan()
                }
            }
        }
        awaitClose { job.cancel() }
    }

    /** Create network scan for allowed network types. */
    private fun createNetworkScan(subscriptionId: Int): NetworkScanRequest {

        fun hasNrSaCapability(): Boolean {
            val phoneCapability = telephony.phoneCapability
            return PhoneCapability.DEVICE_NR_CAPABILITY_SA in phoneCapability.deviceNrCapabilities
        }

        fun getAllowedNetworkTypes(): List<Int> {
            val networkTypeBitmap3gpp = telephony.getAllowedNetworkTypesBitmask(subscriptionId).toLong() and TelephonyManager.NETWORK_STANDARDS_FAMILY_BITMASK_3GPP
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

    private fun startPollingCellInfo(subscriptionId: Int) = callbackFlow {
        val job = launch {
            while (true) {
                delay(5_000L)

                val cellList = suspendCoroutine<List<CellInfo>?> {
                    telephony.requestCellInfoUpdate(subscriptionId, object : ICellInfoCallback.Stub() {
                        override fun onCellInfo(state: MutableList<CellInfo>?) {
                            it.resume(state)
                        }

                        override fun onError(errorCode: Int, exceptionName: String?, message: String?) {
                            it.resume(emptyList())
                        }
                    }, telephony.currentPackageName, context.attributionTag)
                }

                trySend(
                    LogData(
                        subscriptionId = subscriptionId,
                        logType = LogData.LogType.CellInfoLog(
                            cellInfoList = cellList ?: emptyList()
                        )
                    )
                )
            }
        }
        awaitClose { job.cancel() }
    }

    private fun registerBroadcast() = callbackFlow {
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                context ?: return
                intent ?: return
                val broadcastAction = LogData.BroadcastAction.entries.firstOrNull { it.actionString == intent.action } ?: return
                val extra = intent.extras.let { bundle -> bundle?.keySet()?.associateWith { key -> bundle.get(key).toString() } } ?: emptyMap()
                val subscriptionId = intent.extras?.getInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1) ?: -1

                trySend(
                    LogData(
                        subscriptionId = subscriptionId,
                        logType = LogData.LogType.BroadcastLog(
                            action = broadcastAction,
                            keyValue = extra
                        )
                    )
                )
            }
        }
        val intentFilter = IntentFilter().apply {
            LogData.BroadcastAction.entries.forEach { action ->
                addAction(action.actionString)
            }
        }
        ContextCompat.registerReceiver(context, broadcastReceiver, intentFilter, ContextCompat.RECEIVER_EXPORTED)
        awaitClose { context.unregisterReceiver(broadcastReceiver) }
    }

    private fun listen(subscriptionId: Int) = callbackFlow {
        val callback = object : TelephonyCallback(), TelephonyCallback.PhysicalChannelConfigListener, TelephonyCallback.CellInfoListener, TelephonyCallback.ServiceStateListener, TelephonyCallback.RegistrationFailedListener, TelephonyCallback.SignalStrengthsListener {
            override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
                trySend(
                    LogData(
                        subscriptionId = subscriptionId,
                        logType = LogData.LogType.PhysicalChannelConfigLog(
                            configs = configs
                        )
                    )
                )
            }

            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                trySend(
                    LogData(
                        subscriptionId = subscriptionId,
                        logType = LogData.LogType.CellInfoLog(
                            cellInfoList = cellInfo
                        )
                    )
                )
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                trySend(
                    LogData(
                        subscriptionId = subscriptionId,
                        logType = LogData.LogType.ServiceStateLog(
                            serviceState = serviceState
                        )
                    )
                )
            }

            override fun onRegistrationFailed(cellIdentity: CellIdentity, chosenPlmn: String, domain: Int, causeCode: Int, additionalCauseCode: Int) {
                trySend(
                    LogData(
                        subscriptionId = subscriptionId,
                        logType = LogData.LogType.RegistrationFailedLog(
                            cellIdentity = cellIdentity,
                            chosenPlmn = chosenPlmn,
                            domain = domain,
                            causeCode = causeCode,
                            additionalCauseCode = additionalCauseCode
                        )
                    )
                )
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                trySend(
                    LogData(
                        subscriptionId = subscriptionId,
                        logType = LogData.LogType.SignalStrengthLog(
                            signalStrength = signalStrength
                        )
                    )
                )
            }
        }

        callback.init(context.mainExecutor)
        telephonyRegistry.listenWithEventList(
            true,
            true,
            subscriptionId,
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

        awaitClose {
            telephonyRegistry.listenWithEventList(
                false,
                false,
                subscriptionId,
                telephony.currentPackageName,
                context.attributionTag,
                callback.callback,
                intArrayOf(0),
                false
            )
        }
    }
}
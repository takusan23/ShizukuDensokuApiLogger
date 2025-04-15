package io.github.takusan23.shizukudensokuapilogger

import android.annotation.SuppressLint
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat

// TODO 保存機能
// TODO 3GPP のエラーをマッピングする
// TODO 簡略表示

private val simpleDateFormat = SimpleDateFormat("HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val currentFilter = viewModel.currentFilter.collectAsStateWithLifecycle(initialValue = emptyList())
    val logList = viewModel.logList.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.app_name)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            contentPadding = innerPadding
        ) {

            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    FilterType.entries.forEach { type ->
                        val isSelected = type in currentFilter.value
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (type in currentFilter.value) {
                                    viewModel.removeFilter(type)
                                } else {
                                    viewModel.addFilter(type)
                                }
                            },
                            label = { Text(text = type.name) },
                            leadingIcon = if (isSelected) {
                                { Icon(imageVector = Icons.Default.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }

            items(logList.value, key = { it.hashCode() }) { log ->
                LogItem(logData = log)
                HorizontalDivider()
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun LogItem(
    modifier: Modifier = Modifier,
    logData: LogData
) {
    val isExpanded = remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = isExpanded.value, onValueChange = { isExpanded.value = it })
    ) {
        Text(text = "${logData.logType.convertFilterType} Time: ${simpleDateFormat.format(logData.time)}")

        when (val type = logData.logType) {
            is LogData.LogType.BroadcastLog -> {
                Text(text = "action=${type.action}, extraKeys=${type.keyValue.entries}")
            }

            is LogData.LogType.PhysicalChannelConfigLog -> {
                val bandList = type.configs.map {
                    "{generation=${TelephonyManager.getNetworkTypeName(it.networkType)}, band=${it.band}}, pci=${it.physicalCellId}}"
                }

                Text(text = bandList.toString())
            }

            is LogData.LogType.CellInfoLog -> {
                val plmnBandList = type.cellInfoList.map {
                    "{generation=${it.cellIdentity.generation}, plmn=${it.cellIdentity.plmn}, band=${it.cellIdentity.band.toString()}, pci=${it.cellIdentity.cellId}}"
                }

                Text(text = plmnBandList.toString())
            }

            is LogData.LogType.RegistrationFailedLog -> {
                val causeCodeText = when (type.cellIdentity.generation) {
                    NetworkGeneration.LTE -> ErrorResolve3gpp.resolveCauseFromTS24501(type.causeCode)
                    NetworkGeneration.NR -> ErrorResolve3gpp.resolveCauseFromTs24301(type.causeCode)
                    else -> null
                }
                val shortText = listOf(
                    "${type.cellIdentity.generation}",
                    "PLMN=${type.chosenPlmn}",
                    "causeCode=${type.causeCode}($causeCodeText)",
                    "additionalCauseCode=${type.additionalCauseCode}"
                ).joinToString()

                Text(text = shortText)
            }

            is LogData.LogType.ServiceStateLog -> {
                val rejectCause = type.serviceState.networkRegistrationInfoList
                    .map { ErrorResolve3gpp.resolveCauseFromTs24501AnnexA(it.rejectCause) }
                val shortText = listOf(
                    "name=${type.serviceState.operatorAlphaLongRaw}",
                    "state=${type.serviceState.state}(${ServiceState.rilServiceStateToString(type.serviceState.state)})",
                    "bandwidths=${type.serviceState.cellBandwidths.toList()}",
                    "rejectCause=$rejectCause"
                ).joinToString()

                Text(text = shortText)
            }

            is LogData.LogType.SignalStrengthLog -> {
                Text(text = type.signalStrength.cellSignalStrengths.toString())
            }

            is LogData.LogType.NetworkScanLog -> {
                val shortText = type.cellInfoList
                    ?.groupBy { it.cellIdentity.plmn }
                    ?.map { (carrierName, cellInfoList) ->
                        val genList = cellInfoList.map { it.cellIdentity.generation }.distinct()
                        "{generation=$genList, carrierName=$carrierName}"
                    }

                Text(text = "status=${type.status.name}, scanResult=$shortText")
            }
        }

        if (isExpanded.value) {
            Text(text = logData.logType.toString())
        }
    }
}
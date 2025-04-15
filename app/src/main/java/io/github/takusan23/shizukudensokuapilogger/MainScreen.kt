package io.github.takusan23.shizukudensokuapilogger

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
                        FilterChip(
                            selected = type in currentFilter.value,
                            onClick = {
                                if (type in currentFilter.value) {
                                    viewModel.removeFilter(type)
                                } else {
                                    viewModel.addFilter(type)
                                }
                            },
                            label = { Text(text = type.name) }
                        )
                    }
                }
            }

            items(logList.value) { log ->
                LogItem(logData = log)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LogItem(
    modifier: Modifier = Modifier,
    logData: LogData
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "Time: ${simpleDateFormat.format(logData.time)}")

        when (val type = logData.logType) {
            is LogData.LogType.BroadcastLog -> Text(text = type.toString())
            is LogData.LogType.CellInfoLog -> Text(text = type.toString())
            is LogData.LogType.PhysicalChannelConfigLog -> Text(text = type.toString())
            is LogData.LogType.RegistrationFailedLog -> Text(text = type.toString())
            is LogData.LogType.ServiceStateLog -> Text(text = type.toString())
            is LogData.LogType.SignalStrengthLog -> Text(text = type.toString())
            is LogData.LogType.NetworkScanLog -> Text(text = type.toString())
        }
    }
}
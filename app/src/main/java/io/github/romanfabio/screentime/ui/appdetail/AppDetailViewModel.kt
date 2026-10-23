package io.github.romanfabio.screentime.ui.appdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.romanfabio.screentime.data.repo.DashboardRepository
import io.github.romanfabio.screentime.domain.AppDetailData
import io.github.romanfabio.screentime.domain.DashboardRange
import io.github.romanfabio.screentime.ui.dashboard.DashboardParams
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModel(private val repo: DashboardRepository, private val packageName: String) : ViewModel() {

    private val _params = MutableStateFlow(DashboardParams(range = DashboardRange.WEEK))
    val params: StateFlow<DashboardParams> = _params

    val data: StateFlow<AppDetailData?> = combine(_params, repo.changes()) { p, _ -> p }
        .mapLatest { repo.loadAppDetail(packageName, it.range, it.endDate ?: today()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setRange(range: DashboardRange) = _params.update { it.copy(range = range) }

    fun shift(direction: Int) = _params.update { p ->
        val today = today()
        val end = (p.endDate ?: today).plusDays(direction.toLong() * p.range.days)
        p.copy(endDate = if (end >= today) null else end)
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}

package io.github.romanfabio.screentime.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.romanfabio.screentime.data.repo.DashboardRepository
import io.github.romanfabio.screentime.domain.DashboardData
import io.github.romanfabio.screentime.domain.DashboardRange
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** [endDate] is null while following "today", so the screen stays correct across midnight. */
data class DashboardParams(val range: DashboardRange = DashboardRange.TODAY, val endDate: LocalDate? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val repo: DashboardRepository) : ViewModel() {

    private val _params = MutableStateFlow(DashboardParams())
    val params: StateFlow<DashboardParams> = _params

    val data: StateFlow<DashboardData?> = combine(_params, repo.changes()) { p, _ -> p }
        .mapLatest { repo.load(it.range, it.endDate ?: today()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setRange(range: DashboardRange) = _params.update { it.copy(range = range) }

    /** Moves the period back (-1) or forward (+1) by its own length, never beyond today. */
    fun shift(direction: Int) = _params.update { p ->
        val today = today()
        val end = (p.endDate ?: today).plusDays(direction.toLong() * p.range.days)
        p.copy(endDate = if (end >= today) null else end)
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}

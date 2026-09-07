package com.example.opendash.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opendash.data.Expense
import com.example.opendash.data.FuelFillup
import com.example.opendash.data.Himalayan450MaintenanceSchedule
import com.example.opendash.data.MaintenanceItem
import com.example.opendash.data.OfficialMaintenanceSchedule
import com.example.opendash.data.OpenDashCurrency
import com.example.opendash.data.SyncRepository
import com.example.opendash.data.VehicleStore
import com.example.opendash.data.formatCurrencyAmount
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FuelRow(val fill: FuelFillup, val kmpl: Double?)
data class MaintRow(
    val item: MaintenanceItem,
    val remainingKm: Int,
    val remainingDays: Long?,
    val tone: String,
    val officialSchedule: OfficialMaintenanceSchedule?,
)

data class GarageUi(
    val activeVehicleId: String = VehicleStore.DEFAULT_VEHICLE_ID,
    val activeVehicleName: String = "Himalayan 450",
    val odometerKm: Int = 0,
    val fuel: List<FuelRow> = emptyList(),     // newest first; kmpl vs the prior fill
    val maint: List<MaintRow> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val avgKmpl30: Double? = null,
    val avgKmplLast5: Double? = null,
    val spent30: Double = 0.0,
    val litres30: Double = 0.0,
    val fills30: Int = 0,
    val expensesTotal: Double = 0.0,
    val expenses30: Double = 0.0,
)

class GarageViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SyncRepository.get(app)
    private val _ui = MutableStateFlow(GarageUi())
    val ui = _ui.asStateFlow()

    init {
        VehicleStore.init(app)
        reload()
        // Reload whenever local OR synced-from-cloud data changes.
        viewModelScope.launch { repo.revision.collect { reload() } }
        viewModelScope.launch {
            VehicleStore.activeVehicleId.collect { vehicleId ->
                withContext(Dispatchers.IO) { repo.ensureMaintenance(vehicleId) }
                reload()
            }
        }
    }

    private fun reload() = viewModelScope.launch {
        val ui = withContext(Dispatchers.IO) { compute() }
        _ui.value = ui
        // Buzz if a service just crossed into "due" (de-duped inside the notifier).
        withContext(Dispatchers.IO) {
            com.example.opendash.data.MaintenanceNotifier.check(getApplication(), ui.maint.map { it.item }, ui.odometerKm)
        }
    }

    private fun compute(): GarageUi {
        val vehicle = VehicleStore.activeVehicle()
        val fills = repo.fuelFills(vehicle.id)   // highest odometer (newest) first
        val odo = maxOf(repo.odometer(vehicle.id), fills.maxOfOrNull { it.odometerKm } ?: 0)
        val expenses = repo.expenses(vehicle.id)
        val fuelRows = fills.mapIndexed { i, f ->
            val prev = fills.getOrNull(i + 1)   // next-lower odometer fill
            val kmpl = if (prev != null && f.litres > 0 && f.odometerKm > prev.odometerKm)
                (f.odometerKm - prev.odometerKm) / f.litres else null
            FuelRow(f, kmpl)
        }
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val recent = fuelRows.filter { it.fill.dateMs >= cutoff }
        val kmpls = recent.mapNotNull { it.kmpl }
        val maint = repo.maintenanceItems(vehicle.id).map { m ->
            val official = Himalayan450MaintenanceSchedule.forItem(m)
            val remaining = m.lastDoneOdoKm + m.intervalKm - odo
            val intervalMonths = official?.intervalMonths
            val remainingDays = intervalMonths?.let { months ->
                val dueAt = Calendar.getInstance().apply {
                    timeInMillis = m.lastDoneDateMs
                    add(Calendar.MONTH, months)
                }.timeInMillis
                kotlin.math.ceil((dueAt - System.currentTimeMillis()) / 86_400_000.0).toLong()
            }
            val timeWarning = intervalMonths?.let { months ->
                remainingDays?.let { days -> days < months * 30 * 0.25 }
            } == true
            val tone = when {
                remaining < 0 || (remainingDays != null && remainingDays < 0) -> "alert"
                remaining < m.intervalKm * 0.25 || timeWarning -> "warn"
                else -> "ok"
            }
            MaintRow(m, remaining, remainingDays, tone, official)
        }
        return GarageUi(
            activeVehicleId = vehicle.id,
            activeVehicleName = vehicle.title,
            odometerKm = odo,
            fuel = fuelRows,
            maint = maint,
            expenses = expenses,
            avgKmpl30 = kmpls.takeIf { it.isNotEmpty() }?.average(),
            avgKmplLast5 = fuelRows.mapNotNull { it.kmpl }.take(5).takeIf { it.isNotEmpty() }?.average(),
            spent30 = recent.sumOf { it.fill.cost },
            litres30 = recent.sumOf { it.fill.litres },
            fills30 = recent.size,
            expensesTotal = expenses.sumOf { it.amount },
            expenses30 = expenses.filter { it.dateMs >= cutoff }.sumOf { it.amount },
        )
    }

    fun addFuel(litres: Double, cost: Double, odometerKm: Int, location: String) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addFuel(litres, cost, odometerKm, location) } }

    fun deleteFuel(fill: FuelFillup) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteFuel(fill) } }

    fun addExpense(category: String, amount: Double, note: String, dateMs: Long = System.currentTimeMillis()) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addExpense(category, amount, note, dateMs) } }

    fun deleteExpense(expense: Expense) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteExpense(expense) } }

    fun markServiceDone(item: MaintenanceItem, odoKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.markServiceDone(item, odoKm) } }

    fun logService(item: MaintenanceItem, odoKm: Int, intervalKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.logService(item, odoKm, intervalKm) } }

    fun addService(name: String, iconKey: String, intervalKm: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.addMaintenance(name, iconKey, intervalKm, repo.odometer()) } }

    fun deleteService(item: MaintenanceItem) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.deleteMaintenance(item) } }

    fun setOdometer(km: Int) =
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.setOdometer(km) } }

    suspend fun exportExpensesCsv(
        expenses: List<Expense>? = null,
        periodLabel: String = "all-time",
        currency: OpenDashCurrency = OpenDashCurrency.INR,
    ): File = withContext(Dispatchers.IO) {
        val selected = expenses ?: repo.expenses()
        val file = exportFile("opendash-expenses-$periodLabel.csv")
        file.writeText(buildString {
            appendLine("Date,Category,Amount_${currency.code},Note")
            selected.forEach { e ->
                appendLine(
                    listOf(
                        exportDate(e.dateMs),
                        e.category,
                        "%.2f".format(Locale.US, e.amount),
                        e.note,
                    ).joinToString(",") { csvCell(it) }
                )
            }
        })
        file
    }

    suspend fun exportExpensesDoc(
        expenses: List<Expense>? = null,
        periodLabel: String = "All time",
        currency: OpenDashCurrency = OpenDashCurrency.INR,
    ): File = withContext(Dispatchers.IO) {
        val selected = expenses ?: repo.expenses()
        val file = exportFile("opendash-expenses-${periodLabel.lowercase(Locale.US).replace(' ', '-')}.doc")
        file.writeText(
            buildString {
                appendLine("<html><head><meta charset=\"utf-8\"><title>OpenDash Expenses</title></head><body>")
                appendLine("<h1>OpenDash Expenses - ${html(periodLabel)}</h1>")
                appendLine("<p>Total: ${html(formatCurrencyAmount(selected.sumOf { it.amount }, currency, 2))}</p>")
                appendLine("<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\">")
                appendLine("<tr><th>Date</th><th>Category</th><th>Amount</th><th>Note</th></tr>")
                selected.forEach { e ->
                    appendLine("<tr><td>${exportDate(e.dateMs)}</td><td>${html(e.category)}</td><td>${html(formatCurrencyAmount(e.amount, currency, 2))}</td><td>${html(e.note)}</td></tr>")
                }
                appendLine("</table></body></html>")
            }
        )
        file
    }

    private fun exportFile(name: String): File {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        return File(dir, name)
    }

    private fun exportDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    private fun csvCell(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

    private fun html(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

package com.example.opendash.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opendash.data.CurrencySettings
import com.example.opendash.data.Expense
import com.example.opendash.data.OpenDashCurrency
import com.example.opendash.data.formatCurrencyAmount
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.BtnSize
import com.example.opendash.ui.components.BtnVariant
import com.example.opendash.ui.components.OpenDashBtn
import com.example.opendash.ui.components.OpenDashCard
import com.example.opendash.ui.components.OpenDashDivider
import com.example.opendash.ui.components.ScreenHeader
import com.example.opendash.ui.theme.GeistFamily
import com.example.opendash.ui.theme.GeistMonoFamily
import com.example.opendash.viewmodel.GarageViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ExpensesScreen(vm: GarageViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    remember(ctx) {
        CurrencySettings.init(ctx)
        true
    }
    val currency by CurrencySettings.currency.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf("All Expenses") }
    val periods = remember { expensePeriods() }
    var selectedPeriod by remember { mutableStateOf(periods.first()) }
    var showAdd by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    val categories = listOf("All Expenses", "Fuel", "Repairs", "Accessories", "Riding Gear", "Food", "Stay", "Transport", "Others")
    val periodExpenses = ui.expenses.filter { selectedPeriod.includes(it.dateMs) }
    val shown = if (selected == "All Expenses") periodExpenses else periodExpenses.filter { it.category == selected }
    val total = shown.sumOf { it.amount }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
                .padding(bottom = 96.dp),
        ) {
            ScreenHeader(title = "My Expenses")

            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Total", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(formatCurrencyAmount(total, currency), color = MaterialTheme.colorScheme.primary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    }
                    ExpensePeriodSelector(
                        periods = periods,
                        selected = selectedPeriod,
                        onSelect = { selectedPeriod = it },
                        modifier = Modifier.weight(1f),
                    )
                    OpenDashBtn(
                        "Share",
                        onClick = { showShare = true },
                        icon = OpenDashIcons.Share,
                        variant = BtnVariant.Ghost,
                        size = BtnSize.Sm,
                        enabled = shown.isNotEmpty(),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            ) {
                categories.forEach { category ->
                    ExpenseFilterChip(
                        label = category,
                        selected = selected == category,
                        color = categoryColor(category),
                        onClick = { selected = category },
                    )
                }
            }

            OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
                if (shown.isEmpty()) {
                    Text("No expenses for this category and period.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                }
                shown.forEachIndexed { i, expense ->
                    if (i > 0) OpenDashDivider(Modifier.padding(horizontal = 4.dp))
                    ExpenseListRow(expense, currency)
                }
            }
        }

        OpenDashBtn(
            "Add expense",
            onClick = { showAdd = true },
            icon = OpenDashIcons.Plus,
            variant = BtnVariant.Primary,
            size = BtnSize.Md,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(18.dp),
        )
    }

    if (showAdd) AddExpenseScreenDialog(
        vehicleName = ui.activeVehicleName,
        currency = currency,
        onAdd = { category, amount, note, dateMs -> vm.addExpense(category, amount, note, dateMs); showAdd = false },
        onDismiss = { showAdd = false },
    )
    if (showShare) ExpenseExportSheet(
        periodLabel = selectedPeriod.label,
        onDismiss = { showShare = false },
        onExcel = {
            showShare = false
            scope.launch { shareExpenseFile(ctx, vm.exportExpensesCsv(shown, selectedPeriod.fileLabel, currency), "text/csv") }
        },
        onDoc = {
            showShare = false
            scope.launch { shareExpenseFile(ctx, vm.exportExpensesDoc(shown, selectedPeriod.label, currency), "application/msword") }
        },
    )
}

@Composable
private fun ExpenseFilterChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (label == "All Expenses") MaterialTheme.colorScheme.onSurface else color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontFamily = GeistFamily)
    }
}

@Composable
private fun ExpenseListRow(expense: Expense, currency: OpenDashCurrency) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(expense.category, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontFamily = GeistFamily)
            Text(expense.note.ifBlank { dfExpense.format(Date(expense.dateMs)) }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatCurrencyAmount(expense.amount, currency), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
            Text(dfExpense.format(Date(expense.dateMs)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun AddExpenseScreenDialog(
    vehicleName: String,
    currency: OpenDashCurrency,
    onAdd: (String, Double, String, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val categories = listOf("Fuel", "Repairs", "Accessories", "Riding Gear", "Food", "Stay", "Transport", "Others")
    var category by remember { mutableStateOf(categories.first()) }
    var date by remember { mutableStateOf(dfExpense.format(Date())) }
    var time by remember { mutableStateOf(dfExpenseTime.format(Date())) }
    var amount by remember { mutableStateOf("") }
    val vehicle = vehicleName
    var odometer by remember { mutableStateOf("") }
    var fuelQty by remember { mutableStateOf("") }
    var fullTank by remember { mutableStateOf(false) }
    var storeName by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var partsReplaced by remember { mutableStateOf(category != "Repairs") }
    val valid = amount.toDoubleOrNull()?.let { it > 0.0 } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onAdd(
                        category,
                        amount.toDouble(),
                        buildExpenseNote(
                            category = category,
                            date = date,
                            time = time,
                            vehicle = vehicle,
                            odometer = odometer,
                            fuelQty = fuelQty,
                            fullTank = fullTank,
                            storeName = storeName,
                            details = details,
                            description = description,
                            partsReplaced = partsReplaced,
                        ),
                        parseExpenseDateTime(date, time) ?: System.currentTimeMillis(),
                    )
                },
            ) {
                Text("Add", color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        title = { Text("${category} expense") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    categories.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { option ->
                                OpenDashBtn(
                                    option,
                                    onClick = { category = option },
                                    variant = if (category == option) BtnVariant.Primary else BtnVariant.Secondary,
                                    size = BtnSize.Sm,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    ExpenseField(date, { date = it }, "Date", Modifier.weight(1f))
                    ExpenseField(time, { time = it }, "Time", Modifier.weight(1f))
                }

                if (category == "Fuel") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        OpenDashBtn(
                            if (fullTank) "Full tank" else "Partial tank",
                            onClick = { fullTank = !fullTank },
                            icon = OpenDashIcons.Check,
                            variant = if (fullTank) BtnVariant.Primary else BtnVariant.Secondary,
                            size = BtnSize.Sm,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text("Motorcycle", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(vehicle, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        ExpenseField(odometer, { odometer = it }, "Odometer (km)", Modifier.weight(1f), KeyboardType.Number)
                        ExpenseField(fuelQty, { fuelQty = it }, "Fuel quantity", Modifier.weight(1f), KeyboardType.Decimal)
                    }
                    ExpenseField(storeName, { storeName = it }, "Fuel station name (optional)")
                } else {
                    val storeLabel = when (category) {
                        "Repairs", "Accessories", "Riding Gear" -> "Store name (optional)"
                        else -> "Shop / establishment name (optional)"
                    }
                    if (category == "Repairs") {
                        Text("Did you replace any parts?", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            OpenDashBtn("Yes", onClick = { partsReplaced = true }, icon = OpenDashIcons.Check, variant = if (partsReplaced) BtnVariant.Primary else BtnVariant.Secondary, size = BtnSize.Sm, modifier = Modifier.weight(1f))
                            OpenDashBtn("No", onClick = { partsReplaced = false }, icon = OpenDashIcons.Check, variant = if (!partsReplaced) BtnVariant.Primary else BtnVariant.Secondary, size = BtnSize.Sm, modifier = Modifier.weight(1f))
                        }
                    }
                    ExpenseField(storeName, { storeName = it }, storeLabel)
                    when (category) {
                        "Repairs" -> ExpenseField(details, { details = it }, "Parts replaced (optional)")
                        "Accessories" -> ExpenseField(details, { details = it }, "Accessories (optional)")
                        "Riding Gear" -> ExpenseField(details, { details = it }, "Riding gear (optional)")
                    }
                }

                ExpenseField(amount, { amount = it }, "Amount (${currency.symbol})", keyboardType = KeyboardType.Decimal)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(500) },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 2,
                )
                Text("${description.length}/500", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
                OpenDashBtn(
                    "Attach bill",
                    onClick = { },
                    icon = OpenDashIcons.Plus,
                    variant = BtnVariant.Ghost,
                    size = BtnSize.Sm,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
    )
}

private val dfExpense = SimpleDateFormat("d-MMM-yyyy", Locale.getDefault())
private val dfExpenseTime = SimpleDateFormat("hh:mm a", Locale.getDefault())

private fun parseExpenseDateTime(date: String, time: String): Long? =
    runCatching {
        SimpleDateFormat("d-MMM-yyyy hh:mm a", Locale.getDefault()).apply { isLenient = false }
            .parse("${date.trim()} ${time.trim()}")
            ?.time
    }.getOrNull()

@Composable
private fun ExpenseExportSheet(periodLabel: String, onDismiss: () -> Unit, onExcel: () -> Unit, onDoc: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Export $periodLabel", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OpenDashBtn("Excel / CSV", onClick = onExcel, icon = OpenDashIcons.Chart, variant = BtnVariant.Secondary, size = BtnSize.Md, modifier = Modifier.fillMaxWidth())
                OpenDashBtn("Document", onClick = onDoc, icon = OpenDashIcons.Share, variant = BtnVariant.Secondary, size = BtnSize.Md, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

@Composable
private fun ExpenseField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.padding(top = 8.dp),
    )
}

private fun buildExpenseNote(
    category: String,
    date: String,
    time: String,
    vehicle: String,
    odometer: String,
    fuelQty: String,
    fullTank: Boolean,
    storeName: String,
    details: String,
    description: String,
    partsReplaced: Boolean,
): String = buildList {
    add("$date $time")
    if (category == "Fuel") {
        if (vehicle.isNotBlank()) add("Vehicle: ${vehicle.trim()}")
        if (odometer.isNotBlank()) add("Odometer: ${odometer.trim()} km")
        if (fuelQty.isNotBlank()) add("Fuel: ${fuelQty.trim()} L")
        add(if (fullTank) "Full tank" else "Partial tank")
    }
    if (category == "Repairs") add(if (partsReplaced) "Parts replaced" else "No parts replaced")
    if (storeName.isNotBlank()) add("Store: ${storeName.trim()}")
    if (details.isNotBlank()) add(details.trim())
    if (description.isNotBlank()) add(description.trim())
}.joinToString(" · ")

@Composable
private fun categoryColor(category: String): Color = when (category) {
    "Fuel" -> MaterialTheme.colorScheme.primary
    "Repairs" -> Color(0xFFB95A68)
    "Accessories" -> Color(0xFF7EA7C8)
    "Riding Gear" -> Color(0xFFA88FD8)
    "Food" -> Color(0xFFE2A85C)
    "Stay" -> Color(0xFF89B985)
    "Transport" -> Color(0xFF9BB3D9)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private data class ExpensePeriod(
    val label: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
) {
    val fileLabel: String get() = label.lowercase(Locale.US).replace(' ', '-')
    fun includes(timestamp: Long): Boolean =
        (startMs == null || timestamp >= startMs) && (endMs == null || timestamp < endMs)
}

private fun expensePeriods(): List<ExpensePeriod> {
    val now = Calendar.getInstance()
    val year = now.get(Calendar.YEAR)
    val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
    return listOf(ExpensePeriod("All time")) + (0..11).map { month ->
        val start = Calendar.getInstance().apply {
            clear()
            set(year, month, 1, 0, 0, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        ExpensePeriod("${monthFormat.format(start.time)} $year", start.timeInMillis, end.timeInMillis)
    }
}

@Composable
private fun ExpensePeriodSelector(
    periods: List<ExpensePeriod>,
    selected: ExpensePeriod,
    onSelect: (ExpensePeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                .clickable { expanded = true }
                .padding(horizontal = 9.dp, vertical = 9.dp),
        ) {
            Icon(OpenDashIcons.Cal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(selected.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 1)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            periods.forEach { period ->
                DropdownMenuItem(
                    text = { Text(period.label, color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        onSelect(period)
                        expanded = false
                    },
                    trailingIcon = if (period == selected) {
                        { Icon(OpenDashIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
        }
    }
}

private fun shareExpenseFile(context: android.content.Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export expenses"))
}

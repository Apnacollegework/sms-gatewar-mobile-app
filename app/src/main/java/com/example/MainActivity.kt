package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.SmsLogEntity
import com.example.ui.GatewayViewModel
import com.example.ui.GatewayViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val factory = remember { GatewayViewModelFactory(context.applicationContext) }
                val vm: GatewayViewModel = viewModel(factory = factory)

                DisposableEffect(Unit) {
                    vm.refreshMetrics()
                    onDispose {}
                }

                GatewayAppContent(vm = vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayAppContent(vm: GatewayViewModel) {
    val context = LocalContext.current
    val systemLogs by vm.smsLogs.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Launcher for critical permissions
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        vm.runSystemDiagnosticChecks()
        val text = if (isGranted) "SMS Permission Granted." else "SMS Permission Rejected!"
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        vm.runSystemDiagnosticChecks()
    }

    LaunchedEffect(Unit) {
        vm.runSystemDiagnosticChecks()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Gateway icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "OTP SMS GATEWAY",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.refreshMetrics() },
                        modifier = Modifier.testTag("refresh_action")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Manual sync metrics")
                    }
                    if (vm.isPaired) {
                        IconButton(
                            onClick = { vm.unpairDevice() },
                            modifier = Modifier.testTag("logout_action")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Logout/Unpair controller", tint = Color(0xFFFF5252))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Status Hub") },
                    label = { Text("Hub") },
                    modifier = Modifier.testTag("tab_hub")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Build, contentDescription = "Device Registration Setup") },
                    label = { Text("Pairing") },
                    modifier = Modifier.testTag("tab_pairing")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.AutoMirrored.Default.List, contentDescription = "Activity logs list") },
                    label = { Text("Logs") },
                    modifier = Modifier.testTag("tab_logs")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Setup preferences") },
                    label = { Text("Settings") },
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> StatusHubTab(
                    vm = vm,
                    requestSmsPermission = { smsPermissionLauncher.launch(Manifest.permission.SEND_SMS) },
                    requestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                1 -> SetupGatewayTab(vm = vm)
                2 -> ActivityLogsTab(logs = systemLogs, vm = vm)
                3 -> ConfigurationTab(vm = vm)
            }
        }
    }
}

@Composable
fun StatusHubTab(
    vm: GatewayViewModel,
    requestSmsPermission: () -> Unit,
    requestNotifications: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Pairing Overlay status
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (vm.isPaired) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusIcon = if (vm.isPaired) Icons.Default.CheckCircle else Icons.Default.Warning
                    val statusText = if (vm.isPaired) "Device Connected & Paired" else "Pending Setup (Device Unpaired)"
                    val subText = if (vm.isPaired) "Listening to incoming cloud OTP commands..." else "Please configure pairing details under 'Pairing' tab"

                    Icon(
                        imageVector = statusIcon,
                        contentDescription = "Connection badge",
                        tint = if (vm.isPaired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(text = statusText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Text(text = subText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Stats Row Widget
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Success Stat
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "TODAY SENT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${vm.todaySent}",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Quota: ${vm.maxSmsPerDay} / day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }

                // Failed Stat
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "TODAY FAILED", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${vm.todayFailed}",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black,
                            color = if (vm.todayFailed > 0) Color(0xFFFF5252) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Spam block active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }

        // Active State Controller
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Gateway Transmission Engine", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (vm.isGatewayServiceActive) "Active persistent service forwarding OTP logs" else "Forwarding dispatcher is paused",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vm.isGatewayServiceActive,
                            onCheckedChange = { active ->
                                if (!vm.isPaired && active) {
                                    Toast.makeText(context, "Pair the device first!", Toast.LENGTH_SHORT).show()
                                } else {
                                    vm.toggleGatewayService(active)
                                    Toast.makeText(context, if (active) "Foreground active!" else "Foreground stopped", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("engine_switch")
                        )
                    }
                }
            }
        }

        // System Diagnostic Grid
        item {
            Text(
                text = "SYSTEM ENGINE DIAGNOSTICS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // Checker checklist items
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // SMS Permission
                DiagnosticRow(
                    title = "SEND_SMS Permission",
                    isPass = vm.hasSmsPermission,
                    warningText = "Tap to grant mandatory messaging access.",
                    onClick = { requestSmsPermission() }
                )

                // Sim Card Indicator
                DiagnosticRow(
                    title = "Carrier Network SIM State",
                    isPass = vm.isSimCardReady,
                    warningText = "SIM absent. Insert cellular SIM to forward SMS.",
                    onClick = {
                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                        context.startActivity(intent)
                    }
                )

                // Battery Optimizations
                DiagnosticRow(
                    title = "Battery Saving Excluded",
                    isPass = vm.isBatteryOptimizationDisabled,
                    warningText = "Exclude app from battery saving to ensure zero OTP delay.",
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Please configure in Settings -> App Battery optimization.", Toast.LENGTH_LONG).show()
                        }
                    }
                )

                // Notification warning
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    DiagnosticRow(
                        title = "Notifications Enabled",
                        isPass = vm.hasNotificationPermission,
                        warningText = "Tap to enable required Android 13+ status alerts.",
                        onClick = { requestNotifications() }
                    )
                }

                // SMS Compatibility
                DiagnosticRow(
                    title = "Cellular Radio Capability",
                    isPass = vm.isSmsCompatible,
                    warningText = "Warning: This hardware lacks built-in cellular capability.",
                    onClick = {}
                )
            }
        }

        // Test SMS Dispatcher Card Option
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "TEST MESSAGE DISPATCHER",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = "Verify that outbound SIM routing and receipt confirmation loops behave optimally by executing a local test transmission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    var testTargetPhone by remember { mutableStateOf("") }
                    val predefinedMessages = listOf(
                        "Your verification OTP code: 582490.",
                        "Gateway Check: Dynamic connection diagnostic successful.",
                        "SMS Dispatch Status: Cellular transmission confirmed.",
                        "System check complete: Zero cellular delay."
                    )
                    var selectedPredefinedMessage by remember { mutableStateOf(predefinedMessages[0]) }

                    OutlinedTextField(
                        value = testTargetPhone,
                        onValueChange = { testTargetPhone = it },
                        label = { Text("Recipient Phone Number") },
                        placeholder = { Text("e.g. +919876543210") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("test_phone_number_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background
                        ),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) }
                    )

                    Text(
                        text = "Select Predefined Message Template:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        predefinedMessages.forEach { msg ->
                            val isSelected = selectedPredefinedMessage == msg
                            val containerCol = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            val borderCol = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(containerCol)
                                    .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                                    .clickable { selectedPredefinedMessage = msg }
                                    .padding(vertical = 4.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedPredefinedMessage = msg }
                                )
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (testTargetPhone.isBlank()) {
                                Toast.makeText(context, "Recipient details cannot be empty!", Toast.LENGTH_SHORT).show()
                            } else {
                                vm.sendTestSms(testTargetPhone, selectedPredefinedMessage)
                                Toast.makeText(context, "Local test dispatch triggered successfully!", Toast.LENGTH_SHORT).show()
                                vm.refreshMetrics()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("send_test_sms_button"),
                        enabled = testTargetPhone.isNotBlank() && vm.hasSmsPermission
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("DISPATCH CELLULAR TEST")
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticRow(
    title: String,
    isPass: Boolean,
    warningText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isPass) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = "Status",
            tint = if (isPass) Color(0xFF4CAF50) else Color(0xFFFF5252),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (!isPass) {
                Text(
                    text = warningText,
                    fontSize = 12.sp,
                    color = Color(0xFFFF8A80),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (!isPass) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Fix check",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun SetupGatewayTab(vm: GatewayViewModel) {
    val context = LocalContext.current
    var isPairingProgress by remember { mutableStateOf(false) }

    var showSimSelectionDialog by remember { mutableStateOf(false) }
    var showManualPhoneDialog by remember { mutableStateOf(false) }
    var showFcmErrorDialog by remember { mutableStateOf(false) }
    var detectedPhoneNumbers by remember { mutableStateOf<List<String>>(emptyList()) }

    // Helper to start the pairing process on the background
    val initiateAutoPairing: (String) -> Unit = { phoneNumberPicked ->
        if (vm.fcmToken.length < 20) {
            showFcmErrorDialog = true
        } else {
            isPairingProgress = true
            vm.phoneNumber = phoneNumberPicked
            vm.pairDevice { success ->
                isPairingProgress = false
                if (success) {
                    Toast.makeText(context, "Pairing handshake successful. Node is active!", Toast.LENGTH_LONG).show()
                } else {
                    val errorMsg = vm.lastPairingError ?: "Handshake refused. Check server settings."
                    Toast.makeText(context, "Handshake failed: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Helper to check SIM cards numbers
    val checkSimAndInitiatePairing: () -> Unit = {
        val detected = vm.getActiveSubscriptionPhoneNumbers()
        if (detected.size >= 2) {
            detectedPhoneNumbers = detected
            showSimSelectionDialog = true
        } else if (detected.size == 1) {
            initiateAutoPairing(detected[0])
        } else {
            showManualPhoneDialog = true
        }
    }

    // Launcher for auto-detecting phone number permissions right after QR payload is parsed
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            checkSimAndInitiatePairing()
        } else {
            Toast.makeText(context, "SIM read permissions denied. Please enter value manually.", Toast.LENGTH_SHORT).show()
            showManualPhoneDialog = true
        }
    }

    // Helper after successful QR code reading or JSON copy paste
    val onQrPayloadAcquired: (String) -> Unit = { qrData ->
        if (vm.parseQrCodePayload(qrData)) {
            vm.deviceName = Build.MODEL
            // Check for permissions
            val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            val hasPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            if (hasPhoneState && hasPhoneNumbers) {
                checkSimAndInitiatePairing()
            } else {
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)
                } else {
                    arrayOf(Manifest.permission.READ_PHONE_STATE)
                }
                phonePermissionLauncher.launch(permissions)
            }
        } else {
            Toast.makeText(context, "Invalid QR code payload structure. Ensure it is correct.", Toast.LENGTH_LONG).show()
        }
    }

    // SIM Selection Dialog markup
    if (showSimSelectionDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showSimSelectionDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "SELECT ACTIVE TELEPHONY SIM",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Multiple active SIM cards were detected. Please select the number used for this SMS dispatch gateway:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    detectedPhoneNumbers.forEach { number ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable {
                                    showSimSelectionDialog = false
                                    initiateAutoPairing(number)
                                }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SimCard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = number,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            showSimSelectionDialog = false
                            showManualPhoneDialog = true
                        }) {
                            Text("USE ANOTHER NUMBER")
                        }
                    }
                }
            }
        }
    }

    // Manual fallback dialog if SIM extraction returns empty or denied
    if (showManualPhoneDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showManualPhoneDialog = false }) {
            var manualNum by remember { mutableStateOf("") }
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "CONFIRM GATEWAY PHONE NUMBER",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Automated SIM extraction was restricted or unassigned. Please confirm the phone number of this device's active SIM card:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = manualNum,
                        onValueChange = { manualNum = it },
                        label = { Text("Gateway Phone Number") },
                        placeholder = { Text("e.g. +91 99999 99999") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showManualPhoneDialog = false }) {
                            Text("CANCEL")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (manualNum.isNotBlank()) {
                                    showManualPhoneDialog = false
                                    initiateAutoPairing(manualNum)
                                } else {
                                    Toast.makeText(context, "Phone number cannot be empty.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("COMPLETE PAIRING")
                        }
                    }
                }
            }
        }
    }

    // Loading overlay Dialog during active handshake wait
    if (isPairingProgress) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Card(
                modifier = Modifier.width(280.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "PAIRING HANDSHAKE...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Initiating handshake & secure link with server. Please wait...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // FCM unavailable dialog to block pairing handshake if push tokens haven't loaded yet
    if (showFcmErrorDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showFcmErrorDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "FCM Unavailable",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "NOTIFICATION FCM NOT AVAILABLE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Google Firebase Cloud Messaging is not active on this device yet. Check your internet connection or try again after a brief moment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                vm.fetchAndSyncFcmToken()
                                Toast.makeText(context, "Retrying FCM fetch...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                        ) {
                            Text("RETRY")
                        }
                        Button(
                            onClick = { showFcmErrorDialog = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("CLOSE")
                        }
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!vm.isPaired) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Unlinked Gateway indicator",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "LINK SMS GATEWAY DEVICE",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Pair this cellular device as a secure local SMS node with your central cloud router.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (vm.fcmToken.length < 20) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    tint = MaterialTheme.colorScheme.error,
                                    contentDescription = "FCM Unavailable Warning"
                                )
                                Text(
                                    text = "Notification FCM not available",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Text(
                                text = "The central cloud router requires a valid Firebase Cloud Messaging push token to deliver outbound text requests to this device. Please make sure notifications are enabled and check your internet connection.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = {
                                    vm.fetchAndSyncFcmToken()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("RETRY REGISTERING FCM", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "START THE PAIRING PROGRESS",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Text(
                            text = "Please touch the SCAN button below and aim your camera at the QR Code provided on your central console. This action automatically retrieves server credentials, extracts your active SIM card phone number, and establishes a secure handshake.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // QR Code Scanner Action
                        Button(
                            onClick = {
                                vm.triggerQrCodeScan(
                                    onSuccess = { qrData -> onQrPayloadAcquired(qrData) },
                                    onFailure = { errMsg ->
                                        Toast.makeText(context, "Scan error: $errMsg", Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("qr_scan_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = "Scan QR", modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("SCAN PAIRING QR CODE", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        } else {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .background(Color(0xFF4CAF50).copy(alpha = 0.15f), CircleShape)
                            .border(2.dp, Color(0xFF4CAF50).copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success checkmark icon",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(54.dp)
                        )
                    }

                    Text(
                        text = "SUCCESSFULLY PAIRED!",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF4CAF50),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Your local gateway is securely linked and routing communications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "DEVICE CONTROL PANEL",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Registration Status Row
                    StatusKeyValueRow(label = "Registration Status", value = "Registered & Secured", textColor = Color(0xFF4CAF50))

                    // Device Name Row
                    StatusKeyValueRow(label = "Device Name", value = vm.deviceName.ifBlank { "Local Node" })

                    // Active SIM Line Row
                    StatusKeyValueRow(label = "SIM Phone Number", value = vm.phoneNumber.ifBlank { "Auto-assigned SIM" }, isMonospace = true)

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // SMS permission
                    StatusKeyValueRow(
                        label = "SMS Permission Status",
                        value = if (vm.hasSmsPermission) "Granted" else "Restricted",
                        textColor = if (vm.hasSmsPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )

                    // SIM state
                    StatusKeyValueRow(
                        label = "SIM Carrier Status",
                        value = if (vm.isSimCardReady) "Ready" else "No SIM / Error",
                        textColor = if (vm.isSimCardReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )

                    // Foreground service Running state
                    StatusKeyValueRow(
                        label = "Foreground Transmitter",
                        value = if (vm.isGatewayServiceActive) "Running" else "Stopped",
                        textColor = if (vm.isGatewayServiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )

                    // Battery optimization state
                    StatusKeyValueRow(
                        label = "Battery Optimization",
                        value = if (vm.isBatteryOptimizationDisabled) "Disabled (Optimal)" else "Restricted / Active",
                        textColor = if (vm.isBatteryOptimizationDisabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )

                    // Last Heartbeat time
                    val formatHeartbeat: (Long) -> String = { ts ->
                        if (ts == 0L) "Never"
                        else {
                            val format = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
                            format.format(Date(ts))
                        }
                    }
                    StatusKeyValueRow(label = "Last Heartbeat Signal", value = formatHeartbeat(vm.lastHeartbeatTime))

                    // Gateway general Status
                    val isGatewayReady = vm.hasSmsPermission && vm.isSimCardReady && vm.isGatewayServiceActive
                    StatusKeyValueRow(
                        label = "Overall Gateway Health",
                        value = if (isGatewayReady) "Ready" else "Service Degraded",
                        textColor = if (isGatewayReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons
                Text(
                    text = "DIAGNOZES & RE-SYNC PANEL",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )

                // 1. Send Heartbeat Button
                var isHeartbeatProgress by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        isHeartbeatProgress = true
                        vm.sendManualHeartbeat { success ->
                            isHeartbeatProgress = false
                            if (success) {
                                Toast.makeText(context, "Heartbeat acknowledged! Eligibility refreshed.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Heartbeat transmission refused. Check API Connection.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isHeartbeatProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    if (isHeartbeatProgress) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SEND HEARTBEAT SIGNAL")
                    }
                }

                // 2. Open SMS Permission Settings
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("OPEN PERMISSIONS PANEL")
                }

                // 3. Open Battery settings
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Please configure battery rules under System settings", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("OPEN BATTERY SETTINGS")
                }

                // 4. Force Service state override
                Button(
                    onClick = {
                        vm.toggleGatewayService(!vm.isGatewayServiceActive)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vm.isGatewayServiceActive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (vm.isGatewayServiceActive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(if (vm.isGatewayServiceActive) Icons.Default.Close else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (vm.isGatewayServiceActive) "STOP FOREGROUND SERVICE" else "START FOREGROUND SERVICE")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        vm.unpairDevice()
                        Toast.makeText(context, "Pairing keys discarded. Gateway offline.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF5252),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("UNPAIR / DISCONNECT NODE", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StatusKeyValueRow(
    label: String,
    value: String,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    isMonospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
fun ActivityLogsTab(logs: List<SmsLogEntity>, vm: GatewayViewModel) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TRANSMISSION ACTIVITY LOGS",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "OTP values are filtered from view for privacy standard compliance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            IconButton(
                onClick = { vm.clearLogHistory() },
                modifier = Modifier.testTag("clear_logs_button")
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Clear logs history", tint = Color(0xFFFF5252))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Empty",
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No recorded transmittals yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs) { log ->
                    LogItemCard(log = log)
                }
            }
        }
    }
}

@Composable
fun LogItemCard(log: SmsLogEntity) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss dd MMM", Locale.getDefault()) }
    val formattedTime = remember(log.createdAt) { timeFormat.format(Date(log.createdAt)) }

    val statusColor = when (log.status.lowercase()) {
        "delivered" -> Color(0xFF4CAF50)
        "sent" -> Color(0xFF81C784)
        "sending" -> Color(0xFFFFB300)
        "queued" -> Color(0xFF90A4AE)
        else -> Color(0xFFFF5252) // errors/failed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = log.status.uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = statusColor
                    )
                }
                Text(
                    text = formattedTime,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "ID: ${log.requestId} | Trg: ${log.mobile}",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = log.messagePreview,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!log.error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ref: ${log.error}",
                    fontSize = 11.sp,
                    color = Color(0xFFFF8A80),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ConfigurationTab(vm: GatewayViewModel) {
    val context = LocalContext.current
    var minuteLimitInput by remember { mutableStateOf(vm.maxSmsPerMinute.toString()) }
    var dailyLimitInput by remember { mutableStateOf(vm.maxSmsPerDay.toString()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "GATEWAY SYSTEM CONFIGURATION",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Rate limit options
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Antispam Rate Limiting Thresholds",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextField(
                            value = minuteLimitInput,
                            onValueChange = { minuteLimitInput = it },
                            label = { Text("Max SMS/min") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("minute_limit_input"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )

                        TextField(
                            value = dailyLimitInput,
                            onValueChange = { dailyLimitInput = it },
                            label = { Text("Max SMS/day") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("daily_limit_input"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    Button(
                        onClick = {
                            val mins = minuteLimitInput.toIntOrNull() ?: 5
                            val days = dailyLimitInput.toIntOrNull() ?: 100
                            vm.updateLimits(mins, days)
                            Toast.makeText(context, "Rate limits synchronized.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.End).testTag("save_limits_button")
                    ) {
                        Text("Apply Limits")
                    }
                }
            }
        }

        // Privacy mode option
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "App Debug Mode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Expose full raw logs in local activity database (highly discouraged for production use).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = vm.isDebugMode,
                        onCheckedChange = { vm.toggleDebugMode(it) },
                        modifier = Modifier.testTag("debug_switch")
                    )
                }
            }
        }

        // Security Warning
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "IMPORTANT FRAUD DEFENSE WARNING",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5252),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This application handles cryptographically signed transactional commands to transmit critical verification strings. Do not run this on custom rooted OS configurations or shared public devices.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

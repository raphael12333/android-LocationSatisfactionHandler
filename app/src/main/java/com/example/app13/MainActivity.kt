package com.example.app13

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.res.Configuration
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.app13.ui.theme.App13Theme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.SettingsClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "user_data")
private lateinit var locationManager: LocationManager
private lateinit var fusedLocationClient: FusedLocationProviderClient
private lateinit var locationRequest: LocationRequest
private lateinit var builder: LocationSettingsRequest.Builder
private lateinit var settingsClient: SettingsClient

object UserDataKeys
{
    val LOCATION_ACCESS_ASK_COUNT = intPreferencesKey("location_access_ask_count")
}

val locationCallback = object : LocationCallback()
{
    override fun onLocationResult(locationResult: LocationResult)
    {
        locationResult.locations.forEach { location ->
            Log.d("check--", "Latitude: ${location.latitude}, Longitude: ${location.longitude}")
        }
    }
}

class MainActivity : ComponentActivity()
{
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = this.getSystemService(LOCATION_SERVICE) as LocationManager
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).apply {
            setMinUpdateDistanceMeters(3F)
            setWaitForAccurateLocation(false)
        }.build()
        builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true) // To display "To continue" instead of "For a better experience"
        settingsClient = LocationServices.getSettingsClient(this)

        setContent {
            Content()
        }
    }
}

@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    showSystemUi = true,
    device = "id:pixel_8",
    apiLevel = 33 // To remove "Layout fidelity warning" in IDE
)
@Composable
fun Content()
{
    App13Theme {
        Scaffold(modifier = Modifier.fillMaxSize())
        { innerPadding ->
            Box(modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .fillMaxSize()
            )
            {
                Column(modifier = Modifier.fillMaxSize())
                {
                    LocationSatisfactionHandler()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LocationSatisfactionHandler()
{
    Log.d("check--", "LocationSatisfactionHandler")

    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLocationEnabled by remember { mutableStateOf(false) }
    val fineLocationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarseLocationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_COARSE_LOCATION)
    var readyToReceiveLocation by remember { mutableStateOf(false) }

    var resolvableExceptionEnableLoc by remember { mutableStateOf<ResolvableApiException?>(null) }

    var displayButtonSatisfyLocation by remember { mutableStateOf(false) }
    var alertDialogText by remember { mutableStateOf("") }
    var showAlertDialog by remember { mutableStateOf(false) }

    // FUNC TO CHECK IF CAN SHOW PERMISSION DIALOG
    fun locationPermissionRequestDialogWillAppear(onResult: (Boolean) -> Unit)
    {
        Log.d("check--", "CHECK IF CAN SHOW PERMISSION DIALOG")

        if (fineLocationPermissionState.status.shouldShowRationale)
        {
            // User has denied the permission but the rationale can be shown
            onResult(true)
        }
        else
        {
            // First time the user lands on this feature, or doesn't want to be asked again for this permission
            /*
            This permissions wrapper is built on top of the available Android platform APIs. We cannot extend the platform's capabilities.
            For example, it's not possible to differentiate between the it's the first time requesting the permission vs the user doesn't want to be asked again use cases.
            See https://google.github.io/accompanist/permissions/
            */

            coroutineScope.launch {
                val locationAccessAskCount = context.dataStore.data.map { preferences ->
                    preferences[UserDataKeys.LOCATION_ACCESS_ASK_COUNT] ?: 0
                }.first()

                onResult(locationAccessAskCount < 2)
            }
        }
    }
    // FUNC TO ASK GO IN SETTINGS MANUAL ALLOW
    fun triggerAllowPermissionInSettings()
    {
        alertDialogText = "Permission required in Settings"
        showAlertDialog = true
    }

    // CHECK IF LOCATION IS ENABLED
    if (!isPreview)
    {
        Log.d("check--", "CHECK IF LOCATION IS ENABLED")
        isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // CHECK IF LOCATION IS ENABLED AND PERMISSION TOO
    Log.d("check--", "CHECK IF LOCATION IS ENABLED AND PERMISSION TOO")
    readyToReceiveLocation = isLocationEnabled && fineLocationPermissionState.status.isGranted
    if (readyToReceiveLocation || isPreview)
    {
        // READY TO RECEIVE LOCATION UPDATES
        Log.d("check--", "READY TO RECEIVE LOCATION UPDATES")
    }

    // START/STOP LOCATION UPDATES
    if (!isPreview)
    {
        DisposableEffect(readyToReceiveLocation)
        {
            if (readyToReceiveLocation)
            {
                Log.d("check--", "START LOCATION UPDATES")
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
            else
            {
                Log.d("check--", "STOP LOCATION UPDATES")
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            onDispose {
                Log.d("check--", "onDispose STOP LOCATION UPDATES")
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        }
    }

    // CREATE DETECTOR FOR LOCATION TOGGLE
    if (!isPreview)
    {
        DisposableEffect(Unit)
        {
            val locationReceiver = object : BroadcastReceiver()
            {
                override fun onReceive(context: Context?, intent: Intent?)
                {
                    Log.d("check--", "DETECTOR FOR LOCATION TOGGLE onReceive")
                    isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
            }
            val intentFilter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            context.registerReceiver(locationReceiver, intentFilter)
            onDispose {
                context.unregisterReceiver(locationReceiver)
            }
        }
    }

    // CREATE REACTOR FOR PRECISE PERMISSION DENIED
    val launcherResultLocationActivationRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result ->
            Log.d("check--", "REACTOR FOR PRECISE PERMISSION DENIED onResult")
            if (result.resultCode == Activity.RESULT_OK)
            {
                if (!fineLocationPermissionState.status.isGranted)
                {
                    Log.d("check--", "... !fineLocationPermissionState.status.isGranted")

                    locationPermissionRequestDialogWillAppear { willAppear ->
                        if (willAppear)
                        {
                            coroutineScope.launch {
                                context.dataStore.edit { data ->
                                    val currentCount = data[UserDataKeys.LOCATION_ACCESS_ASK_COUNT] ?: 0
                                    data[UserDataKeys.LOCATION_ACCESS_ASK_COUNT] = currentCount + 1
                                }
                            }
                            Log.d("check--", "... launchPermissionRequest")
                            fineLocationPermissionState.launchPermissionRequest()
                        }
                        else
                        {
                            triggerAllowPermissionInSettings()
                        }
                    }
                }
            }
        }
    )

    // DETECT WHEN PRECISE PERM IS/BECOMES NOT GRANTED
    LaunchedEffect(fineLocationPermissionState.status, coarseLocationPermissionState.status)
    {
        Log.d("check--", "DETECT WHEN PRECISE PERM IS/BECOMES NOT GRANTED")
        displayButtonSatisfyLocation = !fineLocationPermissionState.status.isGranted
    }

    // REACT TO LOCATION ACTIVATION / DEACTIVATION
    LaunchedEffect(isLocationEnabled)
    {
        Log.d("check--", "REACT TO LOCATION ACTIVATION / DEACTIVATION")

        if (isLocationEnabled)
        {
            displayButtonSatisfyLocation = !fineLocationPermissionState.status.isGranted
        }
        else
        {
            settingsClient.checkLocationSettings(builder.build())
                .addOnFailureListener { exception ->
                    if (exception is ResolvableApiException)
                    {
                        resolvableExceptionEnableLoc = exception
                        displayButtonSatisfyLocation = true
                    }
                    else
                    {
                        Log.d("check--", "not ResolvableApiException, ${exception.message}")
                    }
                }
        }
    }

    // SHOW BUTTON TO INITIATE LOCATION SATISFACTION
    if (displayButtonSatisfyLocation)
    {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        )
        {
            Text(text = "Precise location required", fontSize = 25.sp)
            Button(
                onClick =
                {
                    if (!isLocationEnabled)
                    {
                        try
                        {
                            // SHOW DIALOG ASK ENABLE LOCATION
                            resolvableExceptionEnableLoc?.let {
                                Log.d("check--", "SHOW DIALOG ASK ENABLE LOCATION")
                                val intentSenderRequest = IntentSenderRequest.Builder(it.resolution.intentSender).build()
                                launcherResultLocationActivationRequest.launch(intentSenderRequest)
                            }
                        }
                        catch (e: IntentSender.SendIntentException)
                        {
                            Log.d("check--", "catch ${e.message}")
                        }
                    }
                    else
                    {
                        if (!fineLocationPermissionState.status.isGranted)
                        {
                            locationPermissionRequestDialogWillAppear { willAppear ->
                                if (willAppear)
                                {
                                    coroutineScope.launch {
                                        context.dataStore.edit { data ->
                                            val currentCount = data[UserDataKeys.LOCATION_ACCESS_ASK_COUNT] ?: 0
                                            data[UserDataKeys.LOCATION_ACCESS_ASK_COUNT] = currentCount + 1
                                        }
                                    }
                                    fineLocationPermissionState.launchPermissionRequest()
                                }
                                else
                                {
                                    triggerAllowPermissionInSettings()
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .padding(top = 12.dp)
            )
            {
                Text(text = "Start")
            }
        }
    }

    // SHOW DIALOG TO GO SETTINGS FOR PERM
    if (showAlertDialog)
    {
        AlertDialog(
            onDismissRequest =
            {
                showAlertDialog = false
            },
            confirmButton =
            {
                Button(
                    onClick =
                    {
                        showAlertDialog = false
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }
                )
                {
                    Text("Open")
                }
            },
            text = { Text(text = alertDialogText, fontSize = 20.sp) }
        )
    }
}

//TEST
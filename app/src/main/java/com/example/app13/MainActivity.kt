package com.example.app13

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
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

@Composable
fun Content()
{
    App13Theme {
        Scaffold { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding))
            {
                Column {
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

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLocationEnabled by remember { mutableStateOf(false) }
    val fineLocationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarseLocationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_COARSE_LOCATION)
    var readyToReceiveLocation by remember { mutableStateOf(false) }

    var resolvableExceptionEnableLoc by remember { mutableStateOf<ResolvableApiException?>(null) }

    var displayButtonSatisfyLocation by remember { mutableStateOf(false) }
    var alertDialogText by remember { mutableStateOf("") }
    var showAlertDialogGoSettings by remember { mutableStateOf(false) }

    fun checkIsLocationEnabled(): Boolean
    {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    fun locationPermissionRequestDialogWillAppear(onResult: (Boolean) -> Unit)
    {
        if (fineLocationPermissionState.status.shouldShowRationale)
        {
            // User has denied the permission but the system dialog can still be shown
            onResult(true)
        }
        else
        {
            /*
            It's either the first time requesting the permission, either the user chose not to be asked again.
            See https://google.github.io/accompanist/permissions/#limitations
            */
            coroutineScope.launch {
                val locationAccessAskCount = context.dataStore.data.map { preferences ->
                    preferences[UserDataKeys.LOCATION_ACCESS_ASK_COUNT] ?: 0
                }.first()

                onResult(locationAccessAskCount < 2) // After two refusals, the system dialog doesn't appear anymore.
            }
        }
    }
    fun triggerRequestAllowManually()
    {
        alertDialogText = "Permission required in Settings"
        showAlertDialogGoSettings = true
    }

    // CREATE REACTOR FOR PRECISE PERMISSION DENIED
    val launcherResultLocationActivationRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = {
            if (it.resultCode == Activity.RESULT_OK)
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
                            triggerRequestAllowManually()
                        }
                    }
                }
            }
        }
    )

    readyToReceiveLocation = checkIsLocationEnabled() && fineLocationPermissionState.status.isGranted
    isLocationEnabled = checkIsLocationEnabled()

    LaunchedEffect(fineLocationPermissionState.status, coarseLocationPermissionState.status)
    {
        Log.d("check--", "LaunchedEffect(fineLocationPermissionState.status, coarseLocationPermissionState.status)")
        // Also check isLocationEnabled in case user disabled Location while permission system rational was shown
        displayButtonSatisfyLocation = !(fineLocationPermissionState.status.isGranted && isLocationEnabled)
    }
    LaunchedEffect(isLocationEnabled)
    {
        Log.d("check--", "LaunchedEffect(isLocationEnabled)")

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
                        Log.d("check--", "LOCATION IS NOT ENABLED, STORE exception")
                        resolvableExceptionEnableLoc = exception
                        displayButtonSatisfyLocation = true
                    }
                    else
                    {
                        Log.e("check--", "not ResolvableApiException, ${exception.message}")
                    }
                }
        }
    }

    // CREATE DETECTOR FOR LOCATION TOGGLE
    DisposableEffect(Unit)
    {
        Log.d("check--", "DisposableEffect(Unit)")

        val locationReceiver = object : BroadcastReceiver()
        {
            override fun onReceive(context: Context?, intent: Intent?)
            {
                Log.d("check--", "DETECTOR FOR LOCATION TOGGLE onReceive")
                isLocationEnabled = checkIsLocationEnabled()
            }
        }
        context.registerReceiver(locationReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        onDispose {
            Log.d("check--", "onDispose unregisterReceiver")
            context.unregisterReceiver(locationReceiver)
        }
    }
    DisposableEffect(readyToReceiveLocation)
    {
        Log.d("check--", "DisposableEffect(readyToReceiveLocation)")

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
            Log.d("check--", "STOP LOCATION UPDATES IF RUNNING")
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        onDispose {
            Log.d("check--", "onDispose STOP LOCATION UPDATES")
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    if (displayButtonSatisfyLocation)
    {
        Column(
            modifier = Modifier.fillMaxSize(),
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
                            // SHOW SYSTEM DIALOG ASK ENABLE LOCATION
                            Log.d("check--", "USE exception")
                            resolvableExceptionEnableLoc?.let {
                                val intentSenderRequest = IntentSenderRequest.Builder(it.resolution.intentSender).build()
                                launcherResultLocationActivationRequest.launch(intentSenderRequest)
                            }
                        }
                        catch (e: IntentSender.SendIntentException)
                        {
                            Log.e("check--", "catch ${e.message}")
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
                                    triggerRequestAllowManually()
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.padding(top = 12.dp)
            ) { Text(text = "Start") }
        }
    }

    if (showAlertDialogGoSettings)
    {
        AlertDialog(
            onDismissRequest =
                {
                    showAlertDialogGoSettings = false
                },
            confirmButton =
                {
                    Button(
                        onClick =
                            {
                                showAlertDialogGoSettings = false
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            }
                    )
                    { Text("Open") }
                },
            text =
                {
                    Text(text = alertDialogText, fontSize = 20.sp)
                }
        )
    }

    if (readyToReceiveLocation)
    {
        // READY TO RECEIVE LOCATION UPDATES
        Log.d("check--", "READY TO RECEIVE LOCATION UPDATES")
    }
}
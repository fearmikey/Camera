package com.fearmikey.camera.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Location for photo geotagging using only the framework [LocationManager]
 * (no Google Play Services). Updates are requested only while [start]ed, i.e.
 * while the camera is in the foreground with "Save location" enabled.
 */
class DeviceLocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    @Volatile
    var lastLocation: Location? = null
        private set

    private var started = false

    private val listener = LocationListener { location ->
        if (isBetter(location, lastLocation)) lastLocation = location
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || locationManager == null || !hasPermission()) return
        started = true
        for (provider in PROVIDERS) {
            if (!locationManager.allProviders.contains(provider)) continue
            try {
                locationManager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider, MIN_TIME_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper()
                    )
                }
            } catch (e: SecurityException) {
                // Only coarse permission granted: GPS provider is unavailable.
            } catch (e: IllegalArgumentException) {
                // Provider vanished between checks.
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        locationManager?.removeUpdates(listener)
    }

    /** Location to embed in a photo, or null if it is too old to be meaningful. */
    fun currentLocation(): Location? {
        val loc = lastLocation ?: return null
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        return if (ageMs <= MAX_AGE_MS) loc else null
    }

    private fun isBetter(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        val newer = candidate.elapsedRealtimeNanos - current.elapsedRealtimeNanos > 30_000_000_000L
        return newer || candidate.accuracy <= current.accuracy
    }

    private companion object {
        val PROVIDERS = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        const val MIN_TIME_MS = 10_000L
        const val MIN_DISTANCE_M = 10f
        const val MAX_AGE_MS = 10 * 60 * 1000L
    }
}

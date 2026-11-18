package cn.classbell.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

data class LocationFix(val latitude:Double,val longitude:Double,val accuracy:Float,val elapsed:Long,val mock:Boolean=false) {
    fun requireCurrent(now:Long=SystemClock.elapsedRealtime()) {
        if(!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || !accuracy.isFinite() || accuracy<0 || accuracy>150 || mock || now-elapsed !in 0..120_000)
            throw ApiProblem("定位过旧、精度不足或不可用，请开启精确定位后重新获取。")
    }
}

class DeviceLocation(private val activity:Activity) {
    private val manager=activity.getSystemService(LocationManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private var listener:LocationListener?=null
    fun cancel(){if(listener!=null)DiagnosticLog.event("Location", "updates_stopped");listener?.let{diagnosticRunCatching{manager.removeUpdates(it)}};listener=null;main.removeCallbacksAndMessages(null)}
    fun request(done:(LocationFix?)->Unit) {
        DiagnosticLog.event("Location", "request_begin")
        cancel()
        if(activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){DiagnosticLog.event("Location", "unavailable reason=precise_permission_missing", "WARN");done(null);return}
        fun accept(location:Location):Boolean {
            @Suppress("DEPRECATION") val fix=LocationFix(location.latitude,location.longitude,location.accuracy,location.elapsedRealtimeNanos/1_000_000,location.isFromMockProvider)
            if(diagnosticRunCatching{fix.requireCurrent()}.isFailure){DiagnosticLog.event("Location", "fix_rejected provider=${location.provider} age_ms=${SystemClock.elapsedRealtime()-fix.elapsed} accuracy=${fix.accuracy} mock=${fix.mock}", "WARN");return false}
            DiagnosticLog.event("Location", "fix_accepted accuracy=${fix.accuracy}");cancel();done(fix);return true
        }
        val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter{diagnosticRunCatching{manager.isProviderEnabled(it)}.getOrDefault(false)}
        DiagnosticLog.event("Location", "providers=${providers.joinToString(",")} timeout_seconds=30")
        if(providers.isEmpty()){DiagnosticLog.event("Location", "unavailable reason=no_enabled_provider", "WARN");done(null);return}
        for(provider in providers)if(diagnosticRunCatching{manager.getLastKnownLocation(provider)}.getOrNull()?.let{accept(it)}==true)return
        val observer=object:LocationListener {
            override fun onLocationChanged(location:Location){accept(location)}
            override fun onProviderEnabled(provider:String){DiagnosticLog.event("Location", "provider_enabled=$provider")}
            override fun onProviderDisabled(provider:String){DiagnosticLog.event("Location", "provider_disabled=$provider", "WARN")}
            @Deprecated("Legacy platform callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
        }
        listener=observer
        for(provider in providers)diagnosticRunCatching{manager.requestLocationUpdates(provider,1000,0f,observer,Looper.getMainLooper())}
        main.postDelayed({DiagnosticLog.event("Location", "timeout", "WARN");cancel();done(null)},30_000)
    }
}

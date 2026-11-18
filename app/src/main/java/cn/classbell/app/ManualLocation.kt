package cn.classbell.app

import org.json.JSONObject

data class ManualLocation(val latitude:Double,val longitude:Double,val address:String="") {
    fun requireValid() {
        if(!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || address.length>160 || address.any{it.code<32})throw ApiProblem("请填写有效经纬度和不超过 160 字的地点名称。")
    }
    fun description()="纬度 $latitude\n经度 $longitude"+(if(address.isBlank())"" else "\n地点 $address")
    fun json()=JSONObject().put("latitude",latitude).put("longitude",longitude).put("address",address)
    fun putInto(intent:android.content.Intent)=intent.putExtra("latitude",latitude).putExtra("longitude",longitude).putExtra("address",address)
    companion object {
        fun fromIntent(intent:android.content.Intent)=ManualLocation(intent.getDoubleExtra("latitude",Double.NaN),intent.getDoubleExtra("longitude",Double.NaN),intent.getStringExtra("address").orEmpty()).also{it.requireValid()}
        fun parse(latitude:String,longitude:String,address:String):ManualLocation {
            val value=ManualLocation(latitude.trim().toDoubleOrNull() ?: Double.NaN,longitude.trim().toDoubleOrNull() ?: Double.NaN,address.trim())
            value.requireValid();return value
        }
    }
}

class LocationPresets(private val read:(String)->String?,private val write:(String,String)->Unit,private val remove:(String)->Unit) {
    fun get(owner:String):ManualLocation? {
        if(owner.isBlank())return null
        return diagnosticRunCatching {val data=JSONObject(read("location.preset.$owner") ?: return null);if(data.optString("owner")!=owner)return null
            ManualLocation(data.getDouble("latitude"),data.getDouble("longitude"),data.optString("address")).also{it.requireValid()}}.getOrNull()
    }
    fun save(owner:String,value:ManualLocation){if(owner.isBlank())throw ApiProblem("请先登录账号。");value.requireValid();write("location.preset.$owner",value.json().put("owner",owner).toString())}
    fun clear(owner:String){if(owner.isNotBlank())remove("location.preset.$owner")}
}

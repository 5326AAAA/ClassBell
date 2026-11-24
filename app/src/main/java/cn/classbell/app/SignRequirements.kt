package cn.classbell.app

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Public requirement descriptions only; never retain response bodies, coordinates or tokens. */
data class SignRequirements(val lines:List<String> = emptyList(),val unsupported:List<String> = emptyList(),val missing:List<String> = emptyList()) {
    companion object {
        private val fields=linkedMapOf(
            "ifNeedVCode" to "验证码", "showVCode" to "验证码控件", "ifopenAddress" to "位置",
            "openCheckFaceFlag" to "人脸核验", "openCheckWeChatFlag" to "微信身份校验",
            "openPreventCheatFlag" to "防作弊校验", "openSignOutFlag" to "签退"
        )
        fun from(data:JSONObject):SignRequirements {
            fun flag(name:String)=when(data.optString(name).lowercase()) {"1","true"->true;"0","false"->false;else->null}
            val missing=fields.filter{flag(it.key)==null}.values.toList()
            val unsupported=fields.filter{it.key!="ifopenAddress" && flag(it.key)==true}.values.toList()
            val lines=mutableListOf<String>()
            if(flag("ifphoto")==true || flag("ifPhoto")==true)lines+="照片：需要"
            fields.filter{flag(it.key)==true}.forEach{(key,label)->lines+="$label：需要${if(key=="ifopenAddress")"本次定位" else " · 待修复"}"}
            if(data.optString("otherId")=="2" || data.optString("otherid")=="2")lines+=QrRefreshInfo.read(data).description()
            val rawTimes=listOf("endTime","endtime").mapNotNull{data.optString(it).toLongOrNull()?.takeIf{v->v>0}}.distinct()
            if(rawTimes.size==1) {
                val raw=rawTimes.single();val millis=if(raw in 1_000_000_000L..9_999_999_999L)raw*1000 else raw
                if(millis in 946684800000L..4102444800000L)lines+="截止：${SimpleDateFormat("MM-dd HH:mm",Locale.CHINESE).format(Date(millis))}"
            }
            if(missing.isNotEmpty())lines+="未能核实：${missing.joinToString("、")}"
            return SignRequirements(lines,unsupported,missing)
        }
    }
}

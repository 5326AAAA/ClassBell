package cn.classbell.app

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Small bounded protobuf reader for the public Chaoxing WebIM 3.1.4 descriptor. */
internal object ImPb {
    data class Value(val integer:Long?=null,val bytes:ByteArray?=null)
    class Message(private val fields:Map<Int,List<Value>>) {
        fun number(field:Int,default:Long=0)=fields[field]?.firstOrNull()?.integer ?: default
        fun bytes(field:Int)=fields[field]?.firstOrNull()?.bytes
        fun repeated(field:Int)=fields[field].orEmpty().mapNotNull{it.bytes}
        fun text(field:Int)=bytes(field)?.toString(Charsets.UTF_8).orEmpty()
    }
    fun read(data:ByteArray):Message {
        require(data.size<=2*1024*1024)
        var index=0
        fun varint():Long {
            var result=0L
            for(shift in 0..63 step 7) {
                require(index<data.size)
                val value=data[index++].toInt() and 255
                if(shift==63) require(value<=1)
                result=result or ((value and 127).toLong() shl shift)
                if(value and 128==0) return result
            }
            throw IllegalArgumentException("Invalid protobuf varint")
        }
        val fields=linkedMapOf<Int,MutableList<Value>>()
        var count=0
        while(index<data.size) {
            require(++count<=20000)
            val tag=varint();val field=(tag ushr 3).toInt();require(field in 1..536870911)
            val value=when((tag and 7).toInt()) {
                0 -> Value(integer=varint())
                2 -> {
                    val length=varint();require(length>=0 && length<=data.size-index)
                    val end=index+length.toInt();Value(bytes=data.copyOfRange(index,end)).also{index=end}
                }
                1 -> {require(data.size-index>=8);index+=8;null}
                5 -> {require(data.size-index>=4);index+=4;null}
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
            if(value!=null) fields.getOrPut(field){mutableListOf()}.add(value)
        }
        return Message(fields)
    }
    class Writer {
        private val output=ByteArrayOutputStream()
        private fun varint(raw:Long) {var value=raw;while(value and -128L!=0L){output.write((value.toInt() and 127) or 128);value=value ushr 7};output.write(value.toInt())}
        fun number(field:Int,value:Long):Writer {varint(field.toLong() shl 3);varint(value);return this}
        fun bytes(field:Int,value:ByteArray):Writer {varint((field.toLong() shl 3) or 2);varint(value.size.toLong());output.write(value);return this}
        fun text(field:Int,value:String)=bytes(field,value.toByteArray(Charsets.UTF_8))
        fun build()=output.toByteArray()
    }
}

internal data class ImEvent(val timestamp:Long,val activityIds:Set<String>)
internal object BackendImProtocol {
    const val APP_KEY="cx-dev#cxstudy"
    fun envelope(command:Int,payload:ByteArray=byteArrayOf())=ImPb.Writer().number(1,0).number(8,command.toLong()).number(11,0).bytes(9,payload).build()
    fun provision(username:String,token:String,resource:String):ByteArray {
        val auth="\$t\$$token"
        val jid=ImPb.Writer().text(1,APP_KEY).text(2,username).text(3,"easemob.com").text(4,resource).build()
        val payload=ImPb.Writer().number(1,16).text(2,"3.0.0").number(5,0).number(6,0).text(9,resource)
            .text(12,"webim").text(13,resource).text(14,auth).build()
        return ImPb.Writer().number(1,0).bytes(2,jid).text(3,auth).number(8,3).bytes(9,payload).number(10,0).number(11,0).build()
    }
    fun frame(message:ByteArray)=JSONArray().put(Base64.getEncoder().encodeToString(message)).toString()
    fun provisionStatus(payload:ByteArray)=ImPb.read(payload).bytes(8)?.let { ImPb.read(it).number(1) }
    fun packets(frame:String):List<ByteArray> {
        require(frame.length<=3*1024*1024)
        if(!frame.startsWith("a"))return emptyList()
        val data=JSONArray(frame.substring(1));require(data.length()<=100)
        return (0 until data.length()).map { Base64.getDecoder().decode(data.getString(it)) }
    }
    fun event(metaBytes:ByteArray):ImEvent? {
        val meta=ImPb.read(metaBytes)
        if(meta.number(5)!=1L)return null
        val raw=meta.bytes(6) ?: return null
        val body=ImPb.read(raw)
        val references=linkedSetOf<String>()
        fun scan(value:Any?,depth:Int=0) {
            if(depth>10 || references.size>=5)return
            when(value) {
                is JSONObject -> for(key in value.keys()) {
                    val child=value.opt(key)
                    val childText=child?.toString().orEmpty()
                    if(key.lowercase() in setOf("activeid","activeprimaryid","activityid","aid") && SignProtocol.validId(childText)) references.add(childText)
                    scan(child,depth+1)
                }
                is JSONArray -> for(i in 0 until minOf(value.length(),40))scan(value.opt(i),depth+1)
                is String -> if(value.length<=200000) {
                    if(value.trimStart().startsWith("{"))diagnosticRunCatching{scan(JSONObject(value),depth+1)}
                    else if(value.trimStart().startsWith("["))diagnosticRunCatching{scan(JSONArray(value),depth+1)}
                    Regex("https://mobilelearn\\.chaoxing\\.com/[^\\s<>\"\\\\]+").findAll(value).take(5).forEach { match ->
                        val url=match.value.toHttpUrlOrNull()
                        if(url!=null && url.isHttps && url.host=="mobilelearn.chaoxing.com" && url.port==443) {
                            for(key in url.queryParameterNames) if(key.lowercase() in setOf("id","activeid","activeprimaryid","aid"))
                                url.queryParameterValues(key).filterNotNull().filter(SignProtocol::validId).forEach(references::add)
                        }
                    }
                }
            }
        }
        fun extension(raw:ByteArray) {val item=ImPb.read(raw);scan(JSONObject().put(item.text(1),item.text(6)))}
        body.repeated(5).forEach(::extension)
        body.repeated(4).forEach { rawContent -> val content=ImPb.read(rawContent);scan(content.text(2));content.repeated(11).forEach(::extension);content.repeated(20).forEach(::extension) }
        if(references.isEmpty())return null
        val stamp=meta.number(4)
        return ImEvent(if(stamp in 1_000_000_000L..9_999_999_999L)stamp*1000 else stamp,references.take(5).toSet())
    }
    fun fresh(event:ImEvent,startedAt:Long,now:Long)=event.timestamp>=startedAt-30_000 && event.timestamp<=now+60_000 && event.timestamp>0
    fun verified(body:String,id:String,courses:List<Course>,now:Long=System.currentTimeMillis()):SignActivity? {
        val root=ApiParser.json(body)
        if(root.optString("result") !in setOf("1","true"))return null
        val data=root.optJSONObject("data") ?: return null
        if(data.optString("id")!=id || data.optInt("status")!=1)return null
        val kind=ReminderKind.fromType(data.optInt("activeType",-1)) ?: return null
        val course=courses.firstOrNull { it.id==data.optString("courseId") && it.classId==data.optString("clazzid") } ?: return null
        val raw=data.optString("endTime",data.optString("endtime")).toLongOrNull() ?: 0
        val end=if(raw in 1_000_000_000L..9_999_999_999L)raw*1000 else raw
        if(end>0 && end<=now)return null
        return SignActivity(id,data.optString("name").ifBlank{data.optString("title").ifBlank{kind.label}},course,kind,data.optString("otherId").toIntOrNull())
    }
}

package cn.classbell.app

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A fictional plane measured in metres, independent of account, GPS and sign APIs. */
internal data class DemoMapPoint(val east:Double,val north:Double) {
    val distance get()=hypot(east,north)
    val inside get()=distance<=RADIUS
    companion object {const val RADIUS=150.0;const val EXTENT=550.0}
}

class LocationDemoActivity:Activity() {
    private var point:DemoMapPoint?=null
    private var confirmed=false
    private lateinit var map:DemoCampusMap
    private lateinit var distance:TextView
    private lateinit var description:TextView
    private lateinit var result:TextView
    private lateinit var confirm:Button

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState?.getBoolean("hasPoint")==true) {
            val x=savedInstanceState.getDouble("east");val y=savedInstanceState.getDouble("north")
            if(x.isFinite() && y.isFinite() && x in -DemoMapPoint.EXTENT..DemoMapPoint.EXTENT && y in -DemoMapPoint.EXTENT..DemoMapPoint.EXTENT)point=DemoMapPoint(x,y)
        }
        confirmed=point!=null && savedInstanceState?.getBoolean("confirmed")==true
        val root=StudyUi.column(this).apply{setBackgroundColor(ModernUi.background)}
        root.setOnApplyWindowInsetsListener{view,insets->
            @Suppress("DEPRECATION")
            view.setPadding(dp(18),insets.systemWindowInsetTop+dp(8),dp(18),insets.systemWindowInsetBottom+dp(12));insets}
        root.addView(StudyUi.header(this,"位置签到模拟"){finish()}.first)
        val content=StudyUi.column(this)
        root.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false;addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        content.addView(StudyUi.text(this,"本地练习",13,true,ModernUi.strongPrimary))
        content.addView(StudyUi.text(this,"在地图上选一个位置",24,true))
        content.addView(StudyUi.text(this,"点一下或拖动标记，试试落在范围内与范围外的区别。",14))
        val mapBox=StudyUi.card(this,content).apply{setPadding(0,0,0,0);clipToOutline=true}
        map=DemoCampusMap(this){select(it)}
        mapBox.addView(map,LinearLayout.LayoutParams(-1,dp(280)))
        mapBox.addView(StudyUi.text(this,"示意地图 · 绿色圆圈为模拟签到范围",12).apply{setPadding(dp(16),dp(8),dp(16),dp(12))})
        val summary=StudyUi.card(this,content)
        summary.addView(StudyUi.text(this,"你的选点",13))
        distance=StudyUi.text(this,"",22,true);summary.addView(distance)
        description=StudyUi.text(this,"",14);summary.addView(description)
        val options=LinearLayout(this).apply{orientation=if(resources.configuration.fontScale>1.25f)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL}
        listOf("试试范围内" to DemoMapPoint(70.0,40.0),"试试范围外" to DemoMapPoint(330.0,-180.0)).forEachIndexed{index,(label,value)->
            options.addView(StudyUi.button(this,label){select(value)},if(options.orientation==LinearLayout.HORIZONTAL)LinearLayout.LayoutParams(0,-2,1f).apply{if(index==1)marginStart=dp(8)} else LinearLayout.LayoutParams(-1,-2))
        }
        summary.addView(options)
        result=StudyUi.text(this,"",14,true).apply{setPadding(dp(16),dp(14),dp(16),dp(14));visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        content.addView(StudyUi.text(this,"这是虚构校园，不对应真实地点。所有选点和结果仅供练习，不发送给学习通，也不计入真实签到记录。",12))
        root.addView(result,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        confirm=StudyUi.button(this,"模拟确认选点",true){if(point!=null){DiagnosticLog.event("LocationDemo", "confirmed inside=${point?.inside}");confirmed=true;render()}}
        root.addView(confirm)
        root.addView(StudyUi.button(this,"重新选择"){point=null;confirmed=false;render()}.apply{ModernUi.button(this,textOnly=true)})
        setContentView(root);render()
    }
    private fun dp(value:Int)=ModernUi.dp(this,value)
    private fun select(value:DemoMapPoint){point=value;confirmed=false;render()}
    private fun render() {
        map.selected=point
        distance.text=point?.let{"距模拟教室 ${it.distance.roundToInt()} 米"} ?: "尚未选择位置"
        description.text=point?.let{if(it.inside)"已在模拟范围内 · 半径 150 米" else "超出模拟范围 · 请在绿色圆圈内重新选点"} ?: "点击地图，或用下方示例选点。"
        description.setTextColor(if(point?.inside==false)ModernUi.danger else ModernUi.strongPrimary)
        confirm.isEnabled=point!=null && !confirmed
        result.visibility=if(confirmed)View.VISIBLE else View.GONE
        point?.let{value->
            result.text=if(value.inside)"模拟通过\n你选择的位置在演示范围内。" else "模拟未通过\n你选择的位置超出了演示范围。"
            result.setTextColor(if(value.inside)ModernUi.strongPrimary else ModernUi.danger)
            result.background=ModernUi.shape(this,if(value.inside)ModernUi.soft else 0xFFFFEDE8.toInt(),18)
        }
    }
    override fun onSaveInstanceState(out:Bundle){
        super.onSaveInstanceState(out)
        out.putBoolean("hasPoint",point!=null);point?.let{out.putDouble("east",it.east);out.putDouble("north",it.north)};out.putBoolean("confirmed",confirmed)
    }
}

/** Native schematic map: no tiles, permissions, bridge, network or device-location writes. */
internal class DemoCampusMap(context:Context,private val choose:(DemoMapPoint)->Unit):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    var selected:DemoMapPoint?=null
        set(value){field=value;contentDescription=value?.let{"模拟地图，距模拟教室 ${it.distance.roundToInt()} 米，${if(it.inside)"范围内" else "范围外"}"} ?: "模拟地图，点击选择位置";invalidate()}
    private val extent=DemoMapPoint.EXTENT.toFloat()
    private val unit get()=(minOf(width,height)-32*density).coerceAtLeast(1f)/(extent*2)
    init{isClickable=true;isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;selected=null}
    private fun line(canvas:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,width:Float){paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=width;canvas.drawLine(x1,y1,x2,y2,paint);paint.style=Paint.Style.FILL}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFEDF2EB.toInt())
        val cx=width/2f;val cy=height/2f
        paint.color=0xFFE0E9DA.toInt()
        canvas.drawRoundRect(width*.06f,height*.10f,width*.30f,height*.38f,14*density,14*density,paint)
        canvas.drawRoundRect(width*.65f,height*.62f,width*.95f,height*.9f,14*density,14*density,paint)
        line(canvas,0f,cy-height*.25f,width.toFloat(),cy-height*.25f,Color.WHITE,18*density)
        line(canvas,width*.72f,0f,width*.72f,height.toFloat(),Color.WHITE,16*density)
        line(canvas,0f,height*.84f,width*.58f,0f,Color.WHITE,12*density)
        val radius=DemoMapPoint.RADIUS.toFloat()*unit
        paint.color=0x28146B53;canvas.drawCircle(cx,cy,radius,paint)
        paint.color=ModernUi.primary;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;canvas.drawCircle(cx,cy,radius,paint);paint.style=Paint.Style.FILL
        paint.color=ModernUi.strongPrimary;canvas.drawCircle(cx,cy,5*density,paint)
        paint.textAlign=Paint.Align.CENTER;paint.textSize=12*resources.displayMetrics.scaledDensity
        canvas.drawText("模拟教室",cx,cy+22*density,paint)
        paint.color=ModernUi.muted;paint.textSize=11*resources.displayMetrics.scaledDensity
        canvas.drawText("示意校园",width*.22f,height*.26f,paint)
        selected?.let{value->
            val x=cx+value.east.toFloat()*unit;val y=cy-value.north.toFloat()*unit
            line(canvas,cx,cy,x,y,ModernUi.muted,1*density)
            paint.color=Color.WHITE;canvas.drawCircle(x,y,12*density,paint)
            paint.color=if(value.inside)ModernUi.strongPrimary else ModernUi.danger;canvas.drawCircle(x,y,8*density,paint)
            paint.textSize=12*resources.displayMetrics.scaledDensity;canvas.drawText("选点",x,y-19*density,paint)
        }
        line(canvas,16*density,height-20*density,16*density+100*unit,height-20*density,ModernUi.muted,2*density)
        paint.color=ModernUi.muted;paint.textSize=10*resources.displayMetrics.scaledDensity;paint.textAlign=Paint.Align.LEFT
        canvas.drawText("100 米",16*density,height-28*density,paint)
        paint.textAlign=Paint.Align.RIGHT;canvas.drawText("北 ↑",width-16*density,24*density,paint)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN)parent?.requestDisallowInterceptTouchEvent(true)
        if(event.actionMasked in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
            val east=((event.x-width/2f)/unit).toDouble().coerceIn(-DemoMapPoint.EXTENT,DemoMapPoint.EXTENT)
            val north=((height/2f-event.y)/unit).toDouble().coerceIn(-DemoMapPoint.EXTENT,DemoMapPoint.EXTENT)
            choose(DemoMapPoint(east,north))
        }
        if(event.actionMasked==MotionEvent.ACTION_UP){performClick();parent?.requestDisallowInterceptTouchEvent(false)}
        if(event.actionMasked==MotionEvent.ACTION_CANCEL)parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
    override fun performClick():Boolean {super.performClick();if(selected==null)choose(DemoMapPoint(0.0,0.0));return true}
}

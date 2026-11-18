package cn.classbell.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import kotlin.math.abs

object GesturePath {
    fun valid(path:String)=path.length in 2..9 && path.all{it in '1'..'9'} && path.toSet().size==path.length
    fun requireValid(path:String) { if(!valid(path))throw ApiProblem("请按顺序连接至少两个不同的点（1—9），或填写对应数字路径。") }
    fun append(path:String,digit:Char):String {
        if(digit !in '1'..'9' || digit in path)return path
        val last=path.lastOrNull() ?: return digit.toString()
        val a=last-'1';val b=digit-'1';val dx=abs(a%3-b%3);val dy=abs(a/3-b/3)
        val middle=if((dx==2 && dy==0) || (dx==0 && dy==2) || (dx==2 && dy==2))('1'.code+((a/3+b/3)/2)*3+(a%3+b%3)/2).toChar() else null
        return path+(middle?.takeIf{it !in path}?.toString().orEmpty())+digit
    }
}

/** Original local input control; no material is persisted or logged. Numeric input supports accessibility. */
fun gestureInput(activity:Activity,initial:String,enabled:Boolean=true,changed:(String)->Unit):LinearLayout {
    val column=StudyUi.column(activity)
    column.addView(StudyUi.text(activity,"连接老师公布的手势；九宫格从左到右、从上到下为 1—9。",13))
    val input=EditText(activity).apply {
        hint="也可填写数字路径，例如 258";inputType=android.text.InputType.TYPE_CLASS_NUMBER
        filters=arrayOf(android.text.InputFilter.LengthFilter(9));setText(initial);isEnabled=enabled;isSaveEnabled=false
        contentDescription="手势数字路径";ModernUi.input(this)
    }
    val pad=GesturePad(activity,initial){input.setText(it);input.setSelection(it.length)}.apply{isEnabled=enabled}
    column.addView(pad,LinearLayout.LayoutParams(-1,ModernUi.dp(activity,240)))
    column.addView(input)
    input.addTextChangedListener(object:android.text.TextWatcher {
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){val path=s?.toString().orEmpty();pad.path=path;pad.invalidate();changed(path)}
        override fun afterTextChanged(s:android.text.Editable?){}
    })
    column.addView(StudyUi.button(activity,"重新绘制手势"){input.setText("")}.apply{isEnabled=enabled})
    return column
}

private class GesturePad(activity:Activity,var path:String,private val changed:(String)->Unit):View(activity) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawing=false
    init { contentDescription="手势九宫格，可连续划过圆点；也可在下方输入数字路径";isSaveEnabled=false }
    private fun x(index:Int)=width/2f+(index%3-1)*minOf(width,height)/3.5f
    private fun y(index:Int)=height/2f+(index/3-1)*minOf(width,height)/3.5f
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        paint.color=ModernUi.primary;paint.strokeWidth=6f
        val points=path.filter{it in '1'..'9'}.map{it-'1'}
        points.zipWithNext().forEach{(a,b)->canvas.drawLine(x(a),y(a),x(b),y(b),paint)}
        for(i in 0..8) {
            paint.color=if(i in points)ModernUi.primary else ModernUi.muted
            canvas.drawCircle(x(i),y(i),minOf(width,height)/13f,paint)
            paint.color=android.graphics.Color.WHITE;paint.textSize=minOf(width,height)/13f;paint.textAlign=Paint.Align.CENTER
            canvas.drawText((i+1).toString(),x(i),y(i)-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled)return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{drawing=true;path="";parent.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_CANCEL->{drawing=false;parent.requestDisallowInterceptTouchEvent(false);return true}
        }
        if(drawing) {
            for(sample in 0..event.historySize) {
                val px=if(sample<event.historySize)event.getHistoricalX(sample) else event.x
                val py=if(sample<event.historySize)event.getHistoricalY(sample) else event.y
                for(i in 0..8)if(abs(px-x(i))<minOf(width,height)/9f && abs(py-y(i))<minOf(width,height)/9f)path=GesturePath.append(path,('1'.code+i).toChar())
            }
            changed(path);invalidate()
        }
        if(event.actionMasked==MotionEvent.ACTION_UP){drawing=false;parent.requestDisallowInterceptTouchEvent(false);performClick()}
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}

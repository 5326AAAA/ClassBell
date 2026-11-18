package cn.classbell.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Saved identities are a list. Authentication and account management are separate actions. */
class AccountSwitcherPanel(private val a:Activity,private val switch:(BoundAccount)->Unit,private val manage:(BoundAccount?)->Unit):LinearLayout(a) {
    private val deepGreen=ModernUi.strongPrimary
    private val rows=LinearLayout(a).apply{orientation=VERTICAL}
    private val session=label("未登录",13,ModernUi.muted).apply{setPadding(0,dp(4),0,dp(16))}
    val addButton=StudyUi.button(a,"添加账号"){}.apply {
        ModernUi.accountButton(this);gravity=Gravity.CENTER_VERTICAL or Gravity.START
        val colors=ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(ModernUi.muted,deepGreen))
        setTextColor(colors);compoundDrawableTintList=colors
        setCompoundDrawablesRelativeWithIntrinsicBounds(ModernUi.icon(a,"add",deepGreen),null,null,null);compoundDrawablePadding=dp(16)
        setPadding(dp(18),dp(12),dp(18),dp(12));minHeight=dp(60)
    }
    init {
        orientation=VERTICAL
        addView(session)
        addView(rows,LayoutParams(-1,-2))
        addView(addButton,LayoutParams(-1,-2).apply{topMargin=dp(8)})
        addView(label("登录信息加密保存在此设备",12,ModernUi.muted).apply{setPadding(dp(18),dp(4),dp(18),dp(4))})
    }
    fun render(values:List<BoundAccount>,owner:String,logged:Boolean,name:String,courses:Int,reminders:Int,busy:Boolean) {
        session.text=if(logged)"点选账号即可切换" else if(owner.isNotBlank())"登录已失效" else "未登录"
        rows.removeAllViews()
        val sorted=values.sortedBy{if(logged && it.id==owner)0 else 1}
        if(logged && values.none{it.id==owner})row(null,name.ifBlank{"学习通"},"$courses 门课程 · $reminders 门提醒",true,busy)
        for(value in sorted) {
            val current=logged && value.id==owner
            row(value,value.label,if(current)"${value.courses.size} 门课程 · $reminders 门提醒" else "${value.courses.size} 门课程 · 点击切换",current,busy)
        }
        rows.visibility=if(rows.childCount==0)View.GONE else View.VISIBLE
        addButton.isEnabled=!busy
    }
    private fun row(value:BoundAccount?,name:String,detail:String,current:Boolean,busy:Boolean) {
        val tint=if(current)ModernUi.primary else Color.rgb(82,105,153)
        val fill=if(current)ModernUi.soft else Color.rgb(236,240,249)
        val row=LinearLayout(a).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(88);setPadding(dp(16),dp(16),dp(8),dp(16));isSelected=current
            background=RippleDrawable(ColorStateList.valueOf(0x18146B53),ModernUi.shape(a,if(current)0xFFF0F7F3.toInt() else Color.WHITE,20),ModernUi.shape(a,Color.WHITE,20))
            contentDescription=if(current)"当前账号：$name" else "切换账号：$name"
            if(!current && value!=null){isFocusable=true;isEnabled=!busy;setLoggedClickListener{switch(value)}}
        }
        row.addView(ModernUi.avatar(a),LayoutParams(dp(44),dp(44)))
        val texts=LinearLayout(a).apply{orientation=VERTICAL;setPadding(dp(14),0,dp(8),0)}
        texts.addView(label(name,16,ModernUi.ink,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
        texts.addView(label(detail,12,ModernUi.muted).apply{setPadding(0,dp(6),0,0)})
        row.addView(texts,LayoutParams(0,-2,1f))
        if(current)row.addView(label("已登录",12,Color.WHITE,true).apply{gravity=Gravity.CENTER;setPadding(dp(7),dp(5),dp(7),dp(5));background=ModernUi.shape(a,deepGreen,9)},LayoutParams(-2,-2).apply{gravity=Gravity.CENTER_VERTICAL})
        row.addView(ModernUi.navigation(a,"more","管理账号：$name"){manage(value)}.apply{isEnabled=!busy},LayoutParams(dp(48),dp(48)))
        rows.addView(row,LayoutParams(-1,-2).apply{if(rows.childCount>0)topMargin=dp(12)})
    }
    private fun dp(value:Int)=ModernUi.dp(a,value)
    private fun label(value:String,size:Int,color:Int,bold:Boolean=false)=TextView(a).apply{text=value;textSize=size.toFloat();setTextColor(color);includeFontPadding=false;setLineSpacing(dp(2).toFloat(),1f);if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
}

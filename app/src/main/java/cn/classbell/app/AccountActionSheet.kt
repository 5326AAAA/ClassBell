package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Account actions stay beside their descriptions; removal has a separate confirmation. */
object AccountActionSheet {
    data class Action(val title:String,val description:String,val icon:String,val destructive:Boolean=false,val run:()->Unit)

    fun show(a:Activity,name:String,subtitle:String,actions:List<Action>):AlertDialog {
        lateinit var dialog:AlertDialog
        val dp={value:Int->ModernUi.dp(a,value)}
        val box=StudyUi.column(a).apply {
            setPadding(dp(20),dp(12),dp(20),dp(16))
            background=ModernUi.shape(a,Color.WHITE,28)
        }
        box.addView(View(a).apply{background=ModernUi.shape(a,ModernUi.border,4)},LinearLayout.LayoutParams(dp(36),dp(4)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(12)})
        val header=LinearLayout(a).apply{gravity=Gravity.CENTER_VERTICAL}
        val title=StudyUi.column(a)
        title.addView(StudyUi.text(a,name,20,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
        title.addView(StudyUi.text(a,subtitle,13))
        header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(ModernUi.navigation(a,"close","关闭账号操作"){dialog.dismiss()},LinearLayout.LayoutParams(dp(48),dp(48)))
        box.addView(header)
        for(action in actions) {
            val color=if(action.destructive)ModernUi.danger else ModernUi.strongPrimary
            val row=LinearLayout(a).apply {
                gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(76)
                setPadding(dp(16),dp(12),dp(12),dp(12));isFocusable=true
                contentDescription=action.title
                background=RippleDrawable(ColorStateList.valueOf(0x18146B53),ModernUi.shape(a,if(action.destructive)0xFFFFF4F1.toInt() else ModernUi.background,18),ModernUi.shape(a,Color.WHITE,18))
                setLoggedClickListener{dialog.dismiss();action.run()}
            }
            row.addView(ImageView(a).apply{setImageDrawable(ModernUi.icon(a,action.icon,color));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(24),dp(24)).apply{marginEnd=dp(16)})
            val texts=StudyUi.column(a)
            texts.addView(StudyUi.text(a,action.title,16,true,color))
            texts.addView(StudyUi.text(a,action.description,13))
            row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            box.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(if(action.destructive)12 else 8)})
        }
        box.addView(StudyUi.button(a,"取消"){dialog.dismiss()}.apply{ModernUi.button(this,textOnly=true)})
        val width=minOf(a.resources.displayMetrics.widthPixels-dp(16),dp(560))
        val maxHeight=(a.resources.displayMetrics.heightPixels*.85f).toInt()
        val scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false;addView(box)}
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST))
        dialog=object:AlertDialog(a,R.style.BellDialogTheme){init{setView(scroll,0,0,0,0)}}
        dialog.create()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM);setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(width,scroll.measuredHeight);setWindowAnimations(0)
        }
        dialog.show()
        return dialog
    }
}

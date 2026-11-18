package cn.classbell.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.*

/** A bounded selection sheet. Edits remain local until the explicit action. */
object ChoiceSheet {
    fun show(a:Activity,title:String,subtitle:String,items:List<Pair<String,String>>,selected:Set<Int> = emptySet(),
             primary:String="应用选择",secondary:String?=null,apply:(Set<Int>,Boolean)->Boolean):AlertDialog {
        val keys=selected.toMutableSet()
        val box=StudyUi.column(a).apply{setPadding(ModernUi.dp(a,20),ModernUi.dp(a,8),ModernUi.dp(a,20),ModernUi.dp(a,16));background=ModernUi.shape(a,Color.WHITE,28)}
        box.addView(android.view.View(a).apply{background=ModernUi.shape(a,ModernUi.border,3)},LinearLayout.LayoutParams(ModernUi.dp(a,36),ModernUi.dp(a,4)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=ModernUi.dp(a,12)})
        lateinit var dialog:AlertDialog
        val head=LinearLayout(a).apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(StudyUi.text(a,title,20,true),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(ModernUi.navigation(a,"close","关闭"){dialog.dismiss()},LinearLayout.LayoutParams(ModernUi.dp(a,48),ModernUi.dp(a,48)));box.addView(head)
        box.addView(StudyUi.text(a,subtitle,13))
        val list=StudyUi.column(a)
        items.forEachIndexed{index,item->
            val card=StudyUi.card(a,list).apply{background=ModernUi.shape(a,if(index in keys)ModernUi.soft else ModernUi.background,16);setPadding(ModernUi.dp(a,14),ModernUi.dp(a,8),ModernUi.dp(a,14),ModernUi.dp(a,8))}
            val check=CheckBox(a).apply{text=item.first;ModernUi.selection(this);isChecked=index in keys;tag=index
                setOnCheckedChangeListener{_,yes->if(yes)keys.add(index) else keys.remove(index);card.background=ModernUi.shape(a,if(yes)ModernUi.soft else ModernUi.background,16)}}
            card.addView(check)
            if(item.second.isNotBlank())card.addView(StudyUi.text(a,item.second,12))
            card.setLoggedClickListener{check.performClick()}
        }
        val maxHeight=(a.resources.displayMetrics.heightPixels*0.46f).toInt()
        val estimate=ModernUi.dp(a,(items.size*100*a.resources.configuration.fontScale).toInt())
        box.addView(ScrollView(a).apply{addView(list);isVerticalScrollBarEnabled=false},LinearLayout.LayoutParams(-1,minOf(maxHeight,estimate)))
        secondary?.let{value->box.addView(StudyUi.button(a,value){if(apply(keys.toSet(),true))dialog.dismiss()}.apply{tag=AlertDialog.BUTTON_NEUTRAL})}
        box.addView(StudyUi.button(a,primary,true){if(apply(keys.toSet(),false))dialog.dismiss()}.apply{tag=AlertDialog.BUTTON_POSITIVE})
        dialog=ModernUi.dialog(a).setView(box).create()
        dialog.window?.apply{setGravity(Gravity.BOTTOM);setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));decorView.setPadding(ModernUi.dp(a,8),0,ModernUi.dp(a,8),ModernUi.dp(a,8));setLayout(-1,-2)}
        dialog.show()
        return dialog
    }
}

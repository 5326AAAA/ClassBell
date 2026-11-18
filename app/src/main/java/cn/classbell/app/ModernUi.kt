package cn.classbell.app

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

object ModernUi {
    val primary = Color.rgb(20, 107, 83)
    val strongPrimary = Color.rgb(8,75,54)
    val ink = Color.rgb(23, 45, 39)
    val muted = Color.rgb(105, 124, 115)
    val background = Color.rgb(245, 247, 246)
    val soft = Color.rgb(230, 242, 236)
    val border = Color.rgb(222, 232, 225)
    val danger = Color.rgb(163, 72, 72)
    enum class ChoiceTone(val fill: Int, val text: Int) {
        STANDARD(0xFFF1F6F3.toInt(), 0xFF172D27.toInt()),
        COMPLETE(0xFFE6F2EC.toInt(), 0xFF145F49.toInt()),
        INCOMPLETE(0xFFFFF1DA.toInt(), 0xFF78511A.toInt()),
        NEUTRAL(0xFFEEF1F3.toInt(), 0xFF40535D.toInt()),
        DESTRUCTIVE(0xFFFFECEB.toInt(), 0xFF923D3D.toInt())
    }
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun shape(context: Context, color: Int, radius: Int = 18, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        if (stroke != null) setStroke(dp(context, 1), stroke)
    }

    fun button(view: Button, primaryAction: Boolean = false, textOnly: Boolean = false, inverted: Boolean = false, primaryTint:Int=primary) {
        val context = view.context
        val fill = when { textOnly -> Color.TRANSPARENT; inverted -> Color.WHITE; primaryAction -> primaryTint; else -> background }
        view.backgroundTintList = null
        val enabledBackground = RippleDrawable(ColorStateList.valueOf(0x20146B53),
            shape(context, fill, 14, if(!textOnly && !primaryAction && !inverted)border else null), shape(context, Color.WHITE, 14))
        view.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),
                shape(context, if (textOnly) Color.TRANSPARENT else Color.rgb(233, 237, 235), 14))
            addState(intArrayOf(), enabledBackground)
        }
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(muted, if (primaryAction && !inverted) Color.WHITE else primary)))
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.textSize = 14f; view.isAllCaps = false; view.gravity = Gravity.CENTER
        view.elevation = 0f; view.stateListAnimator = null; view.minHeight = dp(context, 48)
        view.minWidth = dp(context, 48); view.minimumWidth = 0
        view.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        view.includeFontPadding = false
    }

    fun input(view: EditText) {
        view.backgroundTintList = null
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(view.context, Color.WHITE, 14, primary))
            addState(intArrayOf(), shape(view.context, background, 14, border))
        }
        view.setTextColor(ink); view.setHintTextColor(muted); view.textSize = 16f
        view.setPadding(dp(view.context, 16), dp(view.context, 14), dp(view.context, 16), dp(view.context, 14))
        view.minHeight = dp(view.context, 56)
    }
    fun accountButton(view:Button,destructive:Boolean=false) {
        button(view)
        view.background=RippleDrawable(ColorStateList.valueOf(0x22146B53),shape(view.context,Color.rgb(211,230,218),16),shape(view.context,Color.WHITE,16))
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,if(destructive)danger else strongPrimary)))
    }

    fun toggle(view: CheckBox) {
        view.buttonDrawable = null
        view.background = null
        view.foreground = null
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, ToggleDrawable(view.context), null)
        view.compoundDrawablePadding = dp(view.context, 14)
        view.setPadding(0, dp(view.context, 12), 0, dp(view.context, 12))
        view.minHeight = dp(view.context, 64); view.setTextColor(ink); view.textSize = 14f
        view.gravity = Gravity.CENTER_VERTICAL
    }

    fun spinner(view: Spinner) {
        view.setBackgroundResource(R.drawable.select_field)
        view.setPadding(dp(view.context, 10), 0, dp(view.context, 28), 0)
        view.minimumHeight = dp(view.context, 56)
        view.setPopupBackgroundDrawable(shape(view.context, Color.WHITE, 18, border))
    }

    fun dialog(context: Context, choices: List<ChoiceTone> = emptyList()): AlertDialog.Builder = object : AlertDialog.Builder(context, R.style.BellDialogTheme) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.create()
            styleDialog(dialog, choices)
        }
    }

    private fun styleDialog(dialog: AlertDialog, choices: List<ChoiceTone>) {
        val context = dialog.context
        dialog.window?.apply {
            setBackgroundDrawable(shape(context, Color.WHITE, 26))
            setLayout(minOf(context.resources.displayMetrics.widthPixels - dp(context, 32), dp(context, 560)), -2)
            setDimAmount(0.32f)
            setGravity(Gravity.CENTER)
            setWindowAnimations(0)
        }
        val buttons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)
            .mapNotNull { dialog.getButton(it) }.filter { it.visibility == View.VISIBLE }
        buttons.forEach { view ->
            button(view, primaryAction = view == dialog.getButton(AlertDialog.BUTTON_POSITIVE), textOnly = view == dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
        }
        if (buttons.size >= 3 || context.resources.configuration.fontScale > 1.25f) {
            (buttons.firstOrNull()?.parent as? LinearLayout)?.apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                buttons.forEach { it.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 4) } }
            }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            textSize = 15f; setTextColor(ink); setLineSpacing(dp(context, 4).toFloat(), 1f)
        }
        val titleId=context.resources.getIdentifier("alertTitle","id","android")
        dialog.findViewById<TextView>(titleId)?.apply{textSize=20f;setTextColor(ink);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
        dialog.listView?.let { styleChoiceList(it, choices) }
        dialog.window?.decorView?.let(::hideScrollbars)
    }

    private fun styleChoiceList(list: ListView, tones: List<ChoiceTone>) {
        val original = list.adapter ?: return
        val context = list.context
        val checked = list.checkedItemPositions?.clone()
        list.divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = dp(context, 6)
        list.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        list.clipToPadding = false
        list.adapter = object : ListAdapter by original {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = original.getView(position, convertView, parent)
                row.layoutParams = AbsListView.LayoutParams(-1, -2)
                val tone = tones.getOrElse(position) { ChoiceTone.STANDARD }
                val checkedFill = if (tone == ChoiceTone.STANDARD) soft else tone.fill
                val selectedBorder = if (tone == ChoiceTone.STANDARD) primary else tone.text
                row.background = RippleDrawable(ColorStateList.valueOf(0x22146B53),
                    StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), shape(context, checkedFill, 14, selectedBorder))
                        addState(intArrayOf(android.R.attr.state_activated), shape(context, checkedFill, 14, selectedBorder))
                        addState(intArrayOf(), shape(context, tone.fill, 14))
                    }, shape(context, Color.WHITE, 14))
                val text = (row as? TextView) ?: row.findViewById<TextView>(android.R.id.text1)
                text?.apply {
                    setTextColor(tone.text)
                    textSize = 15f
                    minimumHeight = dp(context, 54)
                    setPaddingRelative(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                    if (this is CheckedTextView && list.choiceMode == ListView.CHOICE_MODE_SINGLE) checkMarkTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(selectedBorder, muted))
                }
                return row
            }
        }
        // AlertController marks initial selections before the app styles the list.
        if (checked != null) for (i in 0 until checked.size()) {
            if (checked.valueAt(i)) list.setItemChecked(checked.keyAt(i), true)
        }
    }

    private fun hideScrollbars(view:View) {
        view.isVerticalScrollBarEnabled=false;view.isHorizontalScrollBarEnabled=false
        if(view is android.view.ViewGroup)for(i in 0 until view.childCount)hideScrollbars(view.getChildAt(i))
    }

    fun icon(context: Context, name: String, color: Int): Drawable = LineIcon(context, name, color)
    fun avatar(context:Context)=ImageView(context).apply{
        setImageResource(R.drawable.default_avatar);scaleType=ImageView.ScaleType.CENTER_CROP
        background=shape(context,Color.WHITE,100);clipToOutline=true
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun navigation(context:Context, name:String="back", description:String="返回", action:()->Unit)=ImageButton(context).apply {
        setImageDrawable(icon(context,name,ink));contentDescription=description
        background=RippleDrawable(ColorStateList.valueOf(0x22146B53),null,shape(context,Color.WHITE,24))
        setPadding(dp(context,12),dp(context,12),dp(context,12),dp(context,12));minimumWidth=dp(context,48);minimumHeight=dp(context,48)
        setLoggedClickListener{action()}
    }

    fun selection(view:CheckBox) {
        view.buttonDrawable=null;view.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,SelectionDrawable(view.context),null)
        view.compoundDrawablePadding=dp(view.context,12)
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,ink)))
        view.textSize=15f
        view.minHeight=dp(view.context,48);view.gravity=Gravity.CENTER_VERTICAL
        view.setPadding(0,dp(view.context,8),0,dp(view.context,8))
    }

    private class SelectionDrawable(context:Context):Drawable() {
        private val size=dp(context,24);private var checked=false
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun isStateful()=true
        override fun onStateChange(state:IntArray):Boolean{checked=android.R.attr.state_checked in state;invalidateSelf();return true}
        override fun getIntrinsicWidth()=size
        override fun getIntrinsicHeight()=size
        override fun draw(canvas:Canvas){
            canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
            paint.color=if(checked)primary else border;paint.style=if(checked)Paint.Style.FILL else Paint.Style.STROKE;paint.strokeWidth=1.6f
            canvas.drawRoundRect(2f,2f,22f,22f,7f,7f,paint)
            if(checked){paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.strokeCap=Paint.Cap.ROUND;val p=Path();p.moveTo(7f,12f);p.lineTo(10.5f,15.5f);p.lineTo(17f,9f);canvas.drawPath(p,paint)}
            canvas.restore()
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha}
        override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
        @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }

    private class ToggleDrawable(context: Context) : Drawable() {
        private val width = dp(context, 44)
        private val height = dp(context, 28)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var checked = false
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            checked = android.R.attr.state_checked in state; invalidateSelf(); return true
        }
        override fun getIntrinsicWidth() = width
        override fun getIntrinsicHeight() = height
        override fun draw(canvas: Canvas) {
            canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 44f, bounds.height() / 28f)
            paint.color = if (checked) primary else border
            canvas.drawRoundRect(0f, 0f, 44f, 28f, 14f, 14f, paint)
            paint.color = if (checked) Color.WHITE else muted
            canvas.drawCircle(if (checked) 30f else 14f, 14f, if (checked) 10f else 8f, paint)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class LineIcon(context: Context, private val name: String, private val color: Int) : Drawable() {
        private val size = dp(context, 24)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@LineIcon.color; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun draw(canvas: Canvas) {
            canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            val path = Path()
            when (name) {
                "back" -> {path.moveTo(14.5f,5f);path.lineTo(7.5f,12f);path.lineTo(14.5f,19f)}
                "add" -> {path.moveTo(12f,5f);path.lineTo(12f,19f);path.moveTo(5f,12f);path.lineTo(19f,12f)}
                "more" -> {paint.style=Paint.Style.FILL;for(x in listOf(5f,12f,19f))canvas.drawCircle(x,12f,1.6f,paint);paint.style=Paint.Style.STROKE}
                "qr" -> {canvas.drawRect(3f,3f,9f,9f,paint);canvas.drawRect(15f,3f,21f,9f,paint);canvas.drawRect(3f,15f,9f,21f,paint);path.moveTo(15f,15f);path.lineTo(18f,15f);path.lineTo(18f,18f);path.lineTo(21f,18f);path.lineTo(21f,21f);path.moveTo(15f,21f);path.lineTo(15f,19f)}
                "remove" -> {path.moveTo(4f,6f);path.lineTo(20f,6f);path.moveTo(9f,6f);path.lineTo(9f,3f);path.lineTo(15f,3f);path.lineTo(15f,6f);path.moveTo(6f,6f);path.lineTo(7f,21f);path.lineTo(17f,21f);path.lineTo(18f,6f);path.moveTo(10f,10f);path.lineTo(10f,17f);path.moveTo(14f,10f);path.lineTo(14f,17f)}
                "close" -> {path.moveTo(6f,6f);path.lineTo(18f,18f);path.moveTo(18f,6f);path.lineTo(6f,18f)}
                "refresh" -> {path.moveTo(19f,8f);path.cubicTo(16f,2f,5f,3f,5f,11f);path.moveTo(5f,16f);path.cubicTo(8f,22f,19f,21f,19f,13f);path.moveTo(19f,3f);path.lineTo(19f,8f);path.lineTo(14f,8f);path.moveTo(5f,21f);path.lineTo(5f,16f);path.lineTo(10f,16f)}
                "home" -> { path.moveTo(3f, 10f); path.lineTo(12f, 3f); path.lineTo(21f, 10f); path.moveTo(5f, 9f); path.lineTo(5f, 21f); path.lineTo(10f, 21f); path.lineTo(10f, 15f); path.lineTo(14f, 15f); path.lineTo(14f, 21f); path.lineTo(19f, 21f); path.lineTo(19f, 9f) }
                "courses" -> { canvas.drawRoundRect(4f, 3f, 20f, 21f, 2f, 2f, paint); path.moveTo(8f, 3f); path.lineTo(8f, 21f); path.moveTo(12f, 8f); path.lineTo(16f, 8f); path.moveTo(12f, 12f); path.lineTo(16f, 12f) }
                "homework" -> { canvas.drawRoundRect(5f, 3f, 19f, 21f, 2f, 2f, paint); path.moveTo(9f, 8f); path.lineTo(15f, 8f); path.moveTo(8f, 14f); path.lineTo(11f, 17f); path.lineTo(16f, 12f) }
                "sign" -> {
                    canvas.drawRoundRect(3f, 5f, 21f, 21f, 3f, 3f, paint)
                    canvas.drawLine(7f, 3f, 7f, 7f, paint)
                    canvas.drawLine(17f, 3f, 17f, 7f, paint)
                    path.moveTo(8f, 13f); path.lineTo(11f, 16f); path.lineTo(16f, 11f)
                }
                "profile" -> {canvas.drawCircle(12f,7f,4f,paint);path.moveTo(4f,21f);path.cubicTo(4f,12f,20f,12f,20f,21f);path.close()}
                "test" -> { path.moveTo(9f, 3f); path.lineTo(15f, 3f); path.moveTo(10f, 3f); path.lineTo(10f, 10f); path.lineTo(4f, 19f); path.quadTo(3f, 21f, 6f, 21f); path.lineTo(18f, 21f); path.quadTo(21f, 21f, 20f, 19f); path.lineTo(14f, 10f); path.lineTo(14f, 3f); path.moveTo(7f, 16f); path.lineTo(17f, 16f) }
                else -> { for (y in listOf(6f, 12f, 18f)) canvas.drawLine(3f, y, 21f, y, paint); for ((x, y) in listOf(8f to 6f, 16f to 12f, 9f to 18f)) { paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(x, y, 2.6f, paint); paint.style = Paint.Style.STROKE; paint.color = color; canvas.drawCircle(x, y, 2.6f, paint) } }
            }
            canvas.drawPath(path, paint); canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

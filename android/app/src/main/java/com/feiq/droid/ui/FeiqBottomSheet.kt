package com.feiq.droid.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.feiq.droid.R

object FeiqBottomSheet {
    data class Action(
        val label: String,
        val iconRes: Int,
        val danger: Boolean = false,
        val run: () -> Unit,
    )

    fun menu(
        context: Context,
        title: String,
        message: String? = null,
        actions: List<Action>,
    ) {
        val dialog = create(context)
        val root = inflateBase(context, title, message)
        val content = root.findViewById<LinearLayout>(R.id.sheetContent)
        actions.forEach { action ->
            val row = LayoutInflater.from(context).inflate(R.layout.sheet_menu_row, content, false)
            row.findViewById<ImageView>(R.id.rowIcon).setImageResource(action.iconRes)
            row.findViewById<TextView>(R.id.rowText).apply {
                text = action.label
                if (action.danger) setTextColor(context.getColorCompat(R.color.danger))
            }
            row.setOnClickListener {
                dialog.dismiss()
                action.run()
            }
            content.addView(row)
        }
        dialog.setContentView(root)
        dialog.showAsBottom()
    }

    fun input(
        context: Context,
        title: String,
        message: String? = null,
        hint: String = "",
        value: String = "",
        confirmText: String = "保存",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onConfirm: (String) -> Unit,
    ) {
        val dialog = create(context)
        val root = inflateBase(context, title, message)
        val content = root.findViewById<LinearLayout>(R.id.sheetContent)
        val inputView = LayoutInflater.from(context).inflate(R.layout.sheet_text_input, content, false)
        val edit = inputView.findViewById<EditText>(R.id.sheetInput)
        edit.hint = hint
        edit.setText(value)
        edit.selectAll()
        edit.inputType = inputType
        inputView.findViewById<TextView>(R.id.btnConfirm).apply {
            text = confirmText
            setOnClickListener {
                dialog.dismiss()
                onConfirm(edit.text.toString().trim())
            }
        }
        inputView.findViewById<TextView>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        content.addView(inputView)
        dialog.setContentView(root)
        dialog.setOnShowListener {
            edit.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.showAsBottom()
    }

    fun custom(
        context: Context,
        title: String,
        message: String? = null,
        contentView: View,
    ) {
        val dialog = create(context)
        val root = inflateBase(context, title, message)
        root.findViewById<LinearLayout>(R.id.sheetContent).addView(contentView)
        dialog.setContentView(root)
        dialog.showAsBottom()
    }

    private fun create(context: Context): Dialog =
        Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }

    private fun inflateBase(context: Context, title: String, message: String?): View {
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_base, null, false)
        root.findViewById<TextView>(R.id.sheetTitle).text = title
        root.findViewById<TextView>(R.id.sheetMessage).apply {
            if (message.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                text = message
                visibility = View.VISIBLE
            }
        }
        return root
    }

    private fun Dialog.showAsBottom() {
        show()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }
}

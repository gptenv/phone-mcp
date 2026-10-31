package dev.phonemcp.fixture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView

/** Standalone test-only application; never included in the shipped app. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("finish", false)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 100, 32, 32)
            isFocusableInTouchMode = true
        }
        if (intent.getStringExtra("mode") == "multi_labels" && Build.VERSION.SDK_INT >= 36) {
            column.addView(TextView(this).apply { text = "MCP multi label fixture" })
            val benign = TextView(this).apply { id = View.generateViewId(); text = "Notes" }
            val credential = TextView(this).apply { id = View.generateViewId(); text = "PIN" }
            column.addView(benign); column.addView(credential)
            column.addView(EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText("PRIVATE_MULTI_LABEL_SECRET_5284")
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        if (Build.VERSION.SDK_INT >= 36) {
                            info.addLabeledBy(benign)
                            info.addLabeledBy(credential)
                        }
                    }
                }
            })
            setContentView(column); column.requestFocus()
            return
        }
        if (intent.getStringExtra("mode") == "scroll") {
            val state = TextView(this).apply { text = "MCP scroll top"; textSize = 22f }
            column.addView(state)
            val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            repeat(35) { index -> rows.addView(TextView(this).apply {
                text = "MCP scroll row $index"; textSize = 22f; minimumHeight = 100
            }) }
            val scroll = ScrollView(this).apply {
                addView(rows)
                setOnScrollChangeListener { _, _, y, _, _ -> state.text = if (y > 0) "MCP scroll moved" else "MCP scroll top" }
            }
            column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(column)
            return
        }
        val label = TextView(this).apply { text = "MCP public fixture label"; textSize = 24f }
        column.addView(label)
        column.addView(Button(this).apply {
            text = "MCP public fixture button"
            setOnClickListener { label.text = "MCP button clicked" }
        })
        column.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            contentDescription = "MCP notes field"
            setText("PRIVATE_EDIT_SECRET_7832")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    label.text = when (s?.toString()) {
                        "ordinary notes" -> "MCP replacement accepted"
                        "" -> "MCP field cleared"
                        else -> "MCP text changed"
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        })
        column.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "login username"
            setText("PRIVATE_LOGIN_SECRET_1204")
        })
        val externallyLabelled = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT
            setText("PRIVATE_EXTERNAL_LOGIN_SECRET_4912")
        }
        column.addView(TextView(this).apply { text = "Username"; labelFor = externallyLabelled.id })
        column.addView(externallyLabelled)
        column.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("PRIVATE_PASSWORD_SECRET_3984")
        })
        setContentView(column)
        column.requestFocus()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("finish", false)) finish()
    }
}

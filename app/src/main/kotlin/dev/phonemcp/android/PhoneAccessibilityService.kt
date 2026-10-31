package dev.phonemcp.android

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.phonemcp.core.Controller
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Conservative metadata policy; never examines or retains an editable field's value. */
internal object EditablePolicy {
    private val credentialWords = listOf("password", "passwd", "passcode", "passphrase", "pin", "otp", "token",
        "secret", "auth", "login", "username", "user_name", "email", "account", "verification", "verify",
        "credential", "security", "cvv", "cvc", "card", "2fa", "mfa")
    fun passwordInputType(inputType: Int): Boolean {
        val kind = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }
    fun credentialMetadata(labels: List<String>): Boolean = labels.any { label ->
        if (label.length > 8192) return@any true
        val normalized = label.lowercase(java.util.Locale.ROOT)
        credentialWords.any(normalized::contains)
    }
    const val MAX_LABELS = 8
    /** Every label matters, including later labels on API36+. Excess metadata fails closed. */
    fun labelMetadataAllowed(labels: List<List<String>>): Boolean {
        if (labels.size > MAX_LABELS) return false
        var remaining = 32768
        for (label in labels) for (value in label) {
            if (value.length > 8192) return false
            remaining -= value.length
            if (remaining < 0) return false
        }
        return !credentialMetadata(labels.flatten())
    }
    fun permits(inputType: Int, password: Boolean, dataSensitive: Boolean, editable: Boolean,
                enabled: Boolean, supportsSetText: Boolean, labels: List<String>): Boolean {
        if (!editable || !enabled || !supportsSetText || password || dataSensitive || credentialMetadata(labels)) return false
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return (inputType and InputType.TYPE_MASK_VARIATION) !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
    }
}

class PhoneAccessibilityService : AccessibilityService() {
    private val nodes = linkedMapOf<String, AccessibilityNodeInfo>()
    private val fingerprints = mutableMapOf<String, ByteArray>()
    private var revision = 0L
    private var capturedAt = 0L
    private var capturedPackage: String? = null
    private var capturedToken: String? = null

    override fun onServiceConnected() { PhoneBridge.accessibility = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { invalidateSnapshot() }
    override fun onInterrupt() { invalidateSnapshot() }
    override fun onDestroy() {
        invalidateSnapshot()
        if (PhoneBridge.accessibility === this) PhoneBridge.accessibility = null
        super.onDestroy()
    }

    /** Clear retained screen data synchronously when local session authority ends. */
    fun revokeSnapshot() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Snapshot revocation must run on main thread" }
        invalidateSnapshot()
    }

    @Suppress("DEPRECATION")
    private fun invalidateSnapshot() {
        nodes.values.forEach { it.recycle() }
        nodes.clear(); fingerprints.clear(); revision++; capturedAt = 0L; capturedPackage = null; capturedToken = null
    }
    private fun protectedPackage(pkg: String): Boolean {
        val name = pkg.lowercase()
        return pkg == packageName || name == "android" ||
            listOf("systemui", "settings", "permissioncontroller", "packageinstaller", "credential", "keychain", "password", "authenticator").any(name::contains)
    }
    private fun inherentlySensitive(node: AccessibilityNodeInfo): Boolean = node.isPassword || EditablePolicy.passwordInputType(node.inputType) ||
        (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
    private fun sensitive(node: AccessibilityNodeInfo): Boolean = node.isEditable || inherentlySensitive(node)
    private fun boundedMetadata(value: CharSequence?): String =
        if ((value?.length ?: 0) > 8192) "credential_metadata_too_large" else value?.toString().orEmpty()
    @Suppress("DEPRECATION")
    private fun associatedLabelMetadata(node: AccessibilityNodeInfo): List<String> {
        // API36 can associate multiple labels. The deprecated singleton omits later labels.
        val labels = if (Build.VERSION.SDK_INT >= 36) node.labeledByList else listOfNotNull(node.labeledBy)
        return try {
            if (labels.size > EditablePolicy.MAX_LABELS) return listOf("credential_label_limit_exceeded")
            val metadata = mutableListOf<List<String>>()
            for (label in labels) {
                // No recursion, child traversal, or editable label value reads.
                if (!label.refresh() || sensitive(label)) return listOf("credential_label_unavailable")
                metadata += listOf(boundedMetadata(label.text), boundedMetadata(label.viewIdResourceName),
                    boundedMetadata(label.contentDescription), boundedMetadata(label.hintText))
            }
            if (!EditablePolicy.labelMetadataAllowed(metadata)) listOf("credential_label_unsafe") else metadata.flatten()
        } finally { labels.forEach { it.recycle() } }
    }
    private fun fieldMetadata(node: AccessibilityNodeInfo): List<String> =
        listOf(boundedMetadata(node.viewIdResourceName), boundedMetadata(node.contentDescription), boundedMetadata(node.hintText)) +
            associatedLabelMetadata(node)
    private fun safeEditable(node: AccessibilityNodeInfo): Boolean = EditablePolicy.permits(
        node.inputType, node.isPassword, inherentlySensitive(node), node.isEditable, node.isEnabled,
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }, fieldMetadata(node))

    @Suppress("DEPRECATION")
    private fun fingerprint(node: AccessibilityNodeInfo, refresh: Boolean = false, checkDeadline: () -> Unit = {}): ByteArray {
        var visited = 0
        var textBudget = 32768
        fun text(value: CharSequence?): String {
            val length = value?.length ?: 0
            textBudget -= length
            check(length <= 8192 && textBudget >= 0) {
                "PROTECTED_NODE: Target content exceeds safety limit"
            }
            return value?.toString().orEmpty()
        }
        fun capture(current: AccessibilityNodeInfo, depth: Int): JSONObject {
            checkDeadline()
            if (refresh) check(current.refresh()) { "STALE_NODE: Target subtree is no longer available" }
            check(++visited <= 64 && depth <= 12 && current.childCount <= 64) {
                "PROTECTED_NODE: Target subtree exceeds safety limit"
            }
            if (!current.isVisibleToUser) return JSONObject().put("visible", false)
            // Only the editable root can be a text target; never traverse or fingerprint its value.
            if (current.isEditable && depth == 0) {
                check(safeEditable(current)) { "PROTECTED_NODE: Text target is sensitive or unsupported" }
                val bounds = Rect(); current.getBoundsInScreen(bounds)
                return JSONObject().put("editable", true).put("inputType", current.inputType)
                    .put("class", text(current.className)).put("package", text(current.packageName))
                    .put("bounds", bounds.flattenToString()).put("window", current.windowId)
                    .put("viewId", text(current.viewIdResourceName))
                    // Safe field labels participate in identity only; never export them or node.text.
                    .put("hint", text(current.hintText)).put("description", text(current.contentDescription))
                    .put("labelMetadata", JSONArray(fieldMetadata(current).map { text(it) }))
                    .put("uniqueId", if (Build.VERSION.SDK_INT >= 33) text(current.uniqueId) else "")
                    .put("enabled", current.isEnabled).put("focusable", current.isFocusable)
                    .put("password", current.isPassword).put("sensitive", inherentlySensitive(current))
                    .put("actions", current.actionList.map { it.id }.sorted().joinToString(","))
            }
            check(!sensitive(current)) { "PROTECTED_NODE: Target contains sensitive controls" }
            val bounds = Rect(); current.getBoundsInScreen(bounds)
            val attributes = JSONObject()
                .put("text", text(current.text))
                .put("description", text(current.contentDescription))
                .put("class", text(current.className))
                .put("package", text(current.packageName))
                .put("bounds", bounds.flattenToString())
                .put("window", current.windowId)
                .put("viewId", text(current.viewIdResourceName))
                .put("uniqueId", if (Build.VERSION.SDK_INT >= 33) text(current.uniqueId) else "")
                .put("actions", current.actionList.map { it.id }.sorted().joinToString(","))
                .put("checked", current.isChecked)
                .put("checkable", current.isCheckable)
                .put("selected", current.isSelected)
                .put("enabled", current.isEnabled)
                .put("clickable", current.isClickable)
                .put("stateDescription", if (Build.VERSION.SDK_INT >= 30) text(current.stateDescription) else "")
            val children = JSONArray()
            for (index in 0 until current.childCount) {
                val child = current.getChild(index)
                    ?: error("STALE_NODE: Target subtree changed during inspection")
                try { children.put(capture(child, depth + 1)) } finally { child.recycle() }
            }
            return attributes.put("children", children)
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(capture(node, 0).toString().toByteArray(Charsets.UTF_8))
    }
    private fun checkedRoot(): AccessibilityNodeInfo {
        val root = rootInActiveWindow ?: error("SCREEN_UNAVAILABLE: No accessible active window")
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank() || protectedPackage(pkg)) {
            @Suppress("DEPRECATION") root.recycle()
            error("PROTECTED_SCREEN: This app or system screen is not exposed")
        }
        return root
    }
    @Suppress("DEPRECATION")
    fun inspect(checkDeadline: () -> Unit = {}): JSONObject {
        invalidateSnapshot()
        val root = checkedRoot()
        try {
            capturedPackage = root.packageName.toString()
            capturedToken = PhoneBridge.token
            val result = JSONArray()
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                checkDeadline()
                if (depth > 30 || visited++ >= 250 || !node.isVisibleToUser) return
                // Editable values and descriptions are never exported. Eligible fields expose only metadata.
                if (inherentlySensitive(node) || (node.isEditable && !safeEditable(node))) return
                val id = "$revision:${result.length()}"
                val rect = Rect(); node.getBoundsInScreen(rect)
                val editable = node.isEditable
                val click = !editable && node.isClickable && node.isEnabled
                val scroll = !editable && node.isScrollable && node.isEnabled && node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val item = JSONObject().put("nodeId", id)
                    .put("className", node.className?.take(160)?.toString().orEmpty())
                    .put("editable", editable).put("setTextAllowed", editable)
                    .put("clickable", click).put("scrollable", scroll)
                    .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                if (editable) item.put("textRedacted", true).put("inputType", node.inputType)
                else item.put("text", node.text?.take(500)?.toString().orEmpty())
                    .put("description", node.contentDescription?.take(500)?.toString().orEmpty())
                result.put(item)
                if (click || scroll || editable) {
                    // Never truncate an action fingerprint: incomplete/sensitive subtrees
                    // remain readable where safe, but are not offered as action targets.
                    val digest = try { fingerprint(node, checkDeadline = checkDeadline) } catch (error: IllegalStateException) {
                        if (error.message?.startsWith("PHONE_TIMEOUT:") == true) throw error
                        null
                    }
                    if (digest != null) {
                        nodes[id] = AccessibilityNodeInfo.obtain(node)
                        fingerprints[id] = digest
                    } else item.put("clickable", false).put("scrollable", false).put("setTextAllowed", false)
                }
                if (editable) return // No values or descendant data from editable controls.
                for (index in 0 until minOf(node.childCount, 100)) {
                    if (visited >= 250) break
                    val child = node.getChild(index) ?: continue
                    try { visit(child, depth + 1) } finally { child.recycle() }
                }
            }
            visit(root, 0)
            capturedAt = SystemClock.elapsedRealtime()
            return JSONObject().put("packageName", capturedPackage).put("revision", revision)
                .put("nodes", result).put("truncated", visited >= 250)
                .put("expiresInMs", 5000)
        } finally { root.recycle() }
    }
    fun click(id: String, authorize: () -> Unit): JSONObject =
        nodeAction(id, AccessibilityNodeInfo.ACTION_CLICK, null, authorize)

    fun scroll(id: String, direction: String, authorize: () -> Unit): JSONObject {
        val action = when (direction) {
            "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> { invalidateSnapshot(); error("INVALID_ARGUMENT: direction must be forward or backward") }
        }
        return nodeAction(id, action, null, authorize)
    }
    fun setText(id: String, text: String, authorize: () -> Unit): JSONObject {
        if (!dev.phonemcp.core.TextInputPolicy.accepts(text)) { invalidateSnapshot(); error("INVALID_ARGUMENT: text must contain at most 1000 Unicode code points and no unpaired surrogates") }
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return nodeAction(id, AccessibilityNodeInfo.ACTION_SET_TEXT, arguments, authorize)
    }
    @Suppress("DEPRECATION")
    private fun nodeAction(id: String, action: Int, arguments: Bundle?, authorize: () -> Unit): JSONObject {
        if (capturedToken == null || capturedToken != PhoneBridge.token || capturedAt == 0L || SystemClock.elapsedRealtime() - capturedAt > 5000) {
            invalidateSnapshot(); error("STALE_NODE: Inspect the screen again")
        }
        val root = checkedRoot()
        try {
            if (root.packageName?.toString() != capturedPackage) error("STALE_NODE: Active app changed")
            val node = nodes[id] ?: error("STALE_NODE: Unknown or ineligible node; inspect again")
            authorize()
            if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled ||
                node.packageName?.toString() != capturedPackage || node.windowId != root.windowId) {
                error("STALE_NODE: Target changed; inspect again")
            }
            val permitted = when (action) {
                AccessibilityNodeInfo.ACTION_SET_TEXT -> safeEditable(node)
                AccessibilityNodeInfo.ACTION_CLICK -> !sensitive(node) && node.isClickable
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ->
                    !sensitive(node) && node.isScrollable && node.actionList.any { it.id == action }
                else -> false
            }
            check(permitted) { "PROTECTED_NODE: Target does not permit this action" }
            if (!fingerprint(node, refresh = true, checkDeadline = authorize).contentEquals(fingerprints[id])) {
                error("STALE_NODE: Target changed; inspect again")
            }
            // Recheck refreshed ancestry and every deadline before taking action.
            var ancestor = node.parent
            var ancestorDepth = 0
            while (ancestor != null) {
                val current = ancestor
                try {
                    authorize()
                    check(current.refresh()) { "STALE_NODE: Target ancestor is no longer available" }
                    check(++ancestorDepth <= 32) { "PROTECTED_NODE: Target ancestry exceeds safety limit" }
                    check(!sensitive(current) && (action != AccessibilityNodeInfo.ACTION_SET_TEXT ||
                        !EditablePolicy.credentialMetadata(fieldMetadata(current)))) { "PROTECTED_NODE: Sensitive target ancestry" }
                    ancestor = current.parent
                } finally { current.recycle() }
            }
            authorize()
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                check(node.refresh() && safeEditable(node) && node.isVisibleToUser &&
                    fingerprint(node, checkDeadline = authorize).contentEquals(fingerprints[id])) {
                    "PROTECTED_NODE: Text target changed before execution"
                }
                authorize()
            }
            // Exactly one native action: no click/focus/submit/clipboard fallback.
            val accepted = node.performAction(action, arguments)
            invalidateSnapshot()
            if (!accepted) error("ACTION_REJECTED: Android refused the requested action")
            return JSONObject().put("accepted", true)
        } catch (error: Exception) { invalidateSnapshot(); throw error }
        finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    fun navigate(action: String, authorize: () -> Unit): JSONObject {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> error("INVALID_ARGUMENT: action must be back, home, or recents")
        }
        val root = checkedRoot(); root.recycle()
        authorize()
        val accepted = performGlobalAction(code)
        invalidateSnapshot()
        if (!accepted) error("ACTION_REJECTED: Android refused navigation")
        return JSONObject().put("accepted", true)
    }
}

class AndroidPhoneController(private val context: Context, private val expectedSessionToken: String) : Controller {
    private val handler = Handler(Looper.getMainLooper())
    override fun execute(name: String, args: JSONObject): JSONObject {
        val session = expectedSessionToken
        val deadline = SystemClock.elapsedRealtime() + 2500
        val scopes = PhoneBridge.scopes
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) executeOnMain(name, args, session, scopes, deadline)
            else {
                val work = FutureTask { executeOnMain(name, args, session, scopes, deadline) }
                handler.post(work)
                try { work.get(3, TimeUnit.SECONDS) } catch (e: Exception) {
                    // Cancel queued work. Running Android IPC cannot be undone; the final
                    // guard below prevents starting an action past its earlier deadline.
                    work.cancel(false)
                    throw IllegalStateException(if (e is java.util.concurrent.TimeoutException && name in setOf("node_click", "node_scroll", "node_set_text", "global_action")) "ACTION_OUTCOME_UNKNOWN: Action may have executed; inspect before retrying" else e.cause?.message ?: "PHONE_TIMEOUT: Device did not respond")
                }
            }
        } catch (e: Exception) {
            val known = setOf("SESSION_STOPPED", "ACTION_OUTCOME_UNKNOWN", "PHONE_TIMEOUT", "DEVICE_LOCKED", "SCOPE_DENIED",
                "ACCESSIBILITY_DISABLED", "SCREEN_UNAVAILABLE", "PROTECTED_SCREEN", "STALE_NODE",
                "PROTECTED_NODE", "ACTION_REJECTED", "INVALID_ARGUMENT", "UNKNOWN_TOOL")
            val message = e.message.orEmpty()
            val safe = if (message.substringBefore(':') in known) message.take(240)
                else "PHONE_ERROR: Request could not be completed"
            JSONObject().put("error", safe)
        }
    }
    private fun isUnlocked(): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked && power.isInteractive
    }
    private fun authorize(session: String?, scopes: Set<String>, deadline: Long, requireUnlocked: Boolean) {
        check(SystemClock.elapsedRealtime() < deadline) { "PHONE_TIMEOUT: Request expired before execution" }
        check(session != null && PhoneBridge.active && PhoneBridge.token == session) {
            "SESSION_STOPPED: Start a session on the phone"
        }
        check(PhoneBridge.scopes == scopes) { "SCOPE_DENIED: Session scopes changed" }
        if (!SessionVisibility.available(context)) {
            PhoneBridge.revokeSession()
            val stoppedService = android.content.Intent(context, McpService::class.java)
            context.stopService(stoppedService)
            error("SESSION_STOPPED: Notification visibility was revoked")
        }
        if (requireUnlocked) check(isUnlocked()) { "DEVICE_LOCKED: Unlock and wake the phone locally" }
    }
    private fun executeOnMain(name: String, args: JSONObject, session: String?, scopes: Set<String>, deadline: Long): JSONObject {
        authorize(session, scopes, deadline, name != "phone_status")
        if (name == "phone_status") return JSONObject()
            .put("active", true).put("unlocked", isUnlocked())
            .put("accessibilityConnected", PhoneBridge.accessibility != null)
            .put("scopes", JSONArray(PhoneBridge.scopes.sorted()))
        val required = when (name) {
            "screen_inspect" -> "screen.read"
            "node_click", "node_scroll", "node_set_text", "global_action" -> "screen.act"
            else -> error("UNKNOWN_TOOL: Unknown phone tool")
        }
        check(required in PhoneBridge.scopes) { "SCOPE_DENIED: Enable $required locally in a new session" }
        if (name in setOf("node_click", "node_scroll", "node_set_text")) check("screen.read" in PhoneBridge.scopes) { "SCOPE_DENIED: Node actions also require screen.read for fresh targets" }
        val service = PhoneBridge.accessibility ?: error("ACCESSIBILITY_DISABLED: Enable screen access on the phone")
        return when (name) {
            "screen_inspect" -> service.inspect {
                check(SystemClock.elapsedRealtime() < deadline) { "PHONE_TIMEOUT: Screen inspection budget expired" }
            }.also { authorize(session, scopes, deadline, true) }
            "node_click" -> service.click(args.getString("nodeId")) { authorize(session, scopes, deadline, true) }
            "node_scroll" -> service.scroll(args.getString("nodeId"), args.getString("direction")) { authorize(session, scopes, deadline, true) }
            "node_set_text" -> service.setText(args.getString("nodeId"), args.getString("text")) { authorize(session, scopes, deadline, true) }
            "global_action" -> service.navigate(args.getString("action")) { authorize(session, scopes, deadline, true) }
            else -> error("UNKNOWN_TOOL")
        }
    }
}

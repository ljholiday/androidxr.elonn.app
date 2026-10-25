package com.elonn.androidxr

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.elonn.androidxr.core.AuthClient
import com.elonn.androidxr.core.FindingEntry
import com.elonn.androidxr.core.firstImageMediaResource
import com.elonn.androidxr.core.PanelStore
import com.elonn.androidxr.core.RuntimeAction
import com.elonn.androidxr.core.RuntimeInterpreter
import com.elonn.androidxr.core.RuntimeState
import com.elonn.androidxr.core.TokenStore
import com.elonn.androidxr.core.WorldAuthRequiredException
import com.elonn.androidxr.core.WorldCallRequest
import com.elonn.androidxr.core.WorldClient
import com.elonn.androidxr.core.WorldCollection
import com.elonn.androidxr.core.WorldObject
import com.elonn.androidxr.core.WorldResource
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The native Android XR runtime spike (decision.native_android_xr_candidate_runtime_20260924,
 * next_step.native_android_field_spike_20260924 on agents.elonn.com). A Runtime
 * owns presentation only -- it never decides what exists, only shows what
 * World's Dataset says exists (CLAUDE.md's Runtime definition; xreal.elonn.app's
 * RuntimeInterpreter is the reference this mirrors). Nothing here is Space-loop-
 * specific UI logic invented for this app: the login form renders generically
 * from an action's argument schema, and the Space screen renders generically
 * from Objects/Actions/Placements/Findings, same as every other Runtime.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this, Android reserves the status/navigation bars as solid
        // opaque chrome outside the app's own drawable area -- the "white
        // area on the bottom of the screen" caught live on-device. Space's
        // camera background is meant to be full-bleed; SpaceView insets the
        // interactive layer (Entry, Carry windows) itself so they still land
        // in the safe-drawing area, not under the status/navigation bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // setDecorFitsSystemWindows alone stops reserving layout space for
        // the bars but leaves their own background opaque -- this device's
        // 3-button nav bar was still a solid light strip after the first
        // fix, caught live on-device. Transparent backgrounds let the camera
        // feed show through them instead of sitting behind a painted bar.
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // The Activity theme (Theme.Material.Light.NoActionBar) paints an opaque window
        // background behind everything. In Full Space that covers the user's whole view, so
        // the window itself must be transparent too, not just the Compose root below.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        setContent {
            ElonnTheme {
                // Transparent so the world shows through. contentColor is set explicitly: a
                // transparent Surface otherwise resets it, and all text falls back to black.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    ElonnApp()
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Loading : Screen
    data class Login(val action: JSONObject, val error: String? = null) : Screen
    data class Space(val state: RuntimeState) : Screen
    data class Failed(val message: String) : Screen
}

@Composable
private fun ElonnApp() {
    val context = LocalContext.current
    val auth = remember { AuthClient() }
    val world = remember {
        WorldClient(renderer = if (usesSpatialSpacePresentation(context)) "headset" else "phone")
    }
    val tokenStore = remember { TokenStore(context) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var token by remember { mutableStateOf<String?>(null) }
    // A World Call is in flight -- surfaced next to Go (a small spinner) so tapping Go gives some
    // immediate sign something is happening, not just a silent wait for the next Dataset to
    // arrive.
    var isBusy by remember { mutableStateOf(false) }
    // Bumped once a world.compose search Call completes (success or failure) or a world.clear
    // completes -- EntryResultsWindow uses a change in this to force the Results pane open, so a
    // fresh search (or its error), or an explicit Clear, is never left hidden behind an
    // already-collapsed pane (web.elonn.local's clearResults() does the same: `panel.collapsed =
    // false`).
    var resultsPaneChangedCount by remember { mutableStateOf(0) }

    suspend fun loadLoginForm() {
        try {
            val dataset = auth.loadForm("login")
            val action = findLoginAction(dataset)
            screen = if (action != null) Screen.Login(action) else Screen.Failed("No login action in auth-form Dataset.")
        } catch (e: Exception) {
            screen = Screen.Failed(e.message ?: "Could not reach api.elonn.")
        }
    }

    suspend fun performCall(currentToken: String, datasetId: String?, threadId: String?, request: WorldCallRequest) {
        isBusy = true
        try {
            val dataset = world.call(currentToken, datasetId, threadId, request)
            val state = RuntimeInterpreter.apply(dataset)
            CurrentTheme.value = state.theme
            screen = Screen.Space(state)
        } catch (e: WorldAuthRequiredException) {
            tokenStore.clear()
            token = null
            loadLoginForm()
        } catch (e: Exception) {
            screen = Screen.Failed(e.message ?: "World request failed.")
        } finally {
            isBusy = false
        }
    }

    suspend fun restoreSpace(currentToken: String) {
        performCall(currentToken, datasetId = null, threadId = null, WorldCallRequest(operation = "world.restore"))
    }

    LaunchedEffect(Unit) {
        val stored = tokenStore.load()
        if (stored != null) {
            token = stored
            restoreSpace(stored)
        } else {
            loadLoginForm()
        }
    }

    suspend fun logout() {
        val previousToken = token
        tokenStore.clear()
        token = null
        if (!previousToken.isNullOrBlank()) {
            try {
                auth.logout(previousToken)
            } catch (e: Exception) {
                // Local logout must still complete if the API is temporarily unavailable.
            }
        }
        loadLoginForm()
    }

    when (val current = screen) {
        is Screen.Loading -> LoadingView()
        // xreal.elonn.app's PhoneRenderer.RenderStatus: Refresh is the retry
        // action on this transient failure state, not permanent chrome --
        // "a small floating card... not a screen-spanning block."
        is Screen.Failed -> ErrorView(current.message) {
            scope.launch {
                val currentToken = token
                if (currentToken != null) restoreSpace(currentToken) else loadLoginForm()
            }
        }
        is Screen.Login -> LoginView(current.action, current.error) { values ->
            scope.launch {
                screen = Screen.Loading
                try {
                    val invocation = current.action.getJSONObject("content").getJSONObject("operation_invocation")
                    val path = invocation.getJSONObject("submit").getString("path")
                    val result = auth.submit(path, values)
                    val accessToken = result.optJSONObject("context")?.optString("access_token").orEmpty()
                    if (accessToken.isNotBlank()) {
                        tokenStore.store(accessToken)
                        token = accessToken
                        restoreSpace(accessToken)
                    } else {
                        val retryAction = findLoginAction(result)
                        screen = if (retryAction != null) {
                            Screen.Login(retryAction, firstError(result))
                        } else {
                            Screen.Login(current.action, "Login failed.")
                        }
                    }
                } catch (e: Exception) {
                    screen = Screen.Login(current.action, e.message ?: "Login failed.")
                }
            }
        }
        is Screen.Space -> SpaceView(
            state = current.state,
            isBusy = isBusy,
            resultsPaneChangedCount = resultsPaneChangedCount,
            onSelect = { objectId ->
                val currentToken = token ?: return@SpaceView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        current.state.threadId,
                        WorldCallRequest(
                            operation = "world.focus",
                            inputText = "world.focus",
                            originObject = objectId,
                            selectedObjectId = objectId,
                        ),
                    )
                }
            },
            onDispatch = { label, operationInvocation ->
                val currentToken = token ?: return@SpaceView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        current.state.threadId,
                        WorldCallRequest(
                            operation = "world.compose",
                            inputText = label,
                            operationInvocation = operationInvocation,
                        ),
                    )
                }
            },
            onClose = { objectId ->
                val currentToken = token ?: return@SpaceView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        current.state.threadId,
                        WorldCallRequest(operation = "world.close", inputText = "world.close", originObject = objectId),
                    )
                }
            },
            onBack = { objectId ->
                val currentToken = token ?: return@SpaceView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        current.state.threadId,
                        WorldCallRequest(operation = "world.back", inputText = "world.back", originObject = objectId),
                    )
                }
            },
            onFocusRegion = { objectId, region ->
                val currentToken = token ?: return@SpaceView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        current.state.threadId,
                        WorldCallRequest(operation = "world.focus_region", inputText = "world.focus_region", originObject = objectId, region = region),
                    )
                }
            },
            onSubmitFind = { query ->
                val currentToken = token ?: return@SpaceView
                scope.launch {
                    // No current.state.threadId here (real bug, root-caused and fixed the same
                    // way in web.elonn.local's submitQuery(): opening a Discovery thread set a
                    // "current" thread id that every later, unrelated Entry search then silently
                    // kept continuing, with no way to tell). Omitting thread_id always starts a
                    // fresh, independent Thread -- continuing a specific Thread has its own path
                    // (onContinueThread below).
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        threadId = null,
                        WorldCallRequest(operation = "world.compose", inputText = query),
                    )
                    resultsPaneChangedCount++
                }
            },
            onContinueThread = { threadId, query ->
                val currentToken = token ?: return@SpaceView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        threadId,
                        WorldCallRequest(operation = "world.compose", inputText = query),
                    )
                }
            },
            onClearResults = {
                val currentToken = token ?: return@SpaceView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        current.state.threadId,
                        WorldCallRequest(operation = "world.clear", inputText = "world.clear"),
                    )
                    resultsPaneChangedCount++
                }
            },
        )
    }
}

private fun findLoginAction(dataset: JSONObject): JSONObject? {
    val actions = dataset.optJSONArray("actions") ?: return null
    for (i in 0 until actions.length()) {
        val action = actions.getJSONObject(i)
        val invocation = action.optJSONObject("content")?.optJSONObject("operation_invocation")
        if (invocation?.optString("operation") == "identity.login") return action
    }
    return null
}

private fun firstError(result: JSONObject): String? {
    val objects = result.optJSONArray("objects") ?: return null
    for (i in 0 until objects.length()) {
        val error = objects.getJSONObject(i).optJSONObject("content")?.optString("error")
        if (!error.isNullOrBlank()) return error
    }
    return null
}

@Composable
private fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Elonn", style = MaterialTheme.typography.headlineMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Refresh") }
    }
}

@Composable
private fun LoginView(action: JSONObject, error: String?, onSubmit: (JSONObject) -> Unit) {
    val invocation = action.getJSONObject("content").getJSONObject("operation_invocation")
    val arguments = invocation.getJSONObject("arguments")
    val keys = arguments.keys().asSequence().toList()
    val fieldValues = remember(action) { keys.associateWith { mutableStateOf("") } }
    val label = action.getJSONObject("content").optString("label", "Log in")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Elonn", style = MaterialTheme.typography.headlineMedium)
        Text("Native Android XR runtime spike", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.padding(top = 16.dp))

        for (key in keys) {
            val spec = arguments.getJSONObject(key)
            val fieldLabel = spec.optString("label", key)
            val isPassword = spec.optString("type") == "password"
            var value by fieldValues.getValue(key)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(fieldLabel) },
                singleLine = true,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (isPassword) {
                    KeyboardOptions(keyboardType = KeyboardType.Password)
                } else {
                    KeyboardOptions.Default
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        Button(
            onClick = {
                val payload = JSONObject()
                for (key in keys) payload.put(key, fieldValues.getValue(key).value)
                onSubmit(payload)
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(label)
        }
    }
}

/**
 * A thin, faithful presentation of the World Dataset, per dev.elonn.local's
 * layout.md and terminology/window.md. Space is a real camera passthrough
 * background with markers placed by GPS bearing/distance plus live compass
 * heading (Geo.kt) -- unaffected by window mechanics, since Space Placement
 * is immutable and a marker's position is real-world geometry, not something
 * the member drags. Everything on Carry -- including Entry, which is Carry's
 * one non-closable window -- floats over that background as a window: move
 * (drag the header), resize (drag the corner), collapse (tap the header, or
 * an explicit control), close (all but Entry). No Object gets fewer
 * controls than another; Entry's only difference is `closable = false`.
 * Entry and the Results pane are one window, not two elements: Entry is that
 * window's header, and collapsing it is exactly layout.md's Results pane
 * show/hide -- "only Entry remains visible" when collapsed.
 *
 * layout.md: "No other permanent interface elements are presented alongside
 * Entry." There is no app-level title bar, refresh button, or logout button
 * here -- those aren't canonical chrome. Refresh only exists as the retry
 * action on the Screen.Failed state (ErrorView), matching xreal.elonn.app's
 * PhoneRenderer.RenderStatus ("a small floating card... not chrome"). Log
 * out isn't Runtime chrome at all -- web.elonn.local's web-runtime.js: "Logout
 * rides on every member.profile object," an ordinary Action on that Object,
 * rendered generically like any other Action once that Object is open on
 * Carry -- not yet reachable here since this Runtime has no Dashboard/entry
 * point to open the member's own profile Object.
 */
@Composable
private fun SpaceView(
    state: RuntimeState,
    isBusy: Boolean,
    resultsPaneChangedCount: Int,
    onSelect: (String) -> Unit,
    onDispatch: (String, JSONObject) -> Unit,
    onClose: (String) -> Unit,
    onBack: (String) -> Unit,
    onSubmitFind: (String) -> Unit,
    onContinueThread: (String, String) -> Unit,
    onClearResults: () -> Unit,
    onFocusRegion: (String, String) -> Unit,
) {
    // A Space Placement names exactly one Object (placement.md: "A Placement positions one
    // Object, never a Collection") -- World never emits one naming a Collection, so there is no
    // Collection-expansion step here.
    val spaceObjects = state.space.objectIds.mapNotNull { state.objectsById[it] }
    val markerContent: @Composable (WorldObject, Double, Modifier) -> Unit = { obj, distanceMeters, markerModifier ->
        SpaceMarker(
            obj = obj,
            distanceMeters = distanceMeters,
            selected = obj.id == state.selectedObjectId,
            onSelect = onSelect,
            modifier = markerModifier,
        )
    }

    val carry = CarryInputs(
        state = state,
        isBusy = isBusy,
        resultsPaneChangedCount = resultsPaneChangedCount,
        onSelect = onSelect,
        onDispatch = onDispatch,
        onClose = onClose,
        onBack = onBack,
        onSubmitFind = onSubmitFind,
        onContinueThread = onContinueThread,
        onClearResults = onClearResults,
        onFocusRegion = onFocusRegion,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Headset: real Jetpack XR markers through SceneCore, with Carry in its own head-locked
        // panel. Phone: classic ARCore camera passthrough, with Carry inline. Both render the same
        // markers through markerContent and the same Carry content through CarryLayer.
        if (usesSpatialSpacePresentation(LocalContext.current)) {
            ArCoreSpace(
                spaceObjects = spaceObjects,
                modifier = Modifier.fillMaxSize(),
                markerContent = markerContent,
                carry = carry,
                carryContent = { CarryLayer(it) },
            )
        } else {
            ClassicArCoreSpace(
                spaceObjects = spaceObjects,
                modifier = Modifier.fillMaxSize(),
                markerContent = markerContent,
            )
            CarryLayer(carry)
        }
    }
}

/**
 * Everything the Carry layer renders, passed as one value. The headset hosts Carry in a separate
 * panel, so its content must observe these inputs as they change rather than capture a snapshot.
 */
internal data class CarryInputs(
    val state: RuntimeState,
    val isBusy: Boolean,
    val resultsPaneChangedCount: Int,
    val onSelect: (String) -> Unit,
    val onDispatch: (String, JSONObject) -> Unit,
    val onClose: (String) -> Unit,
    val onBack: (String) -> Unit,
    val onSubmitFind: (String) -> Unit,
    val onContinueThread: (String, String) -> Unit,
    val onClearResults: () -> Unit,
    val onFocusRegion: (String, String) -> Unit,
)

/**
 * The Carry layer: Entry with its Results pane, and every Carry window. Presentation-agnostic:
 * the headset hosts it in a head-locked panel, the phone shows it inline.
 */
@Composable
internal fun CarryLayer(inputs: CarryInputs) {
    val state = inputs.state
    val context = LocalContext.current
    val panelStore = remember { PanelStore(context) }
    // Camera background is full-bleed, behind the status/navigation bars
    // (edge-to-edge, per MainActivity's setDecorFitsSystemWindows(false));
    // windows measure/clamp against the safe-drawing area instead, one inset
    // in from that, so Entry/Carry windows never default or drag under a
    // system bar. Two different bounds, deliberately.
    var boundsPx by remember { mutableStateOf(IntSize.Zero) }

    val carryObjects = state.carry.objectIds.mapNotNull { state.objectsById[it] }

    // Entry and every Carry window live in the safe-drawing area, not
    // the true full-screen bounds above -- otherwise a window's default
    // position, or a drag/resize clamp, could land it under the status
    // or navigation bar where it's unreachable.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onSizeChanged { boundsPx = it },
    ) {
        if (boundsPx != IntSize.Zero) {
            EntryResultsWindow(
                panelStore = panelStore,
                boundsPx = boundsPx,
                findings = state.findings,
                state = state,
                isBusy = inputs.isBusy,
                resultsPaneChangedCount = inputs.resultsPaneChangedCount,
                onSelect = inputs.onSelect,
                onSubmitFind = inputs.onSubmitFind,
                onClearResults = inputs.onClearResults,
            )

            carryObjects.forEachIndexed { index, obj ->
                key(obj.id) {
                    FloatingWindow(
                        panelId = obj.id,
                        store = panelStore,
                        boundsPx = boundsPx,
                        defaultX = 24.dp + (index * 18).dp,
                        defaultY = 320.dp + (index * 18).dp,
                        defaultWidth = 300.dp,
                        defaultHeight = 220.dp,
                        closable = true,
                        onClosed = { inputs.onClose(obj.id) },
                        canGoBack = state.navigationById[obj.id]?.hasHistory == true,
                        onBack = { inputs.onBack(obj.id) },
                        header = { _, _ ->
                            Text(
                                state.carryTitle(obj),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        },
                        body = {
                            // This window's own scrolling (FloatingWindow's body
                            // doc): plain stacked content, so verticalScroll, not a
                            // list -- e.g. the account Dashboard's stack of forms is
                            // taller than the window's fixed height.
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                if (obj.type == "world.thread") {
                                    ThreadTurns(obj)
                                    ThreadContinueForm(obj.discoveryThreadId, inputs.onContinueThread)
                                } else if (obj.type == "document") {
                                    // An interpreted web document (Find's find.open result,
                                    // dev.elonn canonical/html.md) is partitioned into semantic
                                    // regions (content.regions), never content.body -- mirrors
                                    // web.elonn.local's documentNode()/unity.elonn.app's
                                    // RenderDocument() exactly, including presenting the region
                                    // list first and never falling back to summary/body for a
                                    // document (a region IS its content, not a substitute for
                                    // it).
                                    DocumentBody(
                                        obj = obj,
                                        focusedRegion = state.navigationById[obj.id]?.region.orEmpty(),
                                        onFocusRegion = inputs.onFocusRegion,
                                        state = state,
                                    )
                                } else {
                                    val bodyNodes = obj.content.optJSONArray("body")
                                    // summary and body are not a strict either/or (object.md,
                                    // Content body: the canonical text fields "remain
                                    // separate, top-level, and unaffected by body").
                                    // decision.summary_is_descriptive_not_a_content_excerpt_
                                    // 20261024: summary is an optional descriptive field,
                                    // never a derived content excerpt -- a legitimate summary
                                    // and a real body may coexist, and this screen never
                                    // suppresses either based solely on the presence of the
                                    // other, even when body carries its own real text.
                                    if (obj.summary.isNotBlank()) {
                                        Text(obj.summary, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (bodyNodes != null && bodyNodes.length() > 0) {
                                        BodyContentNodes(bodyNodes, state)
                                    }
                                    ResourceLines(obj, state)
                                    ActionLines(obj, state, inputs.onDispatch)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Entry and the Results pane as one window (layout.md, terminology/window.md).
 * The header/topbar carries only the title, Clear, and show/hide -- Entry
 * itself (form field, submit; microphone omitted, this Runtime has no voice
 * input yet) sits at the bottom of the body, chat-input style, below the
 * Results pane (Findings), matching web.elonn.local's Entry-at-bottom change
 * (2026-10-14) -- a fresh result lands next to Entry at the end of the list,
 * not up top out of view. `closable = false`: this window must always exist.
 * Submitting dispatches world.compose with the typed text as Call content
 * (xreal.elonn.app's ElonnRuntimeApp.SubmitFind uses the same contract).
 * Clear dispatches world.clear, which empties the Results pane in World's
 * saved state, not just this screen. Collapsing (tap the header, or the
 * explicit show/hide button) is local-only and never touches World -- exactly
 * layout.md's "does not hide Entry, no effect on Carry or Space."
 */
@Composable
private fun EntryResultsWindow(
    panelStore: PanelStore,
    boundsPx: IntSize,
    findings: List<FindingEntry>,
    state: RuntimeState,
    isBusy: Boolean,
    resultsPaneChangedCount: Int,
    onSelect: (String) -> Unit,
    onSubmitFind: (String) -> Unit,
    onClearResults: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    FloatingWindow(
        panelId = "entry",
        store = panelStore,
        boundsPx = boundsPx,
        defaultX = 16.dp,
        defaultY = 88.dp,
        defaultWidth = 340.dp,
        defaultHeight = 340.dp,
        closable = false,
        // A completed search should never sit hidden behind an already-collapsed pane -- expand
        // every time a world.compose search finishes (success or failure; a failure still has a
        // status message worth surfacing), never on some other unrelated recomposition.
        expandOnChangeOf = if (resultsPaneChangedCount > 0) resultsPaneChangedCount else null,
        header = { collapsed, toggleCollapsed ->
            Text("Find", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (findings.isNotEmpty()) {
                // web.elonn.local's clearResults() resets the typed query alongside emptying the
                // Results pane (queryInput.value = '') -- this button, unlike Go
                // (constraint.androidxr_go_never_clears_query_20260927, which is specific to
                // submitting a search), is expected to clear the box.
                TextButton(onClick = {
                    query = ""
                    onClearResults()
                }) { Text("Clear") }
            }
            TextButton(onClick = toggleCollapsed) { Text(if (collapsed) "Show" else "Hide") }
        },
        body = {
            // dataset.md: World resolves one status (severity + message) per
            // Dataset; the Runtime renders exactly that, not its own derived
            // error text. Shown here, in Entry's own window, rather than as
            // separate standalone chrome.
            if (state.status.severity != "ok" && state.status.message.isNotBlank()) {
                Text(
                    state.status.message,
                    color = if (state.status.severity == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // The Results list takes whatever space is left above Entry, which
            // sits at the bottom of this window, chat-input style (matches
            // web.elonn.local's Entry-at-bottom change, record
            // next_step.androidxr_discovery_thread_rendering_plan_20261014c
            // step 2) -- a fresh result appends at the end, next to where Entry
            // already is, instead of up top out of view.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (findings.isEmpty()) {
                    Text("No results yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(ElonnSpacing.xs),
                    ) {
                        // dataset.findings interleaves collection- and object-kind entries, oldest
                        // first (web.elonn.local's findingsView) -- rendering object-kind only, as
                        // this once did, silently drops every collection result (e.g. Maps'
                        // maps.reverse answer to a Discovery-thread query like "where am I").
                        items(findings) { entry ->
                            when (entry.kind) {
                                "object" -> state.objectsById[entry.id]?.let { ObjectRow(it, state, onSelect) }
                                "collection" -> state.collectionsById[entry.id]?.let { CollectionFinding(it, state, onSelect) }
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = ElonnSpacing.xs),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Find...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(start = ElonnSpacing.xs), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { if (query.isNotBlank()) onSubmitFind(query) }) { Text("Go") }
                }
            }
        },
    )
}

/**
 * A Space marker card. Positioning is entirely the caller's concern now
 * (ArCorePassthrough.kt's ArCoreSpace passes a Modifier that places this at the
 * real ARCore-tracked screen position for this object's Anchor, recomputed
 * every frame from the camera's own view/projection matrices) -- this
 * composable only renders the card, it does not know or care where it ends
 * up on screen.
 */
@Composable
private fun SpaceMarker(
    obj: WorldObject,
    distanceMeters: Double,
    selected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    val unselectedColor = MaterialTheme.colorScheme.surface
    Column(
        modifier = modifier
            .background(if (selected) selectedColor else unselectedColor, RoundedCornerShape(ElonnRadius.card))
            .border(
                ElonnBorderWidth,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(ElonnRadius.card),
            )
            .clickable { onSelect(obj.id) }
            .padding(ElonnSpacing.xs),
    ) {
        // Space markers are read at a distance inside a headset, so they use body-scale type.
        Text(obj.title.ifBlank { obj.id }, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("${distanceMeters.toInt()}m away", style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

/** resource.md: an external-reference Resource's own label, or its domain when it has none --
 * never prefixed ("Source: ..."); the reference reads on its own, the same text wherever a
 * Runtime shows it (the Results-pane row below, or ResourceLines' detail view). */
private fun externalReferenceText(resource: WorldResource): String =
    resource.label.ifBlank { domainOf(resource.source) }

/** The first external-reference Resource's text, for a compact single-line summary (e.g. a
 * Results-pane row) -- mirrors web.elonn.local's cardLinks(), which likewise shows only the
 * first Resource with a source, not every one. */
private fun firstExternalReferenceText(obj: WorldObject, state: RuntimeState): String? {
    for (resourceId in obj.resourceIds) {
        val resource = state.resourcesById[resourceId] ?: continue
        if (resource.isExternalReference) {
            return externalReferenceText(resource)
        }
    }
    return null
}

/** resource.md: a real embed loads; anything else is reference text only, never opened. */
@Composable
private fun ResourceLines(obj: WorldObject, state: RuntimeState) {
    for (resourceId in obj.resourceIds) {
        val resource = state.resourcesById[resourceId] ?: continue
        when {
            resource.isEmbeddable -> AndroidView(
                modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 4.dp),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        loadUrl(resource.source)
                    }
                },
                update = { view -> if (view.url != resource.source) view.loadUrl(resource.source) },
            )
            resource.isExternalReference -> Text(
                externalReferenceText(resource),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * An Object's own canonical content.body (dev.elonn canonical/object.md, Content body): an
 * ordered list of content nodes a Runtime renders generically, with zero Service-specific
 * interpretation, before ever falling back to the Object's bare summary text -- the same
 * vocabulary and the same precedence web.elonn.local's canonicalBodyNodes()/contentTreeNodes()
 * already use. World Dataset refactor step 9 (Runtime completion): this Runtime previously had
 * no renderer for it at all and only ever showed summary.
 */
/**
 * An interpreted web document (Find's find.open result, dev.elonn canonical/html.md,
 * "Semantic regions") is partitioned into regions, not one flat content tree. `focusedRegion` is
 * World's own state for this container (context.objects[id].region, canonical/context.md,
 * "Recognized detail: region focus") -- this composable reads it, it does not track or decide
 * which region is shown. Mirrors web.elonn.local's documentNode()/unity.elonn.app's
 * RenderDocument() exactly: a document with more than one region always shows the region list
 * first until World reports one focused; a single-region document (no landmark structure at
 * all) renders it directly, since a list of one has nothing to choose between.
 */
@Composable
private fun DocumentBody(
    obj: WorldObject,
    focusedRegion: String,
    onFocusRegion: (String, String) -> Unit,
    state: RuntimeState,
) {
    val regions = obj.content.optJSONArray("regions") ?: return
    var selectedContent: JSONArray? = null
    if (focusedRegion.isNotBlank()) {
        for (i in 0 until regions.length()) {
            val region = regions.optJSONObject(i) ?: continue
            if (region.optString("key") == focusedRegion) {
                selectedContent = region.optJSONArray("content")
                break
            }
        }
    }

    when {
        regions.length() > 1 && selectedContent != null -> BodyContentNodes(selectedContent, state)
        regions.length() > 1 -> DocumentRegionList(regions, obj.id, onFocusRegion)
        regions.length() == 1 -> BodyContentNodes(regions.optJSONObject(0)?.optJSONArray("content") ?: JSONArray(), state)
    }
}

private val DocumentRegionLabels = mapOf(
    "header" to "Header",
    "navigation" to "Navigation",
    "main" to "Main content",
    "sidebar" to "Sidebar",
    "secondary_sidebar" to "Secondary sidebar",
    "footer" to "Footer",
)

/**
 * Each row dispatches world.focus_region naming this container and the region key -- a real
 * World Call, not a Runtime-local view switch (same reasoning as unity.elonn.app's
 * RenderDocumentRegionList). Returning to this list needs no dedicated control here: focusing a
 * region advances the container's depth, so FloatingWindow's own back control (shown once
 * canGoBack is true) already pops it.
 */
@Composable
private fun DocumentRegionList(regions: JSONArray, objectId: String, onFocusRegion: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ElonnSpacing.xs)) {
        for (i in 0 until regions.length()) {
            val key = regions.optJSONObject(i)?.optString("key").orEmpty()
            if (key.isBlank()) continue
            TextButton(onClick = { onFocusRegion(objectId, key) }) {
                Text(DocumentRegionLabels[key] ?: key)
            }
        }
    }
}

@Composable
private fun BodyContentNodes(nodes: JSONArray, state: RuntimeState) {
    Column(verticalArrangement = Arrangement.spacedBy(ElonnSpacing.sm)) {
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            BodyContentNode(node, state)
        }
    }
}

@Composable
private fun BodyContentNode(node: JSONObject, state: RuntimeState) {
    when (node.optString("node")) {
        "heading" -> Text(node.optString("text"), style = MaterialTheme.typography.titleSmall)
        "paragraph" -> Text(node.optString("text"), style = MaterialTheme.typography.bodyMedium)
        // group has no semantic label of its own; section's `kind` is content-level
        // semantics, not presentation (object.md, Content body) -- neither is rendered
        // differently here yet, both just recurse their own content in order.
        "group", "section" -> BodyContentNodes(node.optJSONArray("content") ?: JSONArray(), state)
        "list" -> BodyList(node, state)
        "media" -> BodyMedia(node, state)
    }
}

@Composable
private fun BodyList(node: JSONObject, state: RuntimeState) {
    val ordered = node.optBoolean("ordered", false)
    val items = node.optJSONArray("items") ?: JSONArray()
    Column(verticalArrangement = Arrangement.spacedBy(ElonnSpacing.sm)) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            Row {
                Text(if (ordered) "${i + 1}. " else "• ", style = MaterialTheme.typography.bodyMedium)
                BodyContentNodes(item.optJSONArray("content") ?: JSONArray(), state)
            }
        }
    }
}

/** A `media` node's own `resource` field names a promoted Resource -- the same
 * Elonn-constructed, provider-table-vetted boundary ResourceLines already resolves through,
 * never a raw URL on the node itself (mirrors web.elonn.local's resourceById/documentMedia). */
@Composable
private fun BodyMedia(node: JSONObject, state: RuntimeState) {
    val resource = state.resourcesById[node.optString("resource")] ?: return
    when {
        resource.isImage -> RemoteImage(
            url = resource.source,
            maxHeight = 180.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        resource.isEmbeddable -> AndroidView(
            modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 4.dp),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    loadUrl(resource.source)
                }
            },
            update = { view -> if (view.url != resource.source) view.loadUrl(resource.source) },
        )
        resource.isExternalReference -> Text(
            externalReferenceText(resource),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * A Discovery thread's own Turns (dev.elonn canonical/thread.md): the
 * member's own input for each Turn, oldest first, with any clarifying
 * question Conductor asked for that Turn shown inline. Plain presentational
 * content -- a past Turn is never itself opened or selected, unlike an
 * ordinary Collection's member rows.
 */
@Composable
private fun ThreadTurns(obj: WorldObject) {
    Column(verticalArrangement = Arrangement.spacedBy(ElonnSpacing.sm)) {
        for (turn in obj.turns) {
            Column {
                Text(turn.input, style = MaterialTheme.typography.bodyMedium)
                if (turn.clarifyingQuestion.isNotBlank()) {
                    Text(
                        turn.clarifyingQuestion,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The field at the bottom of an opened Discovery thread, to continue it --
 * mirrors Entry's own field/Go, but dispatches onContinueThread naming this
 * specific Thread's own id (threadId, read from the Object's own
 * discoveryThreadId), never whatever Thread the main Entry currently has
 * active (web.elonn.local's threadContinueFormNode()/continueDiscoveryThread()
 * use the same, deliberately separate, path). Clears after sending, unlike
 * Entry's own Go (constraint.androidxr_go_never_clears_query_20260927, which
 * is specific to Entry's field) -- matches web's own continueDiscoveryThread()
 * clearing its input after submit.
 */
@Composable
private fun ThreadContinueForm(threadId: String, onContinueThread: (String, String) -> Unit) {
    var reply by remember { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = ElonnSpacing.xs),
    ) {
        OutlinedTextField(
            value = reply,
            onValueChange = { reply = it },
            placeholder = { Text("Continue...") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            if (reply.isNotBlank() && threadId.isNotBlank()) {
                onContinueThread(threadId, reply)
                reply = ""
            }
        }) { Text("Send") }
    }
}

private fun domainOf(url: String): String = try {
    java.net.URI(url).host ?: url
} catch (e: Exception) {
    url
}

/** action.md: a simple action is a button; one with arguments gets a real form; a blocked one shows World's reason, not nothing. */
@Composable
private fun ActionLines(obj: WorldObject, state: RuntimeState, onDispatch: (String, JSONObject) -> Unit) {
    val actions = obj.actionIds.mapNotNull { state.actionsById[it] }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        for (action in actions) {
            when {
                action.isSimpleAction -> TextButton(onClick = { onDispatch(action.label, action.operationInvocation!!) }) { Text(action.label) }
                action.needsForm -> ActionForm(action, onDispatch)
                !action.enabled -> Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(action.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f))
                    if (action.reason.isNotBlank()) {
                        Text(action.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

/**
 * The same generic argument-schema rendering LoginView uses, for an ordinary
 * Object Action instead of identity.login -- web.elonn.local's operationForm /
 * submitOperationForm build the same way: `arguments` is only the field-spec
 * map used to render inputs, never resubmitted. Conductor's explicit-invocation
 * path (ServicePlanner::operationAndArguments) reads supplied values exclusively
 * from `operation_invocation.payload`, so a filled value must be merged into
 * `payload` -- any fields already there (e.g. conductor.clarify's own
 * original_call/clarification_round) have to survive alongside it, not be
 * dropped, and `arguments` must not be resubmitted at all.
 */
@Composable
private fun ActionForm(action: RuntimeAction, onDispatch: (String, JSONObject) -> Unit) {
    val invocation = action.operationInvocation ?: return
    val arguments = invocation.optJSONObject("arguments") ?: JSONObject()
    val keys = action.argumentKeys
    val fieldValues = remember(action.id) { keys.associateWith { mutableStateOf("") } }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(action.label, style = MaterialTheme.typography.labelLarge)
        for (key in keys) {
            val spec = arguments.optJSONObject(key) ?: JSONObject()
            var value by fieldValues.getValue(key)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(spec.optString("label", key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        TextButton(onClick = {
            val existingPayload = invocation.optJSONObject("payload")
            val payload = if (existingPayload != null) JSONObject(existingPayload.toString()) else JSONObject()
            for (key in keys) payload.put(key, fieldValues.getValue(key).value)
            val filledInvocation = JSONObject(invocation.toString()).apply {
                remove("arguments")
                put("payload", payload)
            }
            onDispatch(action.label, filledInvocation)
        }) { Text("Submit") }
    }
}

/**
 * A Finding, per finding.md/layout.md: a compact card referencing an Object,
 * not the Object itself. "A Finding does not duplicate the Object it
 * references" and "Focusing a Finding opens the Object [...] on Carry" --
 * the full Object (its Resources, its Actions, an embedded WebView) belongs
 * to that opened-on-Carry presentation (the FloatingWindow body in
 * SpaceView), never to its Results-pane row. Rendering an Object's full
 * Resources/Actions here was a real bug: searching "profile" surfaced the
 * member's account Dashboard Object as a Finding, and this row rendered
 * every one of its argument forms (Save profile, Save messaging preference,
 * Manage CalDAV passwords, Log out) inline in the results list instead of a
 * title/summary card the member taps to open.
 */
@Composable
private fun ObjectRow(
    obj: WorldObject,
    state: RuntimeState,
    onSelect: (String) -> Unit,
) {
    val selected = obj.id == state.selectedObjectId
    val sourceText = firstExternalReferenceText(obj, state)
    // decision.thumbnail_is_a_media_node_not_a_canonical_field_20261024: a compact card's own
    // preview image, the same first-eligible-image-media-node-in-content.body rule every
    // compact card uses -- not the Object's full Resources/Actions, which this row
    // deliberately never renders (see class doc comment above).
    val previewResource = firstImageMediaResource(obj, state)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(obj.id) }
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(ElonnRadius.card),
            )
            .border(
                ElonnBorderWidth,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(ElonnRadius.card),
            )
            .padding(vertical = ElonnSpacing.xs, horizontal = ElonnSpacing.sm),
    ) {
        if (previewResource != null) {
            RemoteImage(
                url = previewResource.source,
                maxHeight = 120.dp,
            )
        }
        Text(obj.title.ifBlank { obj.id }, style = MaterialTheme.typography.titleMedium)
        if (obj.meta.isNotBlank()) {
            Text(obj.meta, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (sourceText != null) {
            Text(
                sourceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * A collection-kind Finding (finding.md), e.g. Maps' results for a query -- a titled group of
 * Object rows, not a single Object. Mirrors web.elonn.local's collectionNode(): title, summary
 * (when there's content to summarize), then each contained Object as its own row.
 */
@Composable
private fun CollectionFinding(
    collection: WorldCollection,
    state: RuntimeState,
    onSelect: (String) -> Unit,
) {
    val items = collection.itemIds.mapNotNull { state.objectsById[it] }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ElonnSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(ElonnSpacing.xs),
    ) {
        if (collection.title.isNotBlank()) {
            Text(collection.title, style = MaterialTheme.typography.titleSmall)
        }
        if (collection.summary.isNotBlank() && items.isNotEmpty()) {
            Text(collection.summary, style = MaterialTheme.typography.bodySmall)
        }
        if (items.isEmpty()) {
            Text("No results.", style = MaterialTheme.typography.bodySmall)
        } else {
            items.forEach { obj -> ObjectRow(obj, state, onSelect) }
        }
    }
}

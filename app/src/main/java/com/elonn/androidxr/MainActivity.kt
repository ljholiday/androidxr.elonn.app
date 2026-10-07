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
import com.elonn.androidxr.core.PanelStore
import com.elonn.androidxr.core.RuntimeAction
import com.elonn.androidxr.core.RuntimeInterpreter
import com.elonn.androidxr.core.RuntimeState
import com.elonn.androidxr.core.TokenStore
import com.elonn.androidxr.core.WorldAuthRequiredException
import com.elonn.androidxr.core.WorldCallRequest
import com.elonn.androidxr.core.WorldClient
import com.elonn.androidxr.core.WorldObject
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The native Android XR runtime spike (decision.native_android_xr_candidate_runtime_20260924,
 * next_step.native_android_field_spike_20260924 on agents.elonn.com). A Runtime
 * owns presentation only -- it never decides what exists, only shows what
 * World's Dataset says exists (CLAUDE.md's Runtime definition; xreal.elonn.app's
 * RuntimeInterpreter is the reference this mirrors). Nothing here is Field-loop-
 * specific UI logic invented for this app: the login form renders generically
 * from an action's argument schema, and the Field screen renders generically
 * from Objects/Actions/Placements/Findings, same as every other Runtime.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this, Android reserves the status/navigation bars as solid
        // opaque chrome outside the app's own drawable area -- the "white
        // area on the bottom of the screen" caught live on-device. Field's
        // camera background is meant to be full-bleed; FieldView insets the
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
    data class Field(val state: RuntimeState) : Screen
    data class Failed(val message: String) : Screen
}

@Composable
private fun ElonnApp() {
    val context = LocalContext.current
    val auth = remember { AuthClient() }
    val world = remember {
        WorldClient(renderer = if (usesSpatialFieldPresentation(context)) "headset" else "phone")
    }
    val tokenStore = remember { TokenStore(context) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var token by remember { mutableStateOf<String?>(null) }
    // A World Call is in flight -- surfaced in Entry's header (a small spinner next to Go) so
    // tapping Go gives some immediate sign something is happening, not just a silent wait for the
    // next Dataset to arrive.
    var isBusy by remember { mutableStateOf(false) }
    // Bumped once a world.compose search Call completes (success or failure) -- EntryResultsWindow
    // uses a change in this to force the Results pane open, so a fresh search (or its error) is
    // never left hidden behind an already-collapsed pane.
    var searchCompletedCount by remember { mutableStateOf(0) }

    suspend fun loadLoginForm() {
        try {
            val dataset = auth.loadForm("login")
            val action = findLoginAction(dataset)
            screen = if (action != null) Screen.Login(action) else Screen.Failed("No login action in auth-form Dataset.")
        } catch (e: Exception) {
            screen = Screen.Failed(e.message ?: "Could not reach api.elonn.")
        }
    }

    suspend fun performCall(currentToken: String, datasetId: String?, request: WorldCallRequest) {
        isBusy = true
        try {
            val dataset = world.call(currentToken, datasetId, request)
            val state = RuntimeInterpreter.apply(dataset)
            android.util.Log.d(
                "ElonnField",
                "carry=${state.carry.objectIds} focus=${state.selectedObjectId} " +
                    "field=${state.field.objectIds} fieldCollections=${state.field.collectionIds} " +
                    "objectsById.size=${state.objectsById.size}",
            )
            screen = Screen.Field(state)
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

    suspend fun restoreField(currentToken: String) {
        performCall(currentToken, datasetId = null, WorldCallRequest(operation = "world.restore"))
    }

    LaunchedEffect(Unit) {
        val stored = tokenStore.load()
        if (stored != null) {
            token = stored
            restoreField(stored)
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
                if (currentToken != null) restoreField(currentToken) else loadLoginForm()
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
                        restoreField(accessToken)
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
        is Screen.Field -> FieldView(
            state = current.state,
            isBusy = isBusy,
            searchCompletedCount = searchCompletedCount,
            onSelect = { objectId ->
                val currentToken = token ?: return@FieldView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
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
                val currentToken = token ?: return@FieldView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        WorldCallRequest(
                            operation = "world.compose",
                            inputText = label,
                            operationInvocation = operationInvocation,
                        ),
                    )
                }
            },
            onClose = { objectId ->
                val currentToken = token ?: return@FieldView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        WorldCallRequest(operation = "world.close", inputText = "world.close", originObject = objectId),
                    )
                }
            },
            onBack = { objectId ->
                val currentToken = token ?: return@FieldView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        WorldCallRequest(operation = "world.back", inputText = "world.back", originObject = objectId),
                    )
                }
            },
            onSubmitFind = { query ->
                val currentToken = token ?: return@FieldView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        WorldCallRequest(operation = "world.compose", inputText = query),
                    )
                    searchCompletedCount++
                }
            },
            onClearResults = {
                val currentToken = token ?: return@FieldView
                scope.launch {
                    performCall(
                        currentToken,
                        current.state.datasetId,
                        WorldCallRequest(operation = "world.clear", inputText = "world.clear"),
                    )
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
 * layout.md and terminology/window.md. Field is a real camera passthrough
 * background with markers placed by GPS bearing/distance plus live compass
 * heading (Geo.kt) -- unaffected by window mechanics, since Field Placement
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
private fun FieldView(
    state: RuntimeState,
    isBusy: Boolean,
    searchCompletedCount: Int,
    onSelect: (String) -> Unit,
    onDispatch: (String, JSONObject) -> Unit,
    onClose: (String) -> Unit,
    onBack: (String) -> Unit,
    onSubmitFind: (String) -> Unit,
    onClearResults: () -> Unit,
) {
    // A Field Placement can name an Object directly, or a Collection
    // (e.g. maps.field's real markers live inside collection:maps:maps.field,
    // not as individual Placements) -- mirrors xreal.elonn.app's
    // ArFieldRenderer.Rebuild, which expands both the same way.
    val fieldObjects = (
        state.field.objectIds +
            state.field.collectionIds.flatMap { state.collectionsById[it]?.itemIds.orEmpty() }
        ).distinct().mapNotNull { state.objectsById[it] }
    val markerContent: @Composable (WorldObject, Double, Modifier) -> Unit = { obj, distanceMeters, markerModifier ->
        FieldMarker(
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
        searchCompletedCount = searchCompletedCount,
        onSelect = onSelect,
        onDispatch = onDispatch,
        onClose = onClose,
        onBack = onBack,
        onSubmitFind = onSubmitFind,
        onClearResults = onClearResults,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Headset: real Jetpack XR markers through SceneCore, with Carry in its own head-locked
        // panel. Phone: classic ARCore camera passthrough, with Carry inline. Both render the same
        // markers through markerContent and the same Carry content through CarryLayer.
        if (usesSpatialFieldPresentation(LocalContext.current)) {
            ArCoreField(
                fieldObjects = fieldObjects,
                modifier = Modifier.fillMaxSize(),
                markerContent = markerContent,
                carry = carry,
                carryContent = { CarryLayer(it) },
            )
        } else {
            ClassicArCoreField(
                fieldObjects = fieldObjects,
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
    val searchCompletedCount: Int,
    val onSelect: (String) -> Unit,
    val onDispatch: (String, JSONObject) -> Unit,
    val onClose: (String) -> Unit,
    val onBack: (String) -> Unit,
    val onSubmitFind: (String) -> Unit,
    val onClearResults: () -> Unit,
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
    val findingObjects = state.findings.filter { it.kind == "object" }.mapNotNull { state.objectsById[it.id] }

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
                findingObjects = findingObjects,
                state = state,
                isBusy = inputs.isBusy,
                searchCompletedCount = inputs.searchCompletedCount,
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
                                if (obj.summary.isNotBlank()) {
                                    Text(obj.summary, style = MaterialTheme.typography.bodyMedium)
                                }
                                ResourceLines(obj, state)
                                ActionLines(obj, state, inputs.onDispatch)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Entry and the Results pane as one window (layout.md, terminology/window.md):
 * Entry -- form field, submit, clear, show/hide (microphone omitted, this
 * Runtime has no voice input yet) -- is the window's header/topbar; the
 * Results pane (Findings) is its body. `closable = false`: this window must
 * always exist. Submitting dispatches world.compose with the typed text as
 * Call content (xreal.elonn.app's ElonnRuntimeApp.SubmitFind uses the same
 * contract). Clear dispatches world.clear, which empties the Results pane in
 * World's saved state, not just this screen. Collapsing (tap the header, or
 * the explicit show/hide button) is local-only and never touches World --
 * exactly layout.md's "does not hide Entry, no effect on Carry or Field."
 */
@Composable
private fun EntryResultsWindow(
    panelStore: PanelStore,
    boundsPx: IntSize,
    findingObjects: List<WorldObject>,
    state: RuntimeState,
    isBusy: Boolean,
    searchCompletedCount: Int,
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
        expandOnChangeOf = if (searchCompletedCount > 0) searchCompletedCount else null,
        header = { collapsed, toggleCollapsed ->
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { if (query.isNotBlank()) onSubmitFind(query) }) { Text("Go") }
            }
            if (findingObjects.isNotEmpty()) {
                TextButton(onClick = onClearResults) { Text("Clear") }
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
            if (findingObjects.isEmpty()) {
                Text("No results yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(ElonnSpacing.xs),
                ) {
                    items(findingObjects) { obj ->
                        ObjectRow(obj, state, onSelect)
                    }
                }
            }
        },
    )
}

/**
 * A Field marker card. Positioning is entirely the caller's concern now
 * (ArCoreField.kt's ArCoreField passes a Modifier that places this at the
 * real ARCore-tracked screen position for this object's Anchor, recomputed
 * every frame from the camera's own view/projection matrices) -- this
 * composable only renders the card, it does not know or care where it ends
 * up on screen.
 */
@Composable
private fun FieldMarker(
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
            .background(if (selected) selectedColor else unselectedColor, RoundedCornerShape(8.dp))
            .border(
                ElonnBorderWidth,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp),
            )
            .clickable { onSelect(obj.id) }
            .padding(ElonnSpacing.xs),
    ) {
        // Field markers are read at a distance inside a headset, so they use body-scale type.
        Text(obj.title.ifBlank { obj.id }, style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("${distanceMeters.toInt()}m away", style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
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
                resource.label.ifBlank { domainOf(resource.source) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
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
 * Object Action instead of identity.login -- web.elonn.local's operationForm
 * builds the same way: base invocation minus `arguments`, filled values
 * merged back in on submit.
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
            val filledArguments = JSONObject()
            for (key in keys) filledArguments.put(key, fieldValues.getValue(key).value)
            val filledInvocation = JSONObject(invocation.toString()).apply { put("arguments", filledArguments) }
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
 * FieldView), never to its Results-pane row. Rendering an Object's full
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(obj.id) }
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp),
            )
            .border(
                ElonnBorderWidth,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp),
            )
            .padding(vertical = ElonnSpacing.xs, horizontal = ElonnSpacing.sm),
    ) {
        Text(obj.title.ifBlank { obj.id }, style = MaterialTheme.typography.titleMedium)
        Text(obj.meta, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

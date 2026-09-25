package com.elonn.androidxr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.elonn.androidxr.core.AuthClient
import com.elonn.androidxr.core.WorldClient
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * First real milestone of the native Android XR runtime spike (see
 * decision.native_android_xr_candidate_runtime_20260924 on agents.elonn.com):
 * a genuine login against api.elonn's real auth-form Dataset, followed by a
 * real world.restore call against production World. No mock data anywhere.
 *
 * The login form is rendered generically from the action's argument schema --
 * the same "Conductor attaches the real argument schema, the runtime renders
 * a real form from it" pattern every other Runtime already follows, not a
 * hand-written email/password screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ElonnApp()
                }
            }
        }
    }
}

private sealed interface Screen {
    data object Loading : Screen
    data class Login(val action: JSONObject, val error: String? = null) : Screen
    data class Field(val dataset: JSONObject) : Screen
    data class Failed(val message: String) : Screen
}

@Composable
private fun ElonnApp() {
    val auth = remember { AuthClient() }
    val world = remember { WorldClient() }
    val scope = rememberCoroutineScopeSafe()

    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var token by remember { mutableStateOf<String?>(null) }

    suspend fun restoreField(currentToken: String) {
        try {
            val dataset = world.call(currentToken, "world.restore", null)
            screen = Screen.Field(dataset)
        } catch (e: Exception) {
            screen = Screen.Failed(e.message ?: "World request failed.")
        }
    }

    suspend fun loadLoginForm() {
        try {
            val dataset = auth.loadForm("login")
            val action = findLoginAction(dataset)
            screen = if (action != null) Screen.Login(action) else Screen.Failed("No login action in auth-form Dataset.")
        } catch (e: Exception) {
            screen = Screen.Failed(e.message ?: "Could not reach api.elonn.")
        }
    }

    LaunchedEffect(Unit) { loadLoginForm() }

    when (val current = screen) {
        is Screen.Loading -> LoadingView()
        is Screen.Failed -> ErrorView(current.message)
        is Screen.Login -> LoginView(current.action, current.error) { values ->
            scope.launch {
                screen = Screen.Loading
                try {
                    val invocation = current.action.getJSONObject("content").getJSONObject("operation_invocation")
                    val path = invocation.getJSONObject("submit").getString("path")
                    val result = auth.submit(path, values)
                    val accessToken = result.optJSONObject("context")?.optString("access_token").orEmpty()
                    if (accessToken.isNotBlank()) {
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
        is Screen.Field -> FieldView(current.dataset)
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
private fun ErrorView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Elonn", style = MaterialTheme.typography.headlineMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
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
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

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

@Composable
private fun FieldView(dataset: JSONObject) {
    val objects = dataset.optJSONArray("objects")
    val rows = remember(dataset) {
        buildList {
            if (objects != null) {
                for (i in 0 until objects.length()) {
                    val obj = objects.getJSONObject(i)
                    add(Triple(obj.optString("id"), obj.optString("type"), obj.optString("title")))
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Elonn Field", style = MaterialTheme.typography.headlineMedium)
        Text(
            "world.restore -- ${rows.size} object(s) from a real World Dataset",
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))

        if (rows.isEmpty()) {
            Text("No objects in this Dataset yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn {
                items(rows) { (id, type, title) ->
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(title.ifBlank { id }, style = MaterialTheme.typography.titleMedium)
                        Text(type, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberCoroutineScopeSafe() = androidx.compose.runtime.rememberCoroutineScope()

package com.vrticconnect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vrticconnect.core.api.ApiProblem
import com.vrticconnect.core.api.ApiResult
import com.vrticconnect.core.auth.AuthState
import com.vrticconnect.core.i18n.AppLocale
import com.vrticconnect.core.i18n.AppStrings
import com.vrticconnect.core.i18n.StringKey
import kotlinx.coroutines.launch

/** Result of the last "Check API" press. */
sealed interface ApiCheckState {
    data object Idle : ApiCheckState
    data object Checking : ApiCheckState
    data object Ok : ApiCheckState
    data class Unavailable(val problem: ApiProblem) : ApiCheckState
}

/**
 * Root composable shared by Android and iOS.
 *
 * Deliberately ViewModel-free: local UI state via `remember`, shared state via StateFlow
 * ([AuthState] from the coordinator).
 */
@Composable
fun App(environment: AppEnvironment = remember { AppEnvironment() }) {
    var locale by remember { mutableStateOf(AppLocale.DEFAULT) }
    var apiCheck by remember { mutableStateOf<ApiCheckState>(ApiCheckState.Idle) }
    val authState by environment.authCoordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val strings = remember(locale) { AppStrings.forLocale(locale) }

    MaterialTheme {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .safeContentPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = strings[StringKey.APP_NAME],
                    style = MaterialTheme.typography.headlineMedium,
                )

                Text(text = strings[StringKey.LANGUAGE], style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLocale.entries.forEach { candidate ->
                        FilterChip(
                            selected = candidate == locale,
                            onClick = { locale = candidate },
                            label = { Text(candidate.nativeName) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        apiCheck = ApiCheckState.Checking
                        scope.launch {
                            apiCheck = when (val result = environment.healthApi.ready()) {
                                is ApiResult.Success -> ApiCheckState.Ok
                                is ApiResult.Failure -> ApiCheckState.Unavailable(result.problem)
                            }
                        }
                    },
                    enabled = apiCheck != ApiCheckState.Checking,
                ) {
                    Text(strings[StringKey.CHECK_API])
                }

                Text(
                    text = when (val state = apiCheck) {
                        ApiCheckState.Idle -> ""
                        ApiCheckState.Checking -> strings[StringKey.CHECKING]
                        ApiCheckState.Ok -> strings[StringKey.API_AVAILABLE]
                        is ApiCheckState.Unavailable ->
                            strings[StringKey.API_UNAVAILABLE] + "\n" + state.problem.summary()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(8.dp))

                // Login is intentionally disabled: the backend returns 501 and secure token
                // storage (Keychain/Keystore) is EPIC 02 work.
                Button(onClick = {}, enabled = false) {
                    Text("${strings[StringKey.LOGIN]} – ${strings[StringKey.NOT_IMPLEMENTED_YET]}")
                }

                if (authState is AuthState.Authenticated) {
                    // Unreachable in the skeleton; kept so the StateFlow binding is exercised.
                    Text(strings[StringKey.TODAY])
                }
            }
        }
    }
}

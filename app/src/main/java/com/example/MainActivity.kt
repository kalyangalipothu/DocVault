package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.authStateFlow
import com.example.auth.signOutUser
import com.example.core.DarkModeOption
import com.example.core.PreferencesRepository
import com.example.core.UserAppPreferences
import com.example.core.configureAppCheck
import com.example.data.GeminiAiService
import com.example.data.VaultRepository
import com.example.ui.AuthScreen
import com.example.ui.BiometricLockScreen
import com.example.ui.VaultAppShell
import com.example.ui.theme.DocVaultTheme
import com.example.viewmodel.VaultViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent BEFORE UI, ViewModels, or Firebase operations.
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            DocVaultRoot()
        }
    }
}

@Composable
private fun DocVaultRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferencesRepo = remember(context) { PreferencesRepository(context.applicationContext) }
    val prefs by preferencesRepo.preferencesFlow.collectAsStateWithLifecycle(
        initialValue = UserAppPreferences()
    )

    val isDarkActive = when (prefs.darkMode) {
        DarkModeOption.SYSTEM -> isSystemInDarkTheme()
        DarkModeOption.LIGHT -> false
        DarkModeOption.DARK -> true
    }

    DocVaultTheme(darkTheme = isDarkActive) {
        val currentUser by Firebase.auth.authStateFlow().collectAsStateWithLifecycle(
            initialValue = Firebase.auth.currentUser
        )
        val user = currentUser

        if (user == null) {
            AuthScreen(onAuthSuccess = { /* AuthStateListener updates currentUser reactively */ })
        } else {
            val uid = user.uid
            var biometricUnlocked by rememberSaveable(uid) { mutableStateOf(false) }

            if (prefs.biometricAppLock && !biometricUnlocked) {
                BiometricLockScreen(
                    userEmail = user.email ?: "your account",
                    onUnlocked = { biometricUnlocked = true }
                )
            } else {
                // Key composition and ViewModel by uid so sign-out / account switch disposes listeners cleanly
                key(uid) {
                    val credentialManager = remember(context) { CredentialManager.create(context) }
                    val vaultViewModel: VaultViewModel = viewModel(
                        key = uid,
                        factory = viewModelFactory {
                            initializer {
                                val app = checkNotNull(this[APPLICATION_KEY]) {
                                    "APPLICATION_KEY missing from CreationExtras"
                                }
                                val databaseId = app.getString(R.string.firestore_database_id)
                                val db = FirebaseFirestore.getInstance(databaseId)
                                VaultViewModel(
                                    repository = VaultRepository(db),
                                    aiService = GeminiAiService(),
                                    preferencesRepository = PreferencesRepository(app),
                                    currentUserId = uid
                                )
                            }
                        }
                    )

                    VaultAppShell(
                        viewModel = vaultViewModel,
                        isDarkActive = isDarkActive,
                        onSignOut = {
                            biometricUnlocked = false
                            signOutUser(credentialManager, scope)
                        }
                    )
                }
            }
        }
    }
}

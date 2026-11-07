package com.minichat.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.minichat.app.RootViewModel
import com.minichat.app.domain.repository.AuthState
import com.minichat.app.feature.auth.ForgotPasswordScreen
import com.minichat.app.feature.auth.LoginScreen
import com.minichat.app.feature.auth.RegisterScreen
import com.minichat.app.feature.auth.ResetPasswordScreen
import com.minichat.app.feature.chat.ChatScreen
import com.minichat.app.feature.contacts.DiscoverScreen
import com.minichat.app.feature.home.HomeScreen
import com.minichat.app.feature.profile.EditProfileScreen
import com.minichat.app.feature.profile.ProfileScreen
import com.minichat.app.feature.profile.UserProfileScreen
import com.minichat.app.feature.settings.AboutScreen
import com.minichat.app.feature.settings.AppearanceScreen
import com.minichat.app.feature.settings.BlockedUsersScreen
import com.minichat.app.feature.settings.NotificationSettingsScreen
import com.minichat.app.feature.settings.PrivacyScreen
import com.minichat.app.feature.settings.SettingsScreen
import com.minichat.app.feature.welcome.WelcomeScreen

private const val TRANSITION_MS = 260

@Composable
fun MiniChatNavHost(
    rootViewModel: RootViewModel,
    deepLinkConversationId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val state by rootViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Auth gate: the back stack never keeps a signed-out screen after sign in (or vice versa).
    LaunchedEffect(state.authState) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        when (state.authState) {
            is AuthState.SignedIn -> {
                val isAuthRoute = currentRoute == null || currentRoute in setOf(
                    Routes.WELCOME,
                    Routes.LOGIN,
                    Routes.REGISTER,
                    Routes.FORGOT_PASSWORD,
                    Routes.RESET_PASSWORD,
                )
                if (isAuthRoute) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            AuthState.SignedOut -> {
                val isProtected = currentRoute != null && currentRoute !in setOf(
                    Routes.WELCOME,
                    Routes.LOGIN,
                    Routes.REGISTER,
                    Routes.FORGOT_PASSWORD,
                    Routes.RESET_PASSWORD,
                )
                if (isProtected || currentRoute == null) {
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            AuthState.Loading -> Unit
        }
    }

    LaunchedEffect(deepLinkConversationId) {
        if (deepLinkConversationId != null && state.authState is AuthState.SignedIn) {
            navController.navigate(Routes.chat(deepLinkConversationId)) { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(TRANSITION_MS),
            ) + fadeIn(tween(TRANSITION_MS))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(TRANSITION_MS),
            ) + fadeOut(tween(TRANSITION_MS))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(TRANSITION_MS),
            ) + fadeIn(tween(TRANSITION_MS))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(TRANSITION_MS),
            ) + fadeOut(tween(TRANSITION_MS))
        },
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onCreateAccount = { navController.navigate(Routes.REGISTER) },
                onSignIn = { navController.navigate(Routes.LOGIN) },
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onBack = { navController.popBackStack() },
                onSignedIn = { navController.navigate(Routes.HOME) { popUpTo(0) } },
                onForgotPassword = { navController.navigate(Routes.FORGOT_PASSWORD) },
                onRegister = { navController.navigate(Routes.REGISTER) },
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onBack = { navController.popBackStack() },
                onRegistered = { navController.navigate(Routes.HOME) { popUpTo(0) } },
                onSignIn = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.REGISTER) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.FORGOT_PASSWORD) {
            ForgotPasswordScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.RESET_PASSWORD,
            arguments = listOf(navArgument("email") { type = NavType.StringType; defaultValue = "" }),
        ) {
            ResetPasswordScreen(
                onDone = {
                    navController.navigate(Routes.LOGIN) { popUpTo(0) }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onOpenConversation = { id -> navController.navigate(Routes.chat(id)) },
                onOpenProfile = { navController.navigate(Routes.MY_PROFILE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onNewChat = { navController.navigate(Routes.DISCOVER) },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) {
            ChatScreen(
                onBack = { navController.popBackStack() },
                onOpenProfile = { userId -> navController.navigate(Routes.userProfile(userId)) },
            )
        }

        composable(Routes.DISCOVER) {
            DiscoverScreen(
                onBack = { navController.popBackStack() },
                onOpenConversation = { id ->
                    navController.navigate(Routes.chat(id)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onOpenProfile = { userId -> navController.navigate(Routes.userProfile(userId)) },
            )
        }

        composable(Routes.MY_PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onEditProfile = { navController.navigate(Routes.EDIT_PROFILE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.USER_PROFILE,
            arguments = listOf(navArgument("userId") { type = NavType.StringType }),
        ) {
            UserProfileScreen(
                onBack = { navController.popBackStack() },
                onMessage = { userId ->
                    navController.navigate(Routes.DISCOVER) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.EDIT_PROFILE) {
            EditProfileScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onEditProfile = { navController.navigate(Routes.EDIT_PROFILE) },
                onAppearance = { navController.navigate(Routes.APPEARANCE) },
                onNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                onPrivacy = { navController.navigate(Routes.PRIVACY) },
                onBlockedUsers = { navController.navigate(Routes.BLOCKED_USERS) },
                onAbout = { navController.navigate(Routes.ABOUT) },
            )
        }

        composable(Routes.APPEARANCE) { AppearanceScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.NOTIFICATIONS) { NotificationSettingsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.PRIVACY) {
            PrivacyScreen(
                onBack = { navController.popBackStack() },
                onBlockedUsers = { navController.navigate(Routes.BLOCKED_USERS) },
            )
        }
        composable(Routes.BLOCKED_USERS) { BlockedUsersScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }
    }
}

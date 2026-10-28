package com.zhixu.studyos.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zhixu.studyos.core.designsystem.theme.StudyOSTheme
import com.zhixu.studyos.core.model.ThemePreference
import com.zhixu.studyos.di.AppContainer

@Composable
fun StudyOSApp(
    container: AppContainer,
    initialDestination: String? = null,
    initialSection: String? = null,
) {
    val themePreference by container.userPreferencesRepository.themePreference
        .collectAsStateWithLifecycle(initialValue = ThemePreference.SYSTEM)
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    StudyOSTheme(darkTheme = darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            val topLevelRoutes = StudyOSDestination.entries.map { it.route }.toSet()
            val showBottomBar = currentRoute == null || currentRoute in topLevelRoutes

            Scaffold(
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar {
                            StudyOSDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav_${destination.route}"),
                                    icon = {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text(stringResource(destination.labelRes)) },
                                    selected = when (destination) {
                                        StudyOSDestination.RECORDS ->
                                            currentRoute?.startsWith("records") == true
                                        else -> currentRoute == destination.route
                                    },
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                StudyOSNavHost(
                    container = container,
                    navController = navController,
                    initialDestination = initialDestination,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}

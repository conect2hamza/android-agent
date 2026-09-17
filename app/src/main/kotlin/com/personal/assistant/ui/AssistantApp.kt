package com.personal.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.personal.assistant.AppContainer
import com.personal.assistant.data.repository.AppSettings
import com.personal.assistant.ui.calendar.CalendarScreen
import com.personal.assistant.ui.chat.ChatScreen
import com.personal.assistant.ui.dashboard.DashboardScreen
import com.personal.assistant.ui.memory.MemoryScreen
import com.personal.assistant.ui.reports.ReportsScreen
import com.personal.assistant.ui.settings.SettingsScreen
import com.personal.assistant.ui.tasks.TaskDetailScreen
import com.personal.assistant.ui.tasks.TasksScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector)

/**
 * Chat is the start destination.
 *
 * The specification is explicit that the product should feel like talking to an assistant rather than
 * operating a management tool, and the start destination is most of that feeling. The other screens are
 * there for the things a conversation is bad at -- scanning a month, comparing two weeks, auditing what
 * the assistant has remembered.
 */
private val BOTTOM_DESTINATIONS = listOf(
    // Drawn from the core icon set so the extended one does not have to ship; a few are
    // approximations of what the tab actually does.
    Destination("chat", "Chat", Icons.Filled.Email),
    Destination("home", "Home", Icons.Filled.Home),
    Destination("calendar", "Calendar", Icons.Filled.DateRange),
    Destination("tasks", "Tasks", Icons.Filled.CheckCircle),
    Destination("reports", "Reports", Icons.Filled.Info),
)

// Memory and Settings are reached from the Home screen rather than the bar. Material's navigation
// bar is specified for three to five destinations, and squeezing seven in makes every label unreadable
// on a mid-range phone -- which is exactly the hardware this app targets.

@Composable
fun AssistantApp(
    container: AppContainer,
    settings: AppSettings,
    openTaskId: Long? = null,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Tapping a notification opens the task it refers to rather than dropping the user on the chat.
    LaunchedEffect(openTaskId) {
        openTaskId?.let { navController.navigate("task/$it") }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                BOTTOM_DESTINATIONS.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(destination.route) {
                                    // A tab switch replaces the tab, rather than stacking history the
                                    // back button then has to unwind one screen at a time.
                                    popUpTo("chat") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = navController, startDestination = "chat") {
                composable("chat") {
                    ChatScreen(container, settings.use24HourClock)
                }
                composable("home") {
                    DashboardScreen(
                        container = container,
                        use24HourClock = settings.use24HourClock,
                        onOpenTask = { id -> navController.navigate("task/$id") },
                        onOpenMemory = { navController.navigate("memory") },
                        onOpenSettings = { navController.navigate("settings") },
                    )
                }
                composable("calendar") {
                    CalendarScreen(container, settings.use24HourClock) { id ->
                        navController.navigate("task/$id")
                    }
                }
                composable("tasks") {
                    TasksScreen(container, settings.use24HourClock) { id ->
                        navController.navigate("task/$id")
                    }
                }
                composable("reports") { ReportsScreen(container) }
                composable("memory") { MemoryScreen(container) }
                composable("settings") { SettingsScreen(container) }
                composable(
                    route = "task/{taskId}",
                    arguments = listOf(navArgument("taskId") { type = NavType.LongType }),
                ) { entry ->
                    TaskDetailScreen(
                        container = container,
                        taskId = entry.arguments?.getLong("taskId") ?: 0L,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

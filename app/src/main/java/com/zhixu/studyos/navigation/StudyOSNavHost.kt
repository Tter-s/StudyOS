package com.zhixu.studyos.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.zhixu.studyos.di.AppContainer
import com.zhixu.studyos.feature.backup.DataBackupViewModel
import com.zhixu.studyos.feature.finance.FinanceHomeScreen
import com.zhixu.studyos.feature.finance.FinanceViewModel
import com.zhixu.studyos.feature.profile.MineScreen
import com.zhixu.studyos.feature.profile.MineViewModel
import com.zhixu.studyos.feature.records.DailySummaryScreen
import com.zhixu.studyos.feature.records.DailySummaryViewModel
import com.zhixu.studyos.feature.records.EntertainmentScreen
import com.zhixu.studyos.feature.records.EntertainmentViewModel
import com.zhixu.studyos.feature.records.HistoryScreen
import com.zhixu.studyos.feature.records.HistoryViewModel
import com.zhixu.studyos.feature.records.RecordsHubViewModel
import com.zhixu.studyos.feature.records.RecordsScreen
import com.zhixu.studyos.feature.records.SleepLogScreen
import com.zhixu.studyos.feature.records.SleepLogViewModel
import com.zhixu.studyos.feature.schedule.ScheduleImportViewModel
import com.zhixu.studyos.feature.schedule.ScheduleScreen
import com.zhixu.studyos.feature.schedule.ScheduleViewModel
import com.zhixu.studyos.feature.today.DayTasksScreen
import com.zhixu.studyos.feature.today.TodayScreen
import com.zhixu.studyos.feature.today.TodayViewModel
import java.time.LocalDate

object StudyOSRoutes {
    const val DAY = "day/{date}"
    const val SUMMARY = "records/summary?date={date}"
    const val SLEEP = "records/sleep"
    const val ENTERTAINMENT = "records/entertainment"
    const val HISTORY = "records/history"

    fun day(date: LocalDate): String = "day/$date"
    fun summary(date: LocalDate): String = "records/summary?date=$date"
}

@Composable
fun StudyOSNavHost(
    container: AppContainer,
    navController: NavHostController,
    modifier: Modifier = Modifier,
    initialDestination: String? = null,
) {
    val startDestination = initialDestination
        ?.takeIf { route -> StudyOSDestination.entries.any { it.route == route } }
        ?: StudyOSDestination.HOME.route

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(StudyOSDestination.HOME.route) {
            val viewModel: TodayViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        TodayViewModel(
                            repository = container.dailyLogRepository,
                            clock = container.clock,
                        )
                    }
                },
            )
            TodayScreen(
                viewModel = viewModel,
                onOpenTomorrow = {
                    navController.navigate(
                        StudyOSRoutes.day(LocalDate.now(container.clock).plusDays(1)),
                    )
                },
            )
        }

        composable(
            route = StudyOSRoutes.DAY,
            arguments = listOf(navArgument("date") { type = NavType.StringType }),
        ) { entry ->
            val date = remember(entry.arguments?.getString("date")) {
                runCatching { LocalDate.parse(entry.arguments?.getString("date")) }
                    .getOrDefault(LocalDate.now(container.clock).plusDays(1))
            }
            val viewModel: TodayViewModel = viewModel(
                key = "day-$date",
                factory = viewModelFactory {
                    initializer {
                        TodayViewModel(
                            repository = container.dailyLogRepository,
                            clock = container.clock,
                            initialDate = date,
                            followToday = false,
                        )
                    }
                },
            )
            DayTasksScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(StudyOSDestination.SCHEDULE.route) {
            val scheduleViewModel: ScheduleViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ScheduleViewModel(
                            repository = container.scheduleRepository,
                            clock = container.clock,
                        )
                    }
                },
            )
            val importViewModel: ScheduleImportViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ScheduleImportViewModel(
                            repository = container.scheduleRepository,
                            importService = container.scheduleImportService,
                            clock = container.clock,
                        )
                    }
                },
            )
            ScheduleScreen(
                viewModel = scheduleViewModel,
                importViewModel = importViewModel,
            )
        }

        composable(StudyOSDestination.RECORDS.route) {
            val viewModel: RecordsHubViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        RecordsHubViewModel(
                            repository = container.dailyLogRepository,
                            sleepRepository = container.sleepRepository,
                            clock = container.clock,
                        )
                    }
                },
            )
            RecordsScreen(
                viewModel = viewModel,
                onOpenSummary = {
                    navController.navigate(StudyOSRoutes.summary(LocalDate.now(container.clock)))
                },
                onOpenSleep = { navController.navigate(StudyOSRoutes.SLEEP) },
                onOpenEntertainment = { navController.navigate(StudyOSRoutes.ENTERTAINMENT) },
                onOpenHistory = { navController.navigate(StudyOSRoutes.HISTORY) },
            )
        }

        composable(
            route = StudyOSRoutes.SUMMARY,
            arguments = listOf(
                navArgument("date") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val date = remember(entry.arguments?.getString("date")) {
                runCatching { LocalDate.parse(entry.arguments?.getString("date")) }
                    .getOrDefault(LocalDate.now(container.clock))
            }
            val viewModel: DailySummaryViewModel = viewModel(
                key = "summary-$date",
                factory = viewModelFactory {
                    initializer {
                        DailySummaryViewModel(
                            repository = container.dailyLogRepository,
                            clock = container.clock,
                            initialDate = date,
                        )
                    }
                },
            )
            DailySummaryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(StudyOSRoutes.SLEEP) {
            val viewModel: SleepLogViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SleepLogViewModel(
                            repository = container.sleepRepository,
                            clock = container.clock,
                        )
                    }
                },
            )
            SleepLogScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(StudyOSRoutes.ENTERTAINMENT) {
            val viewModel: EntertainmentViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        EntertainmentViewModel(
                            repository = container.dailyLogRepository,
                            clock = container.clock,
                        )
                    }
                },
            )
            EntertainmentScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(StudyOSRoutes.HISTORY) {
            val viewModel: HistoryViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        HistoryViewModel(
                            repository = container.dailyLogRepository,
                            sleepRepository = container.sleepRepository,
                        )
                    }
                },
            )
            HistoryScreen(
                viewModel = viewModel,
                onOpenDay = { date ->
                    navController.navigate(StudyOSRoutes.summary(date))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(StudyOSDestination.FINANCE.route) {
            val viewModel: FinanceViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        FinanceViewModel(
                            repository = container.financeRepository,
                            clock = container.clock,
                            documentStore = container.financeDocumentStore,
                            preferences = container.userPreferencesRepository,
                            reminderScheduler = container.budgetReminderScheduler,
                            appVersion = container.appVersion,
                        )
                    }
                },
            )
            FinanceHomeScreen(viewModel = viewModel)
        }

        composable(StudyOSDestination.PROFILE.route) {
            val mineViewModel: MineViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MineViewModel(preferences = container.userPreferencesRepository)
                    }
                },
            )
            val backupViewModel: DataBackupViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        DataBackupViewModel(
                            backupService = container.backupService,
                            exportService = container.dataExportService,
                        )
                    }
                },
            )
            MineScreen(
                viewModel = mineViewModel,
                backupViewModel = backupViewModel,
            )
        }
    }
}

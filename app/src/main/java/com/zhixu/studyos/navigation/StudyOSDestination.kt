package com.zhixu.studyos.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.zhixu.studyos.R

enum class StudyOSDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Rounded.Home),
    SCHEDULE("schedule", R.string.nav_schedule, Icons.Rounded.CalendarMonth),
    RECORDS("records", R.string.nav_records, Icons.Rounded.EditNote),
    FINANCE("finance", R.string.nav_finance, Icons.Rounded.AccountBalanceWallet),
    PROFILE("profile", R.string.nav_profile, Icons.Rounded.Person),
}

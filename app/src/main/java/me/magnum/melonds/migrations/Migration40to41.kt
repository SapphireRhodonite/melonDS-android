package me.magnum.melonds.migrations

import android.content.SharedPreferences
import androidx.core.content.edit

class Migration40to41(private val sharedPreferences: SharedPreferences) : Migration {

    override val from = 40
    override val to = 41

    override fun migrate() {
        // An issue was found in the existing RetroAchievements integration where usernames would be stored with leading or trailing whitespaces. The backend would trim them
        // and accept them as valid, but it was stored in the shared preferences as input by the user. This would lead to invalid RetroAchievements signatures being generated
        val raUsername = sharedPreferences.getString("ra_username", null)
        if (raUsername != null && raUsername.trim() != raUsername) {
            sharedPreferences.edit {
                putString("ra_username", raUsername.trim())
            }
        }
    }
}
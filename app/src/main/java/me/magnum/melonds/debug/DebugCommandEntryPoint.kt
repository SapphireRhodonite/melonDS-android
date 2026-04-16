package me.magnum.melonds.debug

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import me.magnum.melonds.domain.repositories.RomsRepository
import me.magnum.melonds.domain.repositories.SaveStatesRepository
import me.magnum.melonds.domain.repositories.SettingsRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DebugCommandEntryPoint {
    fun sharedPreferences(): SharedPreferences
    fun settingsRepository(): SettingsRepository
    fun romsRepository(): RomsRepository
    fun saveStatesRepository(): SaveStatesRepository

    companion object {
        fun resolve(context: Context): DebugCommandEntryPoint {
            val applicationContext = context.applicationContext ?: throw IllegalStateException()
            return EntryPointAccessors.fromApplication(
                applicationContext,
                DebugCommandEntryPoint::class.java,
            )
        }
    }
}

/*
 * Copyright (C) 2023-25 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioPlaybackCallback
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.util.Log
import androidx.preference.PreferenceManager
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import co.aospa.dolby.xiaomi.DolbyConstants.DsParam
import co.aospa.dolby.xiaomi.R

internal class DolbyController private constructor(
    private val context: Context
) {
    private var dolbyEffect: DolbyAudioEffect? = try {
        DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create DolbyAudioEffect", e)
        null
    }
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val handler = Handler(context.mainLooper)
    private val stereoWideningSupported =
        context.getResources().getBoolean(R.bool.dolby_stereo_widening_supported)
    private val volumeLevelerSupported =
        context.getResources().getBoolean(R.bool.dolby_volume_leveler_supported)

    // Restore current profile on every media session
    private val playbackCallback = object : AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val isPlaying = configs.any {
                it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
            }
            dlog(TAG, "onPlaybackConfigChanged: isPlaying=$isPlaying")
            if (isPlaying)
                setCurrentProfile()
        }
    }

    // Restore current profile on audio device change
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesAdded")
            setCurrentProfile()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesRemoved")
            setCurrentProfile()
        }
    }

    private var registerCallbacks = false
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setRegisterCallbacks($value)")
            if (value) {
                audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            } else {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        }

    var dsOn: Boolean
        get() =
            try {
                dolbyEffect?.dsOn?.also {
                    dlog(TAG, "getDsOn: $it")
                } ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get dsOn", e)
                false
            }
        set(value) {
            dlog(TAG, "setDsOn: $value")
            if (checkEffect()) {
                try {
                    dolbyEffect?.dsOn = value
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set dsOn", e)
                }
            }
            registerCallbacks = value
            if (value)
                setCurrentProfile()
        }

    var profile: Int
        get() =
            try {
                dolbyEffect?.profile?.also {
                    dlog(TAG, "getProfile: $it")
                } ?: 0
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get profile", e)
                0
            }
        set(value) {
            dlog(TAG, "setProfile: $value")
            if (checkEffect()) {
                try {
                    dolbyEffect?.profile = value
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set profile", e)
                }
            }
        }

    init {
        // Restore our main settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        dsOn = prefs.getBoolean(DolbyConstants.PREF_ENABLE, true)

        if (dolbyEffect != null) {
            context.resources.getStringArray(R.array.dolby_profile_values)
                .map { it.toInt() }
                .forEach { profile ->
                    // Reset dolby first to prevent it from loading bad settings
                    try {
                        dolbyEffect?.resetProfileSpecificSettings(profile)
                        // Now restore our profile-specific settings
                        restoreSettings(profile)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore settings for profile $profile", e)
                    }
                }

            // Finally restore the current profile.
            try {
                setCurrentProfile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setCurrentProfile", e)
            }
        }

        dlog(TAG, "initialized")
    }

    fun onBootCompleted () {
        dlog(TAG, "onBootCompleted()")

        // Migrate presets from credential protected storage if needed
        maybeMigratePresets()
    }

    private fun restoreSettings(profile: Int) {
        dlog(TAG, "restoreSettings(profile=$profile)")
        val prefs = context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)
        setPreset(
            prefs.getString(DolbyConstants.PREF_PRESET, getPreset(profile))!!,
            profile
        )
        setBassEnhancerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_BASS, getBassEnhancerEnabled(profile)),
            profile
        )
        setVolumeLevelerEnabled(
            // force disable if unsupported, else force enable on dynamic profile
            volumeLevelerSupported && (profile == 0 ||
                prefs.getBoolean(DolbyConstants.PREF_VOLUME, getVolumeLevelerEnabled(profile))),
            profile
        )
        if (profile == 0) {
            // below settings are not applicable for dynamic
            return
        }
        setIeqPreset(
            prefs.getString(
                DolbyConstants.PREF_IEQ,
                getIeqPreset(profile).toString()
            )!!.toInt(),
            profile
        )
        setHeadphoneVirtEnabled(
            prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, getHeadphoneVirtEnabled(profile)),
            profile
        )
        setSpeakerVirtEnabled(
            prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, getSpeakerVirtEnabled(profile)),
            profile
        )
        setStereoWideningAmount(
            prefs.getInt(
                DolbyConstants.PREF_STEREO_WIDENING,
                getStereoWideningAmount(profile)
            )!!.toInt(),
            profile
        )
        setDialogueEnhancerEnabled(
            prefs.getBoolean(
                DolbyConstants.PREF_DIALOGUE,
                getDialogueEnhancerEnabled(profile)
            ),
            profile
        )
        setDialogueEnhancerAmount(
            prefs.getInt(
                DolbyConstants.PREF_DIALOGUE_AMOUNT,
                getDialogueEnhancerAmount(profile)
            )!!.toInt(),
            profile
        )
    }

    private fun maybeMigratePresets() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_KEY_PRESETS_MIGRATED, false)) {
            return
        }
        val ceContext = context.createCredentialProtectedStorageContext()
        val cePrefs = ceContext.getSharedPreferences(PREF_PRESETS, Context.MODE_PRIVATE)
        if (cePrefs.all.isEmpty()) {
            dlog(TAG, "no presets to migrate")
            return
        }
        if (context.moveSharedPreferencesFrom(ceContext, PREF_PRESETS)) {
            prefs.edit().putBoolean(PREF_KEY_PRESETS_MIGRATED, true).apply()
            dlog(TAG, "presets migrated successfully")
        } else {
            Log.w(TAG, "failed to migrate presets")
        }
    }

    private fun checkEffect(): Boolean {
        val effect = dolbyEffect
        if (effect == null || !effect.hasControl()) {
            Log.w(TAG, "lost control or effect null, recreating effect")
            try {
                effect?.release()
                dolbyEffect = DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recreate DolbyAudioEffect", e)
                dolbyEffect = null
                return false
            }
        }
        return true
    }

    private fun setCurrentProfile() {
        dlog(TAG, "setCurrentProfile")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        profile = prefs.getString(DolbyConstants.PREF_PROFILE, "0" /*dynamic*/)!!.toInt()
    }

    fun setDsOnAndPersist(dsOn: Boolean) {
        this.dsOn = dsOn
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(DolbyConstants.PREF_ENABLE, dsOn)
            .apply()
    }

    fun getProfileName(): String? {
        val profile = this.profile.toString()
        val profiles = context.resources.getStringArray(R.array.dolby_profile_values)
        val profileIndex = profiles.indexOf(profile)
        dlog(TAG, "getProfileName: profile=$profile index=$profileIndex")
        return if (profileIndex == -1) null else context.resources.getStringArray(
            R.array.dolby_profile_entries
        )[profileIndex]
    }

    fun resetProfileSpecificSettings(profile: Int = this.profile) {
        dlog(TAG, "resetProfileSpecificSettings($profile)")
        if (checkEffect()) {
            try {
                dolbyEffect?.resetProfileSpecificSettings(profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resetProfileSpecificSettings", e)
            }
        }
        context.deleteSharedPreferences("profile_$profile")
    }

    fun resetAllProfiles() {
        dlog(TAG, "resetAllProfiles")
        checkEffect()
        // Reset profile-specific settings
        context.resources.getStringArray(R.array.dolby_profile_values)
            .map { it.toInt() }
            .forEach { resetProfileSpecificSettings(it) }
        // Set default dynamic profile
        profile = 0
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(DolbyConstants.PREF_PROFILE, "0")
            .apply()
    }

    fun getPreset(profile: Int = this.profile): String {
        return try {
            val gains = dolbyEffect?.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
                ?: IntArray(DsParam.GEQ_BAND_GAINS.length)
            gains.joinToString(separator = ",").also {
                dlog(TAG, "getPreset: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getPreset", e)
            IntArray(DsParam.GEQ_BAND_GAINS.length).joinToString(",")
        }
    }

    fun setPreset(value: String, profile: Int = this.profile) {
        dlog(TAG, "setPreset: $value")
        if (checkEffect()) {
            try {
                val gains = value.split(",")
                    .map { it.toInt() }
                    .toIntArray()
                dolbyEffect?.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setPreset", e)
            }
        }
    }

    fun getPresetName(): String {
        val preset = getPreset()
        val presets = context.resources.getStringArray(R.array.dolby_preset_values)
        val userPresets = context.getSharedPreferences(PREF_PRESETS, Context.MODE_PRIVATE)
        return if (presets.contains(preset)) {
            context.resources.getStringArray(R.array.dolby_preset_entries)[presets.indexOf(preset)]
        } else {
            userPresets.all.entries.firstOrNull { it.value == preset }?.key
                ?: context.getString(R.string.dolby_preset_custom)
        }
    }

    fun getHeadphoneVirtEnabled(profile: Int = this.profile): Boolean =
        try {
            (dolbyEffect?.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile) ?: false).also {
                dlog(TAG, "getHeadphoneVirtEnabled: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getHeadphoneVirtEnabled", e)
            false
        }

    fun setHeadphoneVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setHeadphoneVirtEnabled: $value")
        if (checkEffect()) {
            try {
                dolbyEffect?.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, value, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setHeadphoneVirtEnabled", e)
            }
        }
    }

    fun getSpeakerVirtEnabled(profile: Int = this.profile): Boolean =
        try {
            (dolbyEffect?.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile) ?: false).also {
                dlog(TAG, "getSpeakerVirtEnabled: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getSpeakerVirtEnabled", e)
            false
        }

    fun setSpeakerVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setSpeakerVirtEnabled: $value")
        if (checkEffect()) {
            try {
                dolbyEffect?.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, value, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setSpeakerVirtEnabled", e)
            }
        }
    }

    fun getBassEnhancerEnabled(profile: Int = this.profile): Boolean =
        try {
            (dolbyEffect?.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile) ?: false).also {
                dlog(TAG, "getBassEnhancerEnabled: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getBassEnhancerEnabled", e)
            false
        }

    fun setBassEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setBassEnhancerEnabled: $value")
        if (checkEffect()) {
            try {
                dolbyEffect?.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, value, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setBassEnhancerEnabled", e)
            }
        }
    }

    fun getVolumeLevelerEnabled(profile: Int = this.profile): Boolean =
        try {
            (dolbyEffect?.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile) ?: false).also {
                dlog(TAG, "getVolumeLevelerEnabled: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getVolumeLevelerEnabled", e)
            false
        }

    fun setVolumeLevelerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setVolumeLevelerEnabled: $value")
        if (checkEffect()) {
            try {
                dolbyEffect?.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, value, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setVolumeLevelerEnabled", e)
            }
        }
    }

    fun getStereoWideningAmount(profile: Int = this.profile): Int =
        if (!stereoWideningSupported) {
            0
        } else {
            try {
                (dolbyEffect?.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile) ?: 0).also {
                    dlog(TAG, "getStereoWideningAmount: $it")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to getStereoWideningAmount", e)
                0
            }
        }

    fun setStereoWideningAmount(value: Int, profile: Int = this.profile) {
        if (!stereoWideningSupported) return
        dlog(TAG, "setStereoWideningAmount: $value")
        if (checkEffect()) {
            try {
                dolbyEffect?.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, value, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setStereoWideningAmount", e)
            }
        }
    }

    fun getDialogueEnhancerEnabled(profile: Int = this.profile): Boolean =
        try {
            (dolbyEffect?.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile) ?: false).also {
                dlog(TAG, "getDialogueEnhancerEnabled: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getDialogueEnhancerEnabled", e)
            false
        }

    fun setDialogueEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setDialogueEnhancerEnabled: $value")
        if (checkEffect()) {
            try {
                dolbyEffect?.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, value, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setDialogueEnhancerEnabled", e)
            }
        }
    }

    fun getDialogueEnhancerAmount(profile: Int = this.profile): Int =
        try {
            (dolbyEffect?.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile) ?: 0).also {
                dlog(TAG, "getDialogueEnhancerAmount: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getDialogueEnhancerAmount", e)
            0
        }

    fun setDialogueEnhancerAmount(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setDialogueEnhancerAmount: $value")
        if (checkEffect()) {
            try {
                dolbyEffect?.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, value, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setDialogueEnhancerAmount", e)
            }
        }
    }

    fun getIeqPreset(profile: Int = this.profile): Int =
        try {
            (dolbyEffect?.getDapParameterInt(DsParam.IEQ_PRESET, profile) ?: 0).also {
                dlog(TAG, "getIeqPreset: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to getIeqPreset", e)
            0
        }

    fun setIeqPreset(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setIeqPreset: $value")
        if (checkEffect()) {
            try {
                dolbyEffect?.setDapParameter(DsParam.IEQ_PRESET, value, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setIeqPreset", e)
            }
        }
    }

    companion object {
        private const val TAG = "DolbyController"
        private const val EFFECT_PRIORITY = 100
        private const val PREF_PRESETS = "presets"
        private const val PREF_KEY_PRESETS_MIGRATED = "presets_migrated"

        @Volatile
        private var instance: DolbyController? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: DolbyController(context).also { instance = it }
            }
    }
}

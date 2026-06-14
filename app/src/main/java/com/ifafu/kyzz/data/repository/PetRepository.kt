package com.ifafu.kyzz.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.ifafu.kyzz.data.model.Pet
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PetRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("pet_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val TAG = "PetRepository"
    }

    fun loadPet(): Pet {
        val json = prefs.getString("pet", null)
        return if (json != null) {
            try {
                gson.fromJson(json, Pet::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize Pet data, returning new Pet", e)
                Pet()
            }
        } else {
            Pet()
        }
    }

    fun savePet(pet: Pet) {
        prefs.edit().putString("pet", gson.toJson(pet)).apply()
    }

    fun getLastGradeAdviceTime(): Long = prefs.getLong("last_grade_advice", 0L)

    fun setLastGradeAdviceTime(time: Long) {
        prefs.edit().putLong("last_grade_advice", time).apply()
    }
}

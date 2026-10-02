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

        // 必须是类级锁而非实例字段：除 Hilt 单例外，PomodoroTimerActivity 等处
        // 仍会直接构造实例读写同一份 pet_data。锁若挂在实例上，两把互不互斥，
        // update() 的读改写互斥对旁路实例完全失效（番茄钟加的经验被首页全量写回滚）。
        private val petLock = Any()
    }

    fun loadPet(): Pet = synchronized(petLock) {
        val json = prefs.getString("pet", null)
        if (json != null) {
            try {
                val saved = com.google.gson.JsonParser.parseString(json)
                if (!saved.isJsonObject) return Pet()
                // Merge into constructor defaults; explicit nulls must not violate Kotlin fields.
                val merged = gson.toJsonTree(Pet()).asJsonObject
                saved.asJsonObject.entrySet().forEach { (key, value) ->
                    if (!value.isJsonNull) merged.add(key, value)
                }
                val pet = gson.fromJson(merged, Pet::class.java)
                if ((pet.state as com.ifafu.kyzz.data.model.PetState?) == null) {
                    pet.state = com.ifafu.kyzz.data.model.PetState.IDLE
                }
                pet.level = pet.level.coerceIn(1, 10000)
                pet.exp = pet.exp.coerceAtLeast(0)
                pet.hunger = pet.hunger.coerceIn(0, 100)
                pet.mood = pet.mood.coerceIn(0, 100)
                pet.feedCount = pet.feedCount.coerceIn(0, Pet.MAX_CHARGE)
                pet.playCount = pet.playCount.coerceIn(0, Pet.MAX_CHARGE)
                pet
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize Pet data, returning new Pet", e)
                Pet()
            }
        } else {
            Pet()
        }
    }

    fun savePet(pet: Pet) {
        synchronized(petLock) {
            prefs.edit().putString("pet", gson.toJson(pet)).apply()
        }
    }

    /**
     * 互斥的"读-改-写"。宠物数据被首页 ViewModel、设置页(改名/换类型)、番茄钟
     * (+经验)多处写入：各自 load→改→save 的两步操作交错时会互相覆盖（番茄钟加
     * 的经验被首页下一次全量写回滚、改的名字被回退）。外部写点统一走这里。
     */
    fun update(block: (Pet) -> Unit): Pet {
        synchronized(petLock) {
            val pet = loadPet()
            block(pet)
            savePet(pet)
            return pet
        }
    }

    fun getLastGradeAdviceTime(): Long = prefs.getLong("last_grade_advice", 0L)

    fun setLastGradeAdviceTime(time: Long) {
        prefs.edit().putLong("last_grade_advice", time).apply()
    }
}

package com.ifafu.kyzz.data.repository

import android.app.Application
import android.content.Context
import com.ifafu.kyzz.data.model.Note
import com.ifafu.kyzz.data.model.PetState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalRepositoryTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        listOf("notes_prefs", "pet_data").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun `null and damaged notes do not crash or discard other valid notes`() {
        val prefs = context.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("notes", "null").commit()
        val repo = NoteRepository(context)
        assertTrue(repo.loadNotes().isEmpty())
        prefs.edit().putString("notes", """[null,{"id":"valid","content":"保留内容","title":null},
            {"id":"broken","content":null}]""").commit()
        assertEquals("保留内容", repo.loadNotes().single().content)
        assertEquals("", repo.loadNotes().single().title)
        repo.upsertNote(Note(id = "new", content = "新增"))
        assertEquals(2, repo.loadNotes().size)
    }

    @Test fun `old and null pet fields recover defaults without reset of progress`() {
        val prefs = context.getSharedPreferences("pet_data", Context.MODE_PRIVATE)
        prefs.edit().putString("pet", "null").commit()
        val repo = PetRepository(context)
        assertEquals(1, repo.loadPet().level)
        prefs.edit().putString("pet", """{"name":null,"state":"UNKNOWN","level":4,"mood":999}""").commit()
        val pet = repo.loadPet()
        assertEquals(4, pet.level)
        assertEquals("小农", pet.name)
        assertEquals(PetState.IDLE, pet.state)
        assertEquals(100, pet.mood)
        assertTrue(pet.lastTickTime > 0)
    }

    @Test fun `concurrent repository instances preserve every pet update`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..4).map {
                pool.submit {
                    val repo = PetRepository(context)
                    repeat(50) { repo.update { pet -> pet.points += 1 } }
                }
            }
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(200, PetRepository(context).loadPet().points)
        } finally { pool.shutdownNow() }
    }
}

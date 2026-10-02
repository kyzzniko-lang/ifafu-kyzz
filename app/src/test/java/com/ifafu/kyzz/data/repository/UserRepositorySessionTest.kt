package com.ifafu.kyzz.data.repository

import android.app.Application
import android.content.Context
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.User
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserRepositorySessionTest {
    private lateinit var repository: UserRepository

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ifafu_secure", Context.MODE_PRIVATE).edit().clear().commit()
        repository = UserRepository(context, CacheManager(context))
        repository.saveAuthenticatedUser(User(account = "A", token = "old", isLogin = true), "password")
    }

    @Test
    fun sameAccountReloginKeepsActiveSessionIdentity() {
        val before = repository.getAuthSnapshot()
        assertTrue(repository.saveReloginIfUnchanged(before.user.copy(token = "fresh"), before.password, "A", before.generation))
        assertTrue(repository.isSessionCurrent(before))
        assertFalse(repository.isAuthSnapshotCurrent(before))
        assertEquals("fresh", repository.getUser().token)
    }

    @Test
    fun manualSemesterDateCannotBeOverwrittenByAutomaticCommit() {
        repository.setManualTermFirstDay("2026-08-31 00:00:00")
        assertFalse(repository.setAutomaticTermFirstDay("2026-09-07 00:00:00"))
        assertTrue(repository.termFirstDayManual)
        assertEquals("2026-08-31 00:00:00", repository.termFirstDay)
    }

    @Test
    fun switchAwayAndBackInvalidatesOldWork() {
        val before = repository.getAuthSnapshot()
        repository.switchAccount(UserRepository.AccountProfile("B", "B", "password"))
        repository.saveAuthenticatedUser(before.user.copy(token = "newA"), "password")
        assertFalse(repository.isSessionCurrent(before))
        assertFalse(repository.saveAuthenticatedUserIfUnchanged(before.user, "password", before))
        var executed = false
        assertFalse(repository.runIfSessionCurrent(before) { executed = true })
        assertFalse(executed)
        assertEquals("newA", repository.getUser().token)
    }

    @Test
    fun logoutRejectsOldReloginAndCacheCommit() {
        val before = repository.getAuthSnapshot()
        repository.clearUser()
        assertFalse(repository.saveReloginIfUnchanged(before.user, before.password, "A", before.generation))
        assertFalse(repository.isSessionCurrent(before))
        assertTrue(repository.getUser().account.isEmpty())
    }

    @Test
    fun passwordAndHostChangesInvalidateWork() {
        val before = repository.getAuthSnapshot()
        repository.savePassword("changed")
        assertFalse(repository.isSessionCurrent(before))
        val afterPassword = repository.getAuthSnapshot()
        repository.host = "https://jwgl.example"
        assertFalse(repository.isSessionCurrent(afterPassword))
    }

    @Test
    fun switchingAccountDropsPreviousManualTermDate() {
        repository.termFirstDay = "2026-09-01"
        repository.termFirstDayManual = true
        repository.switchAccount(UserRepository.AccountProfile("B", "B", "password"))
        assertEquals("", repository.termFirstDay)
        assertFalse(repository.termFirstDayManual)
    }

    @Test
    fun malformedSavedProfilesDoNotEscapeAsNullValues() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ifafu_secure", Context.MODE_PRIVATE)
            .edit().putString("saved_accounts", """[null,{}, {"account":"B","name":null,"password":null}]""").apply()
        assertEquals(listOf(UserRepository.AccountProfile("B", "", "")), repository.getAccountProfiles())
    }

    @Test
    fun passwordChangeForOldSnapshotCannotReplaceNewAccountsPassword() {
        val before = repository.getAuthSnapshot()
        repository.saveAuthenticatedUser(User(account = "B", token = "B-token", isLogin = true), "B-password")
        assertFalse(repository.runIfSessionCurrent(before) { repository.savePassword("A-new-password") })
        assertEquals("B-password", repository.getPassword())
        assertEquals("B", repository.getUser().account)
    }
}

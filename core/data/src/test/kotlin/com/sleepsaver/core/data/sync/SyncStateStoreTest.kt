package com.sleepsaver.core.data.sync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectPermissions
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SyncStateStoreTest {

    @Test
    fun damagedFile_isReplacedInsteadOfFailingEveryWrite() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.preferencesDataStoreFile(SyncStateStore.FILE_NAME).apply {
            parentFile?.mkdirs()
            writeText("not a preferences file")
        }
        val store = SyncStateStore(context)

        assertThat(store.current()).isEqualTo(SyncStateSnapshot())
        store.saveToken("token-1", "health_connect", setOf(HealthConnectPermissions.SLEEP))
        assertThat(store.current().changesToken).isEqualTo("token-1")
        assertThat(store.current().tokenScope).containsExactly(HealthConnectPermissions.SLEEP)
    }
}

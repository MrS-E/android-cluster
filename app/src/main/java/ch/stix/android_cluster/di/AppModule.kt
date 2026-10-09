package ch.stix.android_cluster.di

import android.content.Context
import ch.stix.android_cluster.engine.QemuEngine
import ch.stix.android_cluster.engine.VmEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideVmEngine(@ApplicationContext context: Context): VmEngine = QemuEngine(
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        ioDispatcher = Dispatchers.IO,
    )
}
